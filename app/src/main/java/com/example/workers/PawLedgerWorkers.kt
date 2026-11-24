package com.example.workers

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.MainActivity
import com.example.R
import com.example.data.PawLedgerRepository
import com.example.data.local.PawLedgerDatabase
import com.example.data.local.UserPreferencesStore
import com.example.data.remote.FirestoreSyncRepository
import com.example.domain.CurrencyFormatters
import com.example.domain.IsoDate
import com.example.security.EncryptionManager
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

object NotificationChannels {
    const val DEADLINE_CHANNEL_ID = "pawledger_deadlines"

    fun ensureCreated(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                DEADLINE_CHANNEL_ID,
                "Payment Deadlines & Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Upcoming installment reminders, overdue alerts, and recurring log summaries."
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }
}

class RecurringAndReminderWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = UserPreferencesStore(applicationContext).current()
        val uid = Firebase.auth.currentUser?.uid ?: prefs.lastKnownUid ?: return Result.success()

        val localDb = PawLedgerDatabase.getInstance(applicationContext)
        val firestore = FirebaseFirestore.getInstance(
            applicationContext.getString(R.string.firestore_database_id)
        )
        val syncRepo = FirestoreSyncRepository(
            db = firestore,
            localDb = localDb,
            encryptionManager = EncryptionManager(),
            appContext = applicationContext
        )
        val repo = PawLedgerRepository(localDb, syncRepo, applicationContext)

        val overdueCount = repo.evaluateOverdueForUser(uid)
        val autoLoggedCount = repo.processRecurringAutoLogs(uid)

        if (prefs.notificationsEnabled) {
            postDeadlineSummaryNotification(
                localDb = localDb,
                uid = uid,
                daysBefore = prefs.reminderDaysBefore,
                overdueCount = overdueCount,
                autoLoggedCount = autoLoggedCount
            )
        }

        return Result.success()
    }

    private suspend fun postDeadlineSummaryNotification(
        localDb: PawLedgerDatabase,
        uid: String,
        daysBefore: Int,
        overdueCount: Int,
        autoLoggedCount: Int
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return
        }

        val today = IsoDate.todayIso()
        val open = localDb.installmentDao().observeUpcomingOpenInstallments(uid).first()
        val upcomingSoon = open.filter { row ->
            val diff = IsoDate.daysBetween(today, row.dueDateIso)
            diff in 0L..daysBefore.toLong()
        }

        if (overdueCount == 0 && upcomingSoon.isEmpty() && autoLoggedCount == 0) return

        val title = when {
            overdueCount > 0 -> "$overdueCount Overdue Installment Alert${if (overdueCount > 1) "s" else ""}"
            upcomingSoon.isNotEmpty() -> "${upcomingSoon.size} Payment Deadline${if (upcomingSoon.size > 1) "s" else ""} Approaching"
            else -> "$autoLoggedCount Recurring Payment${if (autoLoggedCount > 1) "s" else ""} Logged"
        }

        val detail = buildString {
            if (overdueCount > 0) append("$overdueCount past grace period. ")
            upcomingSoon.firstOrNull()?.let { first ->
                val amt = CurrencyFormatters.formatMinor(first.outstandingMinor, first.currencyCode)
                append("Next: ${first.counterpartyName} ($amt due ${IsoDate.displayFriendly(first.dueDateIso)}). ")
            }
            if (autoLoggedCount > 0) append("$autoLoggedCount recurring entry auto-logged.")
        }.trim()

        val intent = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            101,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        NotificationChannels.ensureCreated(applicationContext)
        val notification = NotificationCompat.Builder(applicationContext, NotificationChannels.DEADLINE_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(2026, notification)
    }

    companion object {
        private const val WORK_NAME = "pawledger_recurring_and_reminders"

        fun schedulePeriodic(context: Context) {
            synchronized(RecurringAndReminderWorker::class.java) {
                if (!WorkManager.isInitialized()) {
                    WorkManager.initialize(
                        context.applicationContext,
                        androidx.work.Configuration.Builder().build()
                    )
                }
            }
            val request = PeriodicWorkRequestBuilder<RecurringAndReminderWorker>(12, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
