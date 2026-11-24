package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.example.R
import com.example.data.local.CounterpartyEntity
import com.example.data.local.PaymentTransactionEntity
import com.example.domain.ContractStatus
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import com.example.reminders.ReminderDispatcher
import com.example.security.ProofImageProcessor
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MonoStyles
import java.io.File

@Composable
fun MascotAvatar(
    size: Dp = 44.dp,
    modifier: Modifier = Modifier
) {
    AsyncImage(
        model = R.drawable.img_paw_mascot,
        contentDescription = "PawLedger cartoon dog mascot",
        contentScale = ContentScale.Crop,
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.6f), CircleShape)
    )
}

@Composable
fun LedgerCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    borderColor: Color = LocalSemanticColors.current.border,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
            color = containerColor,
            border = BorderStroke(1.dp, borderColor),
            tonalElevation = 1.dp
        ) {
            Column(modifier = Modifier.padding(16.dp), content = content)
        }
    }
}

@Composable
fun StatusPill(
    label: String,
    textColor: Color,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(999.dp),
        color = containerColor
    ) {
        Text(
            text = label,
            style = MonoStyles.BadgeMetric,
            color = textColor,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun ContractStatusBadge(statusRaw: String) {
    val sem = LocalSemanticColors.current
    val status = ContractStatus.fromRaw(statusRaw)
    val (fg, bg) = when (status) {
        ContractStatus.ACTIVE -> sem.info to sem.info.copy(alpha = 0.14f)
        ContractStatus.OVERDUE -> sem.danger to sem.dangerContainer
        ContractStatus.SETTLED -> sem.positive to sem.positiveContainer
        ContractStatus.DEFAULTED -> sem.warning to sem.warningContainer
    }
    StatusPill(label = status.label.uppercase(), textColor = fg, containerColor = bg)
}

@Composable
fun DirectionPill(directionRaw: String) {
    val sem = LocalSemanticColors.current
    val dir = LoanDirection.fromRaw(directionRaw)
    val (fg, bg) = if (dir == LoanDirection.LENT) {
        sem.positive to sem.positiveContainer
    } else {
        sem.warning to sem.warningContainer
    }
    StatusPill(label = dir.label, textColor = fg, containerColor = bg)
}

@Composable
fun MascotEmptyState(
    title: String,
    subtitle: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    LedgerCard(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MascotAvatar(size = 84.dp)
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onAction,
                    modifier = Modifier.testTag("empty_state_action_button")
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
fun ProofPreviewDialog(
    payment: PaymentTransactionEntity,
    onDismiss: () -> Unit
) {
    var verified by remember(payment.id) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(payment.id, payment.proofLocalUri, payment.proofSha256) {
        verified = ProofImageProcessor.verifyFileIntegrity(payment.proofLocalUri, payment.proofSha256)
    }

    val sem = LocalSemanticColors.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, sem.border)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Proof of Payment", style = MaterialTheme.typography.titleMedium)
                    if (verified == true) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Outlined.Verified,
                                contentDescription = "SHA-256 verified",
                                tint = sem.positive,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "SHA-256 Verified",
                                style = MonoStyles.BadgeMetric,
                                color = sem.positive
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                val file = payment.proofLocalUri?.let { File(it) }?.takeIf { it.exists() }
                if (file != null) {
                    AsyncImage(
                        model = file,
                        contentDescription = "Compressed proof of payment image",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(MaterialTheme.shapes.medium)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "Proof file unavailable locally.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                payment.proofSha256?.let { hash ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "SHA-256: ${hash.take(24)}...",
                        style = MonoStyles.BadgeMetric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderChannelSheet(
    counterparty: CounterpartyEntity?,
    contractTitle: String,
    direction: LoanDirection,
    amountDueMinor: Long,
    remainingMinor: Long,
    currencyCode: String,
    dueDateIso: String?,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val name = counterparty?.name ?: "there"
    var message by remember(contractTitle, amountDueMinor, dueDateIso) {
        mutableStateOf(
            ReminderDispatcher.buildDefaultMessage(
                counterpartyName = name,
                contractTitle = contractTitle,
                direction = direction,
                amountDueMinor = amountDueMinor,
                remainingMinor = remainingMinor,
                currencyCode = currencyCode,
                dueDateIso = dueDateIso
            )
        )
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Send Payment Reminder", style = MaterialTheme.typography.titleLarge)
            Text(
                "Edit the pre-filled note and choose your messaging app.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Reminder Message") },
                minLines = 3,
                maxLines = 6,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("reminder_message_input")
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        val wa = counterparty?.whatsapp?.ifBlank { counterparty.phone } ?: ""
                        ReminderDispatcher.launchWhatsApp(context, wa, message)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("send_whatsapp_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF16A34A),
                        contentColor = Color.White
                    )
                ) {
                    Icon(Icons.AutoMirrored.Rounded.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("WhatsApp", fontWeight = FontWeight.SemiBold)
                }
                OutlinedButton(
                    onClick = {
                        ReminderDispatcher.launchSms(context, counterparty?.phone ?: "", message)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("send_sms_button")
                ) {
                    Icon(Icons.Outlined.Sms, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("SMS")
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = {
                        ReminderDispatcher.launchEmail(
                            context,
                            counterparty?.email ?: "",
                            "Payment Reminder: $contractTitle",
                            message
                        )
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("send_email_button")
                ) {
                    Icon(Icons.Outlined.Email, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Email")
                }
                OutlinedButton(
                    onClick = {
                        ReminderDispatcher.launchShareSheet(context, message)
                        onDismiss()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("send_share_button")
                ) {
                    Icon(Icons.Outlined.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Other App")
                }
            }
        }
    }
}

@Composable
fun EncryptionIndicatorBadge(e2eEnabled: Boolean, unlocked: Boolean) {
    val sem = LocalSemanticColors.current
    if (!e2eEnabled) return
    val (label, color, bg) = if (unlocked) {
        Triple("E2E AES-256", sem.positive, sem.positiveContainer)
    } else {
        Triple("E2E Locked", sem.warning, sem.warningContainer)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Outlined.Lock,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(12.dp)
            )
            Spacer(Modifier.width(4.dp))
            Text(label, style = MonoStyles.BadgeMetric, color = color)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IsoDatePickerField(
    label: String,
    selectedDateIso: String,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    testTag: String = "date_picker_field",
    enabled: Boolean = true
) {
    var showPicker by remember { mutableStateOf(false) }
    val displayValue = remember(selectedDateIso) {
        if (IsoDate.isValid(selectedDateIso)) {
            "${IsoDate.displayFriendly(selectedDateIso)} ($selectedDateIso)"
        } else {
            selectedDateIso
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = displayValue,
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = {
                IconButton(
                    onClick = { if (enabled) showPicker = true },
                    enabled = enabled
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CalendarToday,
                        contentDescription = "Select $label"
                    )
                }
            },
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .clickable(enabled = enabled) { showPicker = true }
        )
    }

    if (showPicker) {
        val initialMillis = IsoDate.toUtcMillis(selectedDateIso)
            ?: IsoDate.toUtcMillis(IsoDate.todayIso())
        val pickerState = rememberDatePickerState(initialSelectedDateMillis = initialMillis)

        DatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        pickerState.selectedDateMillis?.let { millis ->
                            onDateSelected(IsoDate.fromUtcMillis(millis))
                        }
                        showPicker = false
                    },
                    modifier = Modifier.testTag("${testTag}_confirm")
                ) {
                    Text("Select")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = pickerState)
        }
    }
}
