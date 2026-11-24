package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.UpcomingInstallmentRow
import com.example.domain.CurrencyFormatters
import com.example.domain.DashboardDirectionFilter
import com.example.domain.InstallmentStatus
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import com.example.ui.CurrencyPortfolioSummary
import com.example.ui.PawLedgerViewModel
import com.example.ui.SyncState
import com.example.ui.components.DirectionPill
import com.example.ui.components.EncryptionIndicatorBadge
import com.example.ui.components.LedgerCard
import com.example.ui.components.MascotAvatar
import com.example.ui.components.MascotEmptyState
import com.example.ui.components.ReminderChannelSheet
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MonoStyles
import java.util.Locale

@Composable
fun DashboardScreen(
    viewModel: PawLedgerViewModel,
    onCreateContract: () -> Unit,
    onOpenContract: (String) -> Unit
) {
    val summaries by viewModel.portfolioSummaries.collectAsStateWithLifecycle()
    val upcoming by viewModel.upcomingInstallments.collectAsStateWithLifecycle()
    val counterparties by viewModel.counterparties.collectAsStateWithLifecycle()
    val directionFilter by viewModel.dashboardDirection.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val e2eUnlocked by viewModel.e2eUnlocked.collectAsStateWithLifecycle()

    var reminderTarget by remember { mutableStateOf<UpcomingInstallmentRow?>(null) }

    val filteredUpcoming = remember(upcoming, directionFilter) {
        when (directionFilter) {
            DashboardDirectionFilter.ALL -> upcoming
            DashboardDirectionFilter.LENT -> upcoming.filter { it.direction == LoanDirection.LENT.name }
            DashboardDirectionFilter.BORROWED -> upcoming.filter { it.direction == LoanDirection.BORROWED.name }
        }
    }

    val overdueRows = remember(filteredUpcoming) {
        filteredUpcoming.filter { it.status == InstallmentStatus.OVERDUE.name }
    }

    val sem = LocalSemanticColors.current

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateContract,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.testTag("dashboard_create_contract_fab")
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "Create loan contract")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header Brand Bar with Cartoon Dog Mascot
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MascotAvatar(size = 46.dp)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("PawLedger", style = MaterialTheme.typography.titleLarge)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                SyncStatusChip(syncState = syncState, onSyncClick = { viewModel.syncNow() })
                                EncryptionIndicatorBadge(prefs.e2eEnabled, e2eUnlocked)
                            }
                        }
                    }
                    IconButton(
                        onClick = { viewModel.runRecurringCheckNow() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("run_recurring_check_button")
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Autorenew,
                            contentDescription = "Run recurring auto-log & deadline check",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Overdue Alert Banner
            item {
                AnimatedVisibility(visible = overdueRows.isNotEmpty()) {
                    val firstOverdue = overdueRows.firstOrNull()
                    if (firstOverdue != null) {
                        LedgerCard(
                            containerColor = sem.dangerContainer,
                            borderColor = sem.danger.copy(alpha = 0.5f)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Rounded.WarningAmber,
                                    contentDescription = "Overdue alert",
                                    tint = sem.danger
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${overdueRows.size} Overdue Alert${if (overdueRows.size > 1) "s" else ""}",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = sem.danger
                                    )
                                    Text(
                                        text = "${firstOverdue.counterpartyName} • ${firstOverdue.contractTitle} (${CurrencyFormatters.formatMinor(firstOverdue.outstandingMinor, firstOverdue.currencyCode)})",
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilledTonalButton(
                                    onClick = { reminderTarget = firstOverdue },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("overdue_send_reminder_button")
                                ) {
                                    Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Remind")
                                }
                                OutlinedButton(
                                    onClick = { onOpenContract(firstOverdue.contractId) },
                                    modifier = Modifier
                                        .height(48.dp)
                                        .testTag("overdue_open_contract_button")
                                ) {
                                    Text("Log Payment")
                                }
                            }
                        }
                    }
                }
            }

            // Direction Toggle Chips
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DashboardDirectionFilter.entries.forEach { filter ->
                        FilterChip(
                            selected = directionFilter == filter,
                            onClick = { viewModel.setDashboardDirection(filter) },
                            label = { Text(filter.label) },
                            modifier = Modifier.testTag("dashboard_dir_${filter.name.lowercase()}")
                        )
                    }
                }
            }

            // Portfolio Summary Cards (grouped safely by currency)
            if (summaries.isEmpty()) {
                item {
                    MascotEmptyState(
                        title = "Your ledger is spotless",
                        subtitle = "Create your first loan contract (lent or borrowed) to generate a repayment schedule and track return rates.",
                        actionLabel = "Create Loan Contract",
                        onAction = onCreateContract
                    )
                }
            } else {
                items(summaries, key = { it.currencyCode }) { summary ->
                    PortfolioSummaryCard(
                        summary = summary,
                        directionFilter = directionFilter
                    )
                }
            }

            // Upcoming Deadlines Timeline
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Upcoming Deadlines", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${filteredUpcoming.size} open",
                        style = MonoStyles.BadgeMetric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (filteredUpcoming.isEmpty() && summaries.isNotEmpty()) {
                item {
                    LedgerCard {
                        Text(
                            "No upcoming open installments for this filter.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                items(filteredUpcoming.take(15), key = { it.installmentId }) { row ->
                    UpcomingDeadlineCard(
                        row = row,
                        onOpen = { onOpenContract(row.contractId) },
                        onRemind = { reminderTarget = row }
                    )
                }
            }
        }
    }

    reminderTarget?.let { target ->
        val cp = counterparties.firstOrNull { it.id == target.counterpartyId }
        ReminderChannelSheet(
            counterparty = cp,
            contractTitle = target.contractTitle,
            direction = LoanDirection.fromRaw(target.direction),
            amountDueMinor = target.outstandingMinor,
            remainingMinor = target.outstandingMinor,
            currencyCode = target.currencyCode,
            dueDateIso = target.dueDateIso,
            onDismiss = { reminderTarget = null }
        )
    }
}

