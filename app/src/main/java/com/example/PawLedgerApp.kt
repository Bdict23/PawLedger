package com.example

import android.app.Application
import com.example.workers.NotificationChannels
import com.example.workers.RecurringAndReminderWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PawLedgerApp : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        val appContext = applicationContext
        appScope.launch {
            NotificationChannels.ensureCreated(appContext)
            RecurringAndReminderWorker.schedulePeriodic(appContext)
        }
    }
}
