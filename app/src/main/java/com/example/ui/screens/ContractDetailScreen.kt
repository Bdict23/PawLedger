package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.data.local.InstallmentEntity
import com.example.data.local.PaymentTransactionEntity
import com.example.domain.ContractStatus
import com.example.domain.CurrencyFormatters
import com.example.domain.InstallmentStatus
import com.example.domain.InterestMode
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import com.example.domain.RepaymentFrequency
import com.example.ui.PawLedgerViewModel
import com.example.ui.components.ContractStatusBadge
import com.example.ui.components.DirectionPill
import com.example.ui.components.IsoDatePickerField
import com.example.ui.components.LedgerCard
import com.example.ui.components.ProofPreviewDialog
import com.example.ui.components.ReminderChannelSheet
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MonoStyles
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContractDetailScreen(
    contractId: String,
    viewModel: PawLedgerViewModel,
    onBack: () -> Unit,
    onEditContract: () -> Unit = {}
) {
    val contracts by viewModel.contracts.collectAsStateWithLifecycle()
    val counterparties by viewModel.counterparties.collectAsStateWithLifecycle()
    val installments by viewModel.observeInstallments(contractId).collectAsStateWithLifecycle(initialValue = emptyList())
    val payments by viewModel.observePayments(contractId).collectAsStateWithLifecycle(initialValue = emptyList())

    val contract = contracts.firstOrNull { it.id == contractId }
    val counterparty = counterparties.firstOrNull { it.id == contract?.counterpartyId }

    var showPaymentSheet by remember { mutableStateOf(false) }
    var editingPayment by remember { mutableStateOf<PaymentTransactionEntity?>(null) }
    var editingInstallment by remember { mutableStateOf<InstallmentEntity?>(null) }
    var showReminderSheet by remember { mutableStateOf(false) }
    var showDeleteContractDialog by remember { mutableStateOf(false) }
    var inspectingPayment by remember { mutableStateOf<PaymentTransactionEntity?>(null) }

    val sem = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contract?.title ?: "Contract Details") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (contract != null) {
                        IconButton(
                            onClick = onEditContract,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("detail_edit_contract_button")
                        ) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Edit contract")
                        }
                        IconButton(
                            onClick = { showDeleteContractDialog = true },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("detail_delete_contract_button")
                        ) {
                            Icon(
                                Icons.Outlined.DeleteOutline,
                                contentDescription = "Remove contract",
                                tint = sem.danger
                            )
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (contract == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Contract not found.", style = MaterialTheme.typography.titleMedium)
            }
            return@Scaffold
        }

        val isSettled = contract.status == ContractStatus.SETTLED.name
        val progress = if (contract.totalScheduledMinor > 0L) {
            (contract.totalRepaidMinor.toFloat() / contract.totalScheduledMinor.toFloat()).coerceIn(0f, 1f)
        } else 0f

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                LedgerCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            DirectionPill(contract.direction)
                            ContractStatusBadge(contract.status)
                        }
                        Text(
                            RepaymentFrequency.fromRaw(contract.frequency).label,
                            style = MonoStyles.BadgeMetric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(contract.counterpartyName, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${InterestMode.fromRaw(contract.interestMode).label} • ${contract.interestRateBps / 100.0}% • Grace ${contract.graceDays}d",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text("Remaining Balance", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                CurrencyFormatters.formatMinor(contract.remainingMinor, contract.currencyCode),
                                style = MonoStyles.HeroAmount
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Repaid", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                CurrencyFormatters.formatMinor(contract.totalRepaidMinor, contract.currencyCode),
                                style = MonoStyles.RowAmount,
                                color = sem.positive
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(7.dp),
                        color = sem.positive,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = {
                                editingPayment = null
                                showPaymentSheet = true
                            },
                            enabled = !isSettled,
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("detail_record_payment_button")
                        ) {
                            Icon(Icons.Outlined.Payments, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(if (isSettled) "Settled" else "Log Payment")
                        }
                        OutlinedButton(
                            onClick = { showReminderSheet = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("detail_send_reminder_button")
                        ) {
                            Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remind")
                        }
                    }
                    if (!isSettled) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Automated Recurring Log", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Auto-log installments on their due date",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = contract.autoLogRecurring,
                                onCheckedChange = { viewModel.toggleAutoLog(contract.id, it) },
                                modifier = Modifier.testTag("detail_autolog_switch")
                            )
                        }
                    }
                }
            }

            item {
                Text("Repayment Schedule (${installments.size})", style = MaterialTheme.typography.titleMedium)
            }

            items(installments, key = { it.id }) { inst ->
                InstallmentRowCard(
                    inst = inst,
                    currencyCode = contract.currencyCode,
                    onEdit = { editingInstallment = inst }
                )
            }

            item {
                Text("Verified Payments (${payments.size})", style = MaterialTheme.typography.titleMedium)
            }

            if (payments.isEmpty()) {
                item {
                    LedgerCard {
                        Text(
                            "No payments logged yet. Tap \"Log Payment\" to record a repayment and attach proof.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(payments, key = { it.id }) { pay ->
                    PaymentRowCard(
                        payment = pay,
                        currencyCode = contract.currencyCode,
                        onInspectProof = { inspectingPayment = pay },
                        onEdit = {
                            editingPayment = pay
                            showPaymentSheet = true
                        }
                    )
                }
            }
        }
    }

    if (showDeleteContractDialog && contract != null) {
        AlertDialog(
            onDismissRequest = { showDeleteContractDialog = false },
            title = { Text("Remove Contract?") },
            text = {
                Text("This permanently deletes \"${contract.title}\", its repayment schedule, and all recorded payment history.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteContractDialog = false
                        viewModel.deleteContract(contract.id) { onBack() }
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = sem.danger,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("detail_confirm_delete_contract_button")
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteContractDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    if (showPaymentSheet && contract != null) {
        val activeEdit = editingPayment
        val suggested = if (contract.nextDueAmountMinor > 0L) contract.nextDueAmountMinor else contract.remainingMinor
        RecordPaymentSheet(
            currencyCode = contract.currencyCode,
            defaultAmountMinor = suggested,
            editingPayment = activeEdit,
            onDismiss = {
                showPaymentSheet = false
                editingPayment = null
            },
            onSubmit = { amountMinor, paidDateIso, note, proofUri, removeProof, onDone, onFail ->
                if (activeEdit == null) {
                    viewModel.recordPayment(
                        contractId = contract.id,
                        amountMinor = amountMinor,
                        paidDateIso = paidDateIso,
                        note = note,
                        proofUri = proofUri,
                        onSuccess = {
                            onDone()
                            showPaymentSheet = false
                        },
                        onError = { errMsg ->
                            onFail(errMsg)
                        }
                    )
                } else {
                    viewModel.updatePayment(
                        contractId = contract.id,
                        paymentId = activeEdit.id,
                        amountMinor = amountMinor,
                        paidDateIso = paidDateIso,
                        note = note,
                        proofUri = proofUri,
                        removeProof = removeProof,
                        onSuccess = {
                            onDone()
                            showPaymentSheet = false
                            editingPayment = null
                        },
                        onError = { errMsg ->
                            onFail(errMsg)
                        }
                    )
                }
            },
            onDelete = if (activeEdit != null) {
                { onDone, onFail ->
                    viewModel.deletePayment(
                        contractId = contract.id,
                        paymentId = activeEdit.id,
                        onSuccess = {
                            onDone()
                            showPaymentSheet = false
                            editingPayment = null
                        },
                        onError = { errMsg ->
                            onFail(errMsg)
                        }
                    )
                }
            } else null
        )
    }

    editingInstallment?.let { inst ->
        if (contract != null) {
            EditInstallmentSheet(
                installment = inst,
                currencyCode = contract.currencyCode,
                onDismiss = { editingInstallment = null },
                onSave = { dueDateIso, principalMinor, interestMinor, lateFeeMinor, onDone, onFail ->
                    viewModel.updateInstallment(
                        contractId = contract.id,
                        installmentId = inst.id,
                        dueDateIso = dueDateIso,
                        principalDueMinor = principalMinor,
                        interestDueMinor = interestMinor,
                        lateFeeDueMinor = lateFeeMinor,
                        onSuccess = {
                            onDone()
                            editingInstallment = null
                        },
                        onError = { errMsg ->
                            onFail(errMsg)
                        }
                    )
                }
            )
        }
    }

    if (showReminderSheet && contract != null) {
        ReminderChannelSheet(
            counterparty = counterparty,
            contractTitle = contract.title,
            direction = LoanDirection.fromRaw(contract.direction),
            amountDueMinor = if (contract.nextDueAmountMinor > 0L) contract.nextDueAmountMinor else contract.remainingMinor,
            remainingMinor = contract.remainingMinor,
            currencyCode = contract.currencyCode,
            dueDateIso = contract.nextDueDateIso,
            onDismiss = { showReminderSheet = false }
        )
    }

    inspectingPayment?.let { pay ->
        ProofPreviewDialog(payment = pay, onDismiss = { inspectingPayment = null })
    }
}

@Composable
private fun InstallmentRowCard(
    inst: InstallmentEntity,
    currencyCode: String,
    onEdit: () -> Unit
) {
    val sem = LocalSemanticColors.current
    val status = InstallmentStatus.fromRaw(inst.status)
    val totalDue = inst.principalDueMinor + inst.interestDueMinor + inst.lateFeeDueMinor
    val (fg, bg) = when (status) {
        InstallmentStatus.PAID -> sem.positive to sem.positiveContainer
        InstallmentStatus.OVERDUE -> sem.danger to sem.dangerContainer
        InstallmentStatus.PARTIAL -> sem.warning to sem.warningContainer
        InstallmentStatus.PENDING -> sem.info to sem.info.copy(alpha = 0.14f)
    }

    LedgerCard(onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Installment #${inst.installmentNumber} • ${IsoDate.displayFriendly(inst.dueDateIso)}",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "Principal ${CurrencyFormatters.formatMinor(inst.principalDueMinor, currencyCode)} • Interest ${CurrencyFormatters.formatMinor(inst.interestDueMinor, currencyCode)}" +
                        if (inst.lateFeeDueMinor > 0L) " • Fee ${CurrencyFormatters.formatMinor(inst.lateFeeDueMinor, currencyCode)}" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CurrencyFormatters.formatMinor(totalDue, currencyCode),
                    style = MonoStyles.RowAmount
                )
                Spacer(Modifier.height(4.dp))
                StatusPill(status.label.uppercase(), fg, bg)
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_installment_button_${inst.id}")
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit installment #${inst.installmentNumber}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun PaymentRowCard(
    payment: PaymentTransactionEntity,
    currencyCode: String,
    onInspectProof: () -> Unit,
    onEdit: () -> Unit
) {
    val sem = LocalSemanticColors.current
    val hasProof = !payment.proofLocalUri.isNullOrBlank() || !payment.proofSha256.isNullOrBlank()
    LedgerCard(onClick = onEdit) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        CurrencyFormatters.formatMinor(payment.amountMinor, currencyCode),
                        style = MonoStyles.CardAmount,
                        color = sem.positive
                    )
                    if (payment.autoLogged) {
                        StatusPill("AUTO", sem.info, sem.info.copy(alpha = 0.14f))
                    }
                }
                Text(
                    "Paid ${IsoDate.displayFriendly(payment.paidDateIso)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (payment.note.isNotBlank()) {
                    Text(payment.note, style = MaterialTheme.typography.bodyMedium)
                }
                if (hasProof) {
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.Verified,
                            contentDescription = "Proof attached",
                            tint = sem.positive,
                            modifier = Modifier.size(15.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text(
                            "Tap thumbnail to verify SHA-256 proof",
                            style = MonoStyles.BadgeMetric,
                            color = sem.positive
                        )
                    }
                }
            }
            val file = payment.proofLocalUri?.let { File(it) }?.takeIf { it.exists() }
            if (file != null) {
                Spacer(Modifier.width(8.dp))
                AsyncImage(
                    model = file,
                    contentDescription = "Proof of payment thumbnail",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(60.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { onInspectProof() }
                        .testTag("payment_proof_thumbnail_${payment.id}")
                )
            }
            IconButton(
                onClick = onEdit,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("edit_payment_button_${payment.id}")
            ) {
                Icon(
                    Icons.Outlined.Edit,
                    contentDescription = "Edit repayment",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditInstallmentSheet(
    installment: InstallmentEntity,
    currencyCode: String,
    onDismiss: () -> Unit,
    onSave: (
        dueDateIso: String,
        principalMinor: Long,
        interestMinor: Long,
        lateFeeMinor: Long,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit
) {
    var dueDateIso by remember(installment.id) { mutableStateOf(installment.dueDateIso) }
    var principalText by remember(installment.id) {
        mutableStateOf(CurrencyFormatters.minorToEditableMajor(installment.principalDueMinor, currencyCode))
    }
    var interestText by remember(installment.id) {
        mutableStateOf(CurrencyFormatters.minorToEditableMajor(installment.interestDueMinor, currencyCode))
    }
    var lateFeeText by remember(installment.id) {
        mutableStateOf(CurrencyFormatters.minorToEditableMajor(installment.lateFeeDueMinor, currencyCode))
    }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Edit Installment #${installment.installmentNumber}",
                style = MaterialTheme.typography.titleLarge
            )
            IsoDatePickerField(
                label = "Due Date",
                selectedDateIso = dueDateIso,
                onDateSelected = {
                    dueDateIso = it
                    errorText = null
                },
                enabled = !isSaving,
                testTag = "installment_due_date_picker"
            )
            OutlinedTextField(
                value = principalText,
                onValueChange = {
                    principalText = it
                    errorText = null
                },
                enabled = !isSaving,
                label = { Text("Principal Due ($currencyCode)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("installment_principal_input")
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = interestText,
                    onValueChange = {
                        interestText = it
                        errorText = null
                    },
                    enabled = !isSaving,
                    label = { Text("Interest ($currencyCode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("installment_interest_input")
                )
                OutlinedTextField(
                    value = lateFeeText,
                    onValueChange = {
                        lateFeeText = it
                        errorText = null
                    },
                    enabled = !isSaving,
                    label = { Text("Late Fee ($currencyCode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("installment_late_fee_input")
                )
            }

            if (errorText != null) {
                Text(errorText!!, color = LocalSemanticColors.current.danger, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    errorText = null
                    val principalMinor = CurrencyFormatters.parseMajorToMinor(principalText, currencyCode)
                    val interestMinor = CurrencyFormatters.parseMajorToMinor(interestText, currencyCode)
                    val lateFeeMinor = CurrencyFormatters.parseMajorToMinor(lateFeeText, currencyCode)
                    when {
                        !IsoDate.isValid(dueDateIso) -> errorText = "Please select a valid due date."
                        principalMinor == null || principalMinor < 0L -> errorText = "Enter a valid principal amount."
                        interestMinor == null || interestMinor < 0L -> errorText = "Enter a valid interest amount."
                        lateFeeMinor == null || lateFeeMinor < 0L -> errorText = "Enter a valid late fee amount."
                        (principalMinor + interestMinor + lateFeeMinor) <= 0L -> errorText = "Total scheduled amount must be positive."
                        else -> {
                            isSaving = true
                            onSave(
                                dueDateIso,
                                principalMinor,
                                interestMinor,
                                lateFeeMinor,
                                { isSaving = false },
                                { msg ->
                                    isSaving = false
                                    errorText = msg
                                }
                            )
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_installment_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving...")
                } else {
                    Text("Save Schedule Changes")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecordPaymentSheet(
    currencyCode: String,
    defaultAmountMinor: Long,
    editingPayment: PaymentTransactionEntity? = null,
    onDismiss: () -> Unit,
    onSubmit: (
        amountMinor: Long,
        paidDateIso: String,
        note: String,
        proofUri: Uri?,
        removeProof: Boolean,
        onDone: () -> Unit,
        onError: (String) -> Unit
    ) -> Unit,
    onDelete: ((onDone: () -> Unit, onError: (String) -> Unit) -> Unit)? = null
) {
    val isEditing = editingPayment != null
    var amountText by remember(editingPayment?.id) {
        mutableStateOf(
            when {
                editingPayment != null -> CurrencyFormatters.minorToEditableMajor(editingPayment.amountMinor, currencyCode)
                defaultAmountMinor > 0L -> CurrencyFormatters.minorToEditableMajor(defaultAmountMinor, currencyCode)
                else -> ""
            }
        )
    }
    var paidDateIso by remember(editingPayment?.id) {
        mutableStateOf(editingPayment?.paidDateIso ?: IsoDate.todayIso())
    }
    var note by remember(editingPayment?.id) {
        mutableStateOf(editingPayment?.note ?: "")
    }
    var selectedProofUri by remember(editingPayment?.id) { mutableStateOf<Uri?>(null) }
    var removeExistingProof by remember(editingPayment?.id) { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var isSaving by remember { mutableStateOf(false) }

    val hasExistingProof = editingPayment != null &&
        (!editingPayment.proofLocalUri.isNullOrBlank() || !editingPayment.proofSha256.isNullOrBlank()) &&
        !removeExistingProof &&
        selectedProofUri == null

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Zero-permission Android Photo Picker (Play-policy compliant)
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                selectedProofUri = uri
                removeExistingProof = false
            }
        }
    )

    ModalBottomSheet(
        onDismissRequest = { if (!isSaving) onDismiss() },
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (isEditing) "Edit Repayment" else "Log Repayment",
                style = MaterialTheme.typography.titleLarge
            )
            OutlinedTextField(
                value = amountText,
                onValueChange = {
                    amountText = it
                    errorText = null
                },
                enabled = !isSaving,
                label = { Text("Amount ($currencyCode)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("payment_amount_input")
            )
            IsoDatePickerField(
                label = "Payment Date",
                selectedDateIso = paidDateIso,
                onDateSelected = {
                    paidDateIso = it
                    errorText = null
                },
                enabled = !isSaving,
                testTag = "payment_date_picker_field"
            )
            OutlinedTextField(
                value = note,
                onValueChange = { note = it },
                enabled = !isSaving,
                label = { Text("Note / Reference (Optional)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            FilledTonalButton(
                onClick = {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("attach_proof_button")
            ) {
                Icon(Icons.Outlined.AttachFile, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        selectedProofUri != null -> "New Proof Selected (Compressed + SHA-256 on Save)"
                        hasExistingProof -> "Replace Proof of Payment (Photo Picker)"
                        else -> "Attach Proof of Payment (Photo Picker)"
                    }
                )
            }

            if (hasExistingProof) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Existing verified proof attached",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalSemanticColors.current.positive
                    )
                    TextButton(
                        onClick = { removeExistingProof = true },
                        enabled = !isSaving,
                        modifier = Modifier.testTag("remove_existing_proof_button")
                    ) {
                        Text("Remove Proof", color = LocalSemanticColors.current.danger)
                    }
                }
            }

            if (errorText != null) {
                Text(errorText!!, color = LocalSemanticColors.current.danger, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    errorText = null
                    val minor = CurrencyFormatters.parseMajorToMinor(amountText, currencyCode)
                    when {
                        minor == null || minor <= 0L -> errorText = "Enter a valid positive amount."
                        !IsoDate.isValid(paidDateIso) -> errorText = "Please select a valid payment date."
                        else -> {
                            isSaving = true
                            onSubmit(
                                minor,
                                paidDateIso,
                                note,
                                selectedProofUri,
                                removeExistingProof,
                                { isSaving = false },
                                { msg ->
                                    isSaving = false
                                    errorText = msg
                                }
                            )
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("submit_payment_button")
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving...")
                } else {
                    Text(if (isEditing) "Save Repayment Changes" else "Save Verified Payment")
                }
            }

            if (isEditing && onDelete != null) {
                OutlinedButton(
                    onClick = { showDeleteConfirm = true },
                    enabled = !isSaving,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("delete_payment_button")
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline,
                        contentDescription = null,
                        tint = LocalSemanticColors.current.danger
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Delete Repayment", color = LocalSemanticColors.current.danger)
                }
            }
        }
    }

    if (showDeleteConfirm && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Repayment?") },
            text = {
                Text("Deleting this repayment will recalculate the contract's installment balances and status.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDeleteConfirm = false
                        isSaving = true
                        onDelete(
                            { isSaving = false },
                            { msg ->
                                isSaving = false
                                errorText = msg
                            }
                        )
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalSemanticColors.current.danger,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_payment_button")
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