@Composable
private fun SyncStatusChip(syncState: SyncState, onSyncClick: () -> Unit) {
    val sem = LocalSemanticColors.current
    Surface(
        onClick = onSyncClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            when (syncState) {
                SyncState.Syncing -> {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(5.dp))
                    Text("Syncing", style = MonoStyles.BadgeMetric)
                }
                is SyncState.Synced -> {
                    Icon(Icons.Outlined.CloudDone, contentDescription = "Synced", tint = sem.positive, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Cloud Synced", style = MonoStyles.BadgeMetric, color = sem.positive)
                }
                is SyncState.Error -> {
                    Icon(Icons.Outlined.CloudOff, contentDescription = "Offline", tint = sem.warning, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Offline Ready", style = MonoStyles.BadgeMetric, color = sem.warning)
                }
                SyncState.Idle -> {
                    Icon(Icons.Outlined.Sync, contentDescription = "Sync", modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Sync", style = MonoStyles.BadgeMetric)
                }
            }
        }
    }
}

@Composable
private fun PortfolioSummaryCard(
    summary: CurrencyPortfolioSummary,
    directionFilter: DashboardDirectionFilter
) {
    val sem = LocalSemanticColors.current
    val cur = summary.currencyCode
    val showLent = directionFilter != DashboardDirectionFilter.BORROWED
    val showBorrowed = directionFilter != DashboardDirectionFilter.LENT

    LedgerCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "$cur Portfolio",
                style = MaterialTheme.typography.titleMedium
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatusPill(
                    label = "${summary.activeContractsCount} Active",
                    textColor = sem.info,
                    containerColor = sem.info.copy(alpha = 0.14f)
                )
                if (summary.overdueContractsCount > 0) {
                    StatusPill(
                        label = "${summary.overdueContractsCount} Overdue",
                        textColor = sem.danger,
                        containerColor = sem.dangerContainer
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (showLent) {
            Text(
                "OWED TO ME (LENT)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    CurrencyFormatters.formatMinor(summary.lentOutstandingMinor, cur),
                    style = MonoStyles.HeroAmount,
                    color = sem.positive
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        "Return ${String.format(Locale.US, "%.1f", summary.returnRatePercent)}%",
                        style = MonoStyles.BadgeMetric,
                        color = sem.positive
                    )
                    Text(
                        "Repaid ${CurrencyFormatters.formatMinor(summary.lentRepaidMinor, cur)}",
                        style = MonoStyles.BadgeMetric,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            val progress = if (summary.lentScheduledMinor > 0L) {
                (summary.lentRepaidMinor.toFloat() / summary.lentScheduledMinor.toFloat()).coerceIn(0f, 1f)
            } else 0f
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
                color = sem.positive,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }

        if (showLent && showBorrowed) {
            Spacer(Modifier.height(14.dp))
        }

        if (showBorrowed) {
            Text(
                "I OWE (BORROWED)",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    CurrencyFormatters.formatMinor(summary.borrowedOutstandingMinor, cur),
                    style = MonoStyles.CardAmount,
                    color = sem.warning
                )
                Text(
                    "Paid ${CurrencyFormatters.formatMinor(summary.borrowedRepaidMinor, cur)}",
                    style = MonoStyles.BadgeMetric,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "On-Time Completion",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${String.format(Locale.US, "%.0f", summary.onTimeRatePercent)}%",
                style = MonoStyles.RowAmount,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

@Composable
private fun UpcomingDeadlineCard(
    row: UpcomingInstallmentRow,
    onOpen: () -> Unit,
    onRemind: () -> Unit
) {
    val sem = LocalSemanticColors.current
    val today = IsoDate.todayIso()
    val daysDelta = IsoDate.daysBetween(today, row.dueDateIso)
    val isOverdue = row.status == InstallmentStatus.OVERDUE.name || daysDelta < 0
    val dueTagText = when {
        daysDelta < 0 -> "${-daysDelta}d overdue"
        daysDelta == 0L -> "Due today"
        daysDelta <= 3L -> "In ${daysDelta}d"
        else -> IsoDate.displayFriendly(row.dueDateIso)
    }

    LedgerCard(onClick = onOpen) {
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
                    DirectionPill(row.direction)
                    if (row.autoLogRecurring) {
                        StatusPill("AUTO", sem.info, sem.info.copy(alpha = 0.14f))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    row.contractTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${row.counterpartyName} • #${row.installmentNumber}/${row.totalTerms}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    CurrencyFormatters.formatMinor(row.outstandingMinor, row.currencyCode),
                    style = MonoStyles.CardAmount,
                    color = if (isOverdue) sem.danger else MaterialTheme.colorScheme.onSurface
                )
                Spacer(Modifier.height(4.dp))
                StatusPill(
                    label = dueTagText,
                    textColor = if (isOverdue) sem.danger else sem.warning,
                    containerColor = if (isOverdue) sem.dangerContainer else sem.warningContainer
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(
                onClick = onRemind,
                modifier = Modifier.height(48.dp)
            ) {
                Icon(Icons.Outlined.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Send Reminder", fontWeight = FontWeight.Medium)
            }
        }
    }
}
