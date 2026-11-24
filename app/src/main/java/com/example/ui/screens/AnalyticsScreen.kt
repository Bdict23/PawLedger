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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.domain.AnalyticsSortOption
import com.example.domain.ContractStatus
import com.example.domain.CurrencyFormatters
import com.example.domain.DashboardDirectionFilter
import com.example.ui.CounterpartyScorecard
import com.example.ui.PawLedgerViewModel
import com.example.ui.components.ContractStatusBadge
import com.example.ui.components.LedgerCard
import com.example.ui.components.MascotEmptyState
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MonoStyles
import java.util.Locale

@Composable
fun AnalyticsScreen(
    viewModel: PawLedgerViewModel,
    onOpenContract: (String) -> Unit
) {
    val scorecards by viewModel.filteredScorecards.collectAsStateWithLifecycle()
    val query by viewModel.analyticsQuery.collectAsStateWithLifecycle()
    val direction by viewModel.analyticsDirection.collectAsStateWithLifecycle()
    val status by viewModel.analyticsStatus.collectAsStateWithLifecycle()
    val sortOption by viewModel.analyticsSort.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Borrower & Lender Analytics", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "Filter individual performance, return rates, and on-time repayment reliability.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { viewModel.setAnalyticsQuery(it) },
                placeholder = { Text("Search counterparty or contract...") },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("analytics_search_input")
            )
        }

        item {
            Text("Filter by Direction & Status", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(DashboardDirectionFilter.entries) { dir ->
                    FilterChip(
                        selected = direction == dir,
                        onClick = { viewModel.setAnalyticsDirection(dir) },
                        label = { Text(dir.label) }
                    )
                }
                items(ContractStatus.entries) { st ->
                    FilterChip(
                        selected = status == st,
                        onClick = { viewModel.setAnalyticsStatus(if (status == st) null else st) },
                        label = { Text(st.label) }
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Sort Scorecards", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(6.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(AnalyticsSortOption.entries) { opt ->
                    FilterChip(
                        selected = sortOption == opt,
                        onClick = { viewModel.setAnalyticsSort(opt) },
                        label = { Text(opt.label) }
                    )
                }
            }
        }

        if (scorecards.isEmpty()) {
            item {
                MascotEmptyState(
                    title = "No matching counterparty analytics",
                    subtitle = "Create contracts with contacts or clear your active filters to see performance scorecards."
                )
            }
        } else {
            items(scorecards, key = { it.counterparty.id }) { card ->
                ScorecardItem(card = card, onOpenContract = onOpenContract)
            }
        }
    }
}

@Composable
private fun ScorecardItem(
    card: CounterpartyScorecard,
    onOpenContract: (String) -> Unit
) {
    val sem = LocalSemanticColors.current
    var expanded by remember { mutableStateOf(false) }

    val scoreColor = when {
        card.onTimeRatePercent >= 90.0 -> sem.positive
        card.onTimeRatePercent >= 65.0 -> sem.warning
        else -> sem.danger
    }

    LedgerCard(onClick = { expanded = !expanded }) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(card.counterparty.name, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${card.totalContracts} contract${if (card.totalContracts != 1) "s" else ""} • ${card.overdueContracts} overdue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusPill(
                label = "${String.format(Locale.US, "%.0f", card.onTimeRatePercent)}% On-Time",
                textColor = scoreColor,
                containerColor = scoreColor.copy(alpha = 0.14f)
            )
        }

        Spacer(Modifier.height(12.dp))
        LinearProgressIndicator(
            progress = { (card.returnRatePercent / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp),
            color = scoreColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )
        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MetricColumn("Return Rate", "${String.format(Locale.US, "%.1f", card.returnRatePercent)}%")
            MetricColumn("Repaid", CurrencyFormatters.formatMinor(card.totalRepaidMinor, card.primaryCurrency))
            MetricColumn("Outstanding", CurrencyFormatters.formatMinor(card.outstandingMinor, card.primaryCurrency))
            MetricColumn("Avg Late", "${String.format(Locale.US, "%.1f", card.avgDaysLate)}d")
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide Contracts" else "Inspect Contracts (${card.contracts.size})")
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = null
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                card.contracts.forEach { c ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Remaining: ${CurrencyFormatters.formatMinor(c.remainingMinor, c.currencyCode)}",
                                style = MonoStyles.BadgeMetric,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        ContractStatusBadge(c.status)
                        TextButton(onClick = { onOpenContract(c.id) }) {
                            Text("Open")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricColumn(label: String, value: String) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(value, style = MonoStyles.RowAmount)
    }
}
