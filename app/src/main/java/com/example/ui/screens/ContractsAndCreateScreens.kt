package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PersonAdd
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.ContractSummaryRow
import com.example.domain.ContractStatus
import com.example.domain.CurrencyFormatters
import com.example.domain.DashboardDirectionFilter
import com.example.domain.FinancialCalculator
import com.example.domain.InterestMode
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import com.example.domain.PaymentAllocationOrder
import com.example.domain.RepaymentFrequency
import com.example.ui.PawLedgerViewModel
import com.example.ui.components.ContractStatusBadge
import com.example.ui.components.DirectionPill
import com.example.ui.components.IsoDatePickerField
import com.example.ui.components.LedgerCard
import com.example.ui.components.MascotEmptyState
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MonoStyles

@Composable
fun ContractsScreen(
    viewModel: PawLedgerViewModel,
    onCreateContract: () -> Unit,
    onOpenContract: (String) -> Unit,
    onEditContract: (String) -> Unit = {}
) {
    val contracts by viewModel.contracts.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    var dirFilter by remember { mutableStateOf(DashboardDirectionFilter.ALL) }
    var statusFilter by remember { mutableStateOf<ContractStatus?>(null) }
    var pendingDeleteContract by remember { mutableStateOf<ContractSummaryRow?>(null) }

    val filtered = remember(contracts, query, dirFilter, statusFilter) {
        contracts.filter { c ->
            val matchQuery = query.isBlank() ||
                c.title.contains(query, ignoreCase = true) ||
                c.counterpartyName.contains(query, ignoreCase = true)
            val matchDir = when (dirFilter) {
                DashboardDirectionFilter.ALL -> true
                DashboardDirectionFilter.LENT -> c.direction == LoanDirection.LENT.name
                DashboardDirectionFilter.BORROWED -> c.direction == LoanDirection.BORROWED.name
            }
            val matchStatus = statusFilter == null || c.status == statusFilter!!.name
            matchQuery && matchDir && matchStatus
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateContract,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("contracts_create_fab")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Create new loan contract")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Loan Contracts", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search contract or counterparty...") },
                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("contracts_search_input")
                )
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(DashboardDirectionFilter.entries) { d ->
                        FilterChip(
                            selected = dirFilter == d,
                            onClick = { dirFilter = d },
                            label = { Text(d.label) }
                        )
                    }
                    items(ContractStatus.entries) { st ->
                        FilterChip(
                            selected = statusFilter == st,
                            onClick = { statusFilter = if (statusFilter == st) null else st },
                            label = { Text(st.label) }
                        )
                    }
                }
            }

            if (filtered.isEmpty()) {
                item {
                    MascotEmptyState(
                        title = "No matching contracts",
                        subtitle = "Adjust filters or create a new loan contract to generate an automated repayment schedule.",
                        actionLabel = "New Contract",
                        onAction = onCreateContract
                    )
                }
            } else {
                items(filtered, key = { it.id }) { row ->
                    ContractListCard(
                        row = row,
                        onClick = { onOpenContract(row.id) },
                        onEdit = { onEditContract(row.id) },
                        onRequestDelete = { pendingDeleteContract = row }
                    )
                }
            }
        }
    }

    pendingDeleteContract?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDeleteContract = null },
            title = { Text("Remove Contract?") },
            text = {
                Text("This permanently deletes \"${target.title}\", its repayment schedule, and all recorded payment history.")
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteContract(target.id)
                        pendingDeleteContract = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LocalSemanticColors.current.danger,
                        contentColor = MaterialTheme.colorScheme.onError
                    ),
                    modifier = Modifier.testTag("confirm_delete_contract_button")
                ) {
                    Text("Remove")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteContract = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ContractListCard(
    row: ContractSummaryRow,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onRequestDelete: () -> Unit
) {
    val sem = LocalSemanticColors.current
    var menuExpanded by remember { mutableStateOf(false) }
    val progress = if (row.totalScheduledMinor > 0L) {
        (row.totalRepaidMinor.toFloat() / row.totalScheduledMinor.toFloat()).coerceIn(0f, 1f)
    } else 0f

    LedgerCard(onClick = onClick) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DirectionPill(row.direction)
                ContractStatusBadge(row.status)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (row.autoLogRecurring) {
                    StatusPill("AUTO-LOG", sem.info, sem.info.copy(alpha = 0.14f))
                }
                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("contract_menu_${row.id}")
                    ) {
                        Icon(
                            Icons.Outlined.MoreVert,
                            contentDescription = "Contract actions",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Edit Contract") },
                            leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onEdit()
                            },
                            modifier = Modifier.testTag("contract_menu_edit_${row.id}")
                        )
                        DropdownMenuItem(
                            text = { Text("Remove Contract", color = sem.danger) },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.DeleteOutline,
                                    contentDescription = null,
                                    tint = sem.danger
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onRequestDelete()
                            },
                            modifier = Modifier.testTag("contract_menu_delete_${row.id}")
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            row.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            "${row.counterpartyName} • ${InterestMode.fromRaw(row.interestMode).label}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Remaining", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    CurrencyFormatters.formatMinor(row.remainingMinor, row.currencyCode),
                    style = MonoStyles.CardAmount
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Total Scheduled", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    CurrencyFormatters.formatMinor(row.totalScheduledMinor, row.currencyCode),
                    style = MonoStyles.RowAmount,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = if (row.status == ContractStatus.OVERDUE.name) sem.danger else sem.positive,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        row.nextDueDateIso?.let { nextDue ->
            Spacer(Modifier.height(6.dp))
            Text(
                "Next due: ${IsoDate.displayFriendly(nextDue)} (${CurrencyFormatters.formatMinor(row.nextDueAmountMinor, row.currencyCode)})",
                style = MonoStyles.BadgeMetric,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CreateContractScreen(
    viewModel: PawLedgerViewModel,
    onBack: () -> Unit,
    onContractCreated: (String) -> Unit,
    editContractId: String? = null
) {
    val policy by viewModel.adminPolicy.collectAsStateWithLifecycle()
    val counterparties by viewModel.counterparties.collectAsStateWithLifecycle()
    val contracts by viewModel.contracts.collectAsStateWithLifecycle()
    val existingContract = remember(contracts, editContractId) {
        editContractId?.let { id -> contracts.firstOrNull { it.id == id } }
    }
    val isEditing = editContractId != null

    var direction by remember { mutableStateOf(LoanDirection.LENT) }
    var title by remember { mutableStateOf("") }
    var selectedCounterpartyId by remember { mutableStateOf("") }
    var principalText by remember { mutableStateOf("500") }
    var currencyCode by remember { mutableStateOf("USD") }
    var startDateIso by remember { mutableStateOf(IsoDate.todayIso()) }
    var termText by remember { mutableStateOf("6") }
    var frequency by remember { mutableStateOf(RepaymentFrequency.MONTHLY) }
    var interestMode by remember { mutableStateOf(InterestMode.SIMPLE_FLAT) }
    var ratePercentText by remember { mutableStateOf("5.0") }
    var graceDaysText by remember { mutableStateOf("3") }
    var lateFeePercentText by remember { mutableStateOf("2.0") }
    var lateFeeCapText by remember { mutableStateOf("25") }
    var allocationOrder by remember { mutableStateOf(PaymentAllocationOrder.FEES_INTEREST_PRINCIPAL) }
    var autoLogRecurring by remember { mutableStateOf(false) }
    var notes by remember { mutableStateOf("") }
    var initializedFromPolicy by remember { mutableStateOf(false) }
    var initializedFromContract by remember { mutableStateOf(false) }
    var showAddCounterpartyDialog by remember { mutableStateOf(false) }
    var validationError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(existingContract) {
        val c = existingContract ?: return@LaunchedEffect
        if (!initializedFromContract) {
            direction = LoanDirection.fromRaw(c.direction)
            title = c.title
            selectedCounterpartyId = c.counterpartyId
            currencyCode = c.currencyCode
            principalText = CurrencyFormatters.minorToEditableMajor(c.principalMinor, c.currencyCode)
            startDateIso = c.startDateIso
            termText = c.termCount.toString()
            frequency = RepaymentFrequency.fromRaw(c.frequency)
            interestMode = InterestMode.fromRaw(c.interestMode)
            ratePercentText = (c.interestRateBps / 100.0).toString()
            graceDaysText = c.graceDays.toString()
            lateFeePercentText = (c.lateFeeBps / 100.0).toString()
            lateFeeCapText = CurrencyFormatters.minorToEditableMajor(c.lateFeeCapMinor, c.currencyCode)
            allocationOrder = PaymentAllocationOrder.fromRaw(c.paymentAllocationOrder)
            autoLogRecurring = c.autoLogRecurring
            notes = c.notes
            initializedFromContract = true
        }
    }

    LaunchedEffect(policy, isEditing) {
        if (isEditing) return@LaunchedEffect
        val p = policy ?: return@LaunchedEffect
        if (!initializedFromPolicy) {
            currencyCode = p.defaultCurrencyCode
            interestMode = InterestMode.fromRaw(p.defaultInterestMode)
            ratePercentText = (p.defaultRateBps / 100.0).toString()
            graceDaysText = p.defaultGraceDays.toString()
            lateFeePercentText = (p.defaultLateFeeBps / 100.0).toString()
            lateFeeCapText = CurrencyFormatters.minorToEditableMajor(p.defaultLateFeeCapMinor, p.defaultCurrencyCode)
            allocationOrder = PaymentAllocationOrder.fromRaw(p.paymentAllocationOrder)
            autoLogRecurring = p.defaultAutoLogRecurring
            initializedFromPolicy = true
        }
    }

    LaunchedEffect(counterparties, isEditing) {
        if (!isEditing && selectedCounterpartyId.isBlank() && counterparties.isNotEmpty()) {
            selectedCounterpartyId = counterparties.first().id
        }
    }

    val allowedModes = remember(policy) {
        val p = policy
        if (p == null) InterestMode.entries
        else buildList {
            if (p.allowZeroInterest) add(InterestMode.ZERO_INTEREST)
            if (p.allowSimpleFlat) add(InterestMode.SIMPLE_FLAT)
            if (p.allowAmortizedMonthly) add(InterestMode.AMORTIZED_MONTHLY)
        }.ifEmpty { listOf(InterestMode.ZERO_INTEREST) }
    }

    LaunchedEffect(allowedModes) {
        if (interestMode !in allowedModes) {
            interestMode = allowedModes.first()
        }
    }

    val principalMinor = CurrencyFormatters.parseMajorToMinor(principalText, currencyCode) ?: 0L
    val termCount = termText.toIntOrNull()?.coerceIn(1, 360) ?: 1
    val rateBps = ((ratePercentText.toDoubleOrNull() ?: 0.0) * 100).toInt().coerceIn(0, 100000)
    val graceDays = graceDaysText.toIntOrNull()?.coerceIn(0, 365) ?: 0
    val lateFeeBps = ((lateFeePercentText.toDoubleOrNull() ?: 0.0) * 100).toInt().coerceIn(0, 50000)
    val lateFeeCapMinor = CurrencyFormatters.parseMajorToMinor(lateFeeCapText, currencyCode) ?: 0L

    val previewSchedule = remember(principalMinor, startDateIso, termCount, frequency, interestMode, rateBps) {
        if (principalMinor > 0L && IsoDate.isValid(startDateIso)) {
            FinancialCalculator.generateSchedule(
                principalMinor = principalMinor,
                startDateIso = startDateIso,
                termCount = termCount,
                frequency = frequency,
                interestMode = interestMode,
                interestRateBps = if (interestMode == InterestMode.ZERO_INTEREST) 0 else rateBps
            )
        } else emptyList()
    }

    val sem = LocalSemanticColors.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isEditing) "Edit Loan Contract" else "New Loan Contract") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Direction Selector
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LoanDirection.entries.forEach { d ->
                    FilterChip(
                        selected = direction == d,
                        onClick = { direction = d },
                        label = { Text(d.label) },
                        modifier = Modifier.testTag("create_dir_${d.name.lowercase()}")
                    )
                }
            }

            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Contract Title") },
                placeholder = { Text("e.g., Camera Equipment Loan") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create_contract_title_input")
            )

            // Counterparty Picker
            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Counterparty", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = { showAddCounterpartyDialog = true },
                        modifier = Modifier
                            .height(48.dp)
                            .testTag("add_counterparty_button")
                    ) {
                        Icon(Icons.Outlined.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New Contact")
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (counterparties.isEmpty()) {
                    Text(
                        "Tap \"New Contact\" to add the borrower or lender.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(counterparties, key = { it.id }) { cp ->
                            FilterChip(
                                selected = selectedCounterpartyId == cp.id,
                                onClick = { selectedCounterpartyId = cp.id },
                                label = { Text(cp.name) }
                            )
                        }
                    }
                }
            }

            // Principal & Currency
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = principalText,
                    onValueChange = { principalText = it },
                    label = { Text("Principal ($currencyCode)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1.4f)
                        .testTag("create_principal_input")
                )
                OutlinedTextField(
                    value = termText,
                    onValueChange = { termText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Installments") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("create_term_input")
                )
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(CurrencyFormatters.supportedCurrencies) { code ->
                    FilterChip(
                        selected = currencyCode == code,
                        onClick = { currencyCode = code },
                        label = { Text(code) }
                    )
                }
            }

            IsoDatePickerField(
                label = "Start Date",
                selectedDateIso = startDateIso,
                onDateSelected = { startDateIso = it },
                testTag = "create_start_date_input"
            )

            Text("Repayment Frequency", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RepaymentFrequency.entries.forEach { freq ->
                    FilterChip(
                        selected = frequency == freq,
                        onClick = { frequency = freq },
                        label = { Text(freq.label) },
                        modifier = Modifier.testTag("freq_${freq.name.lowercase()}")
                    )
                }
            }

            // Super Admin Policy Snapshot
            LedgerCard {
                Text("Super Admin Policy Rules", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Interest modes and defaults are governed by Super Admin Console.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(allowedModes) { mode ->
                        FilterChip(
                            selected = interestMode == mode,
                            onClick = { interestMode = mode },
                            label = { Text(mode.label) }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (interestMode != InterestMode.ZERO_INTEREST) {
                        OutlinedTextField(
                            value = ratePercentText,
                            onValueChange = { ratePercentText = it },
                            label = { Text("Annual Rate %") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    OutlinedTextField(
                        value = graceDaysText,
                        onValueChange = { graceDaysText = it.filter { ch -> ch.isDigit() } },
                        label = { Text("Grace Days") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Automated Recurring Logging", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Idempotently log due installments via background worker",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = autoLogRecurring,
                        onCheckedChange = { autoLogRecurring = it },
                        modifier = Modifier.testTag("create_autolog_switch")
                    )
                }
            }

            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("Contract Notes (Optional)") },
                minLines = 2,
                maxLines = 4,
                modifier = Modifier.fillMaxWidth()
            )

            // Live Auto-Generated Repayment Schedule Preview
            if (previewSchedule.isNotEmpty()) {
                val totalScheduled = previewSchedule.sumOf { it.totalDueMinor }
                val totalInterest = previewSchedule.sumOf { it.interestDueMinor }
                LedgerCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Schedule Preview", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Total: ${CurrencyFormatters.formatMinor(totalScheduled, currencyCode)}",
                            style = MonoStyles.RowAmount,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Text(
                        "Principal + ${CurrencyFormatters.formatMinor(totalInterest, currencyCode)} interest",
                        style = MonoStyles.BadgeMetric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    previewSchedule.take(6).forEach { inst ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                "#${inst.installmentNumber} • ${IsoDate.displayFriendly(inst.dueDateIso)}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                CurrencyFormatters.formatMinor(inst.totalDueMinor, currencyCode),
                                style = MonoStyles.RowAmount
                            )
                        }
                    }
                    if (previewSchedule.size > 6) {
                        Text(
                            "+ ${previewSchedule.size - 6} more installments",
                            style = MonoStyles.BadgeMetric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isEditing && existingContract != null && existingContract.totalRepaidMinor > 0L) {
                LedgerCard {
                    Text(
                        "Existing repayments (${CurrencyFormatters.formatMinor(existingContract.totalRepaidMinor, existingContract.currencyCode)}) will automatically re-allocate across the updated installment schedule.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = sem.info
                    )
                }
            }

            if (validationError != null) {
                Text(validationError!!, color = sem.danger, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = {
                    validationError = null
                    when {
                        title.isBlank() -> validationError = "Please enter a contract title."
                        selectedCounterpartyId.isBlank() -> validationError = "Please add or select a counterparty."
                        principalMinor <= 0L -> validationError = "Enter a valid positive principal amount."
                        !IsoDate.isValid(startDateIso) -> validationError = "Use YYYY-MM-DD format for start date."
                        isEditing -> viewModel.updateContract(
                            contractId = editContractId!!,
                            counterpartyId = selectedCounterpartyId,
                            direction = direction,
                            title = title,
                            principalMinor = principalMinor,
                            currencyCode = currencyCode,
                            startDateIso = startDateIso,
                            termCount = termCount,
                            frequency = frequency,
                            interestMode = interestMode,
                            interestRateBps = if (interestMode == InterestMode.ZERO_INTEREST) 0 else rateBps,
                            graceDays = graceDays,
                            lateFeeBps = lateFeeBps,
                            lateFeeCapMinor = lateFeeCapMinor,
                            allocationOrder = allocationOrder,
                            autoLogRecurring = autoLogRecurring,
                            notes = notes,
                            onSaved = { onContractCreated(editContractId) }
                        )
                        else -> viewModel.createContract(
                            counterpartyId = selectedCounterpartyId,
                            direction = direction,
                            title = title,
                            principalMinor = principalMinor,
                            currencyCode = currencyCode,
                            startDateIso = startDateIso,
                            termCount = termCount,
                            frequency = frequency,
                            interestMode = interestMode,
                            interestRateBps = if (interestMode == InterestMode.ZERO_INTEREST) 0 else rateBps,
                            graceDays = graceDays,
                            lateFeeBps = lateFeeBps,
                            lateFeeCapMinor = lateFeeCapMinor,
                            allocationOrder = allocationOrder,
                            autoLogRecurring = autoLogRecurring,
                            notes = notes,
                            onCreated = onContractCreated
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("save_contract_button")
            ) {
                Text(if (isEditing) "Save Contract Changes" else "Create Contract & Schedule")
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAddCounterpartyDialog) {
        AddCounterpartyDialog(
            onDismiss = { showAddCounterpartyDialog = false },
            onSave = { name, phone, email, wa ->
                viewModel.addCounterparty(name, phone, email, wa, "") { created ->
                    selectedCounterpartyId = created.id
                    showAddCounterpartyDialog = false
                }
            }
        )
    }
}

@Composable
fun AddCounterpartyDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, phone: String, email: String, whatsapp: String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var whatsapp by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Counterparty") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name *") },
                    singleLine = true,
                    modifier = Modifier.testTag("dialog_cp_name_input")
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone (SMS)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                OutlinedTextField(
                    value = whatsapp,
                    onValueChange = { whatsapp = it },
                    label = { Text("WhatsApp Number") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { if (name.isNotBlank()) onSave(name, phone, email, whatsapp) },
                enabled = name.isNotBlank(),
                modifier = Modifier.testTag("dialog_cp_save_button")
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
