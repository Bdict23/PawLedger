package com.example.reminders

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.example.domain.CurrencyFormatters
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import java.net.URLEncoder

object ReminderDispatcher {
    fun buildDefaultMessage(
        counterpartyName: String,
        contractTitle: String,
        direction: LoanDirection,
        amountDueMinor: Long,
        remainingMinor: Long,
        currencyCode: String,
        dueDateIso: String?
    ): String {
        val dueAmt = CurrencyFormatters.formatMinor(amountDueMinor, currencyCode)
        val remAmt = CurrencyFormatters.formatMinor(remainingMinor, currencyCode)
        val dueText = dueDateIso?.let { IsoDate.displayFriendly(it) } ?: "soon"
        return if (direction == LoanDirection.LENT) {
            "Hi $counterpartyName, friendly reminder from PawLedger regarding \"$contractTitle\": $dueAmt is due on $dueText (remaining balance: $remAmt). Thank you!"
        } else {
            "Hi $counterpartyName, quick note regarding \"$contractTitle\": I have an upcoming payment of $dueAmt scheduled for $dueText (remaining balance: $remAmt)."
        }
    }

    fun launchWhatsApp(context: Context, phoneOrHandle: String, message: String) {
        val cleaned = phoneOrHandle.filter { it.isDigit() }
        val encoded = URLEncoder.encode(message, "UTF-8")
        val uri = if (cleaned.isNotBlank()) {
            Uri.parse("https://wa.me/$cleaned?text=$encoded")
        } else {
            Uri.parse("https://wa.me/?text=$encoded")
        }
        val intent = Intent(Intent.ACTION_VIEW, uri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { launchShareSheet(context, message) }
    }

    fun launchSms(context: Context, phone: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("smsto:${Uri.encode(phone)}")
            putExtra("sms_body", message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { launchShareSheet(context, message) }
    }

    fun launchEmail(context: Context, email: String, subject: String, message: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:")
            if (email.isNotBlank()) putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
            .onFailure { launchShareSheet(context, message) }
    }

    fun launchShareSheet(context: Context, message: String) {
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
        }
        val chooser = Intent.createChooser(sendIntent, "Send Payment Reminder").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(chooser)
    }
}
