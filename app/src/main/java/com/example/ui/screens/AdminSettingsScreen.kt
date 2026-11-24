package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.credentials.CredentialManager
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.domain.CurrencyFormatters
import com.example.domain.InterestMode
import com.example.domain.PaymentAllocationOrder
import com.example.domain.ThemeMode
import com.example.security.BiometricHelper
import com.example.ui.PawLedgerViewModel
import com.example.ui.components.LedgerCard
import com.example.ui.components.StatusPill
import com.example.ui.theme.LocalSemanticColors
import com.example.ui.theme.MonoStyles
import java.io.File

@Composable
fun AdminSettingsScreen(
    viewModel: PawLedgerViewModel,
    userEmail: String?
) {
    val context = LocalContext.current
    val activity = context as? FragmentActivity
    val credentialManager = remember { CredentialManager.create(context) }

    val policy by viewModel.adminPolicy.collectAsStateWithLifecycle()
    val prefs by viewModel.preferences.collectAsStateWithLifecycle()
    val e2eUnlocked by viewModel.e2eUnlocked.collectAsStateWithLifecycle()
    val recoveryLogs by viewModel.recoveryLogs.collectAsStateWithLifecycle()

    var adminConsoleUnlocked by remember { mutableStateOf(false) }
    var showE2EDialog by remember { mutableStateOf(false) }
    var showRecoveryConfirmDialog by remember { mutableStateOf(false) }
    var adminGateError by remember { mutableStateOf<String?>(null) }

    // Policy editor local state synced from Room
    var defaultInterestMode by remember { mutableStateOf(InterestMode.SIMPLE_FLAT) }
    var allowZero by remember { mutableStateOf(true) }
    var allowSimple by remember { mutableStateOf(true) }
    var allowAmortized by remember { mutableStateOf(true) }
    var rateText by remember { mutableStateOf("5.0") }
    var graceText by remember { mutableStateOf("3") }
    var lateFeeRateText by remember { mutableStateOf("2.0") }
    var lateFeeCapText by remember { mutableStateOf("25") }
    var allocationOrder by remember { mutableStateOf(PaymentAllocationOrder.FEES_INTEREST_PRINCIPAL) }
    var defaultCurrency by remember { mutableStateOf("USD") }
    var defaultAutoLog by remember { mutableStateOf(false) }

    LaunchedEffect(policy) {
        val p = policy ?: return@LaunchedEffect
        defaultInterestMode = InterestMode.fromRaw(p.defaultInterestMode)
        allowZero = p.allowZeroInterest
        allowSimple = p.allowSimpleFlat
        allowAmortized = p.allowAmortizedMonthly
        rateText = (p.defaultRateBps / 100.0).toString()
        graceText = p.defaultGraceDays.toString()
        lateFeeRateText = (p.defaultLateFeeBps / 100.0).toString()
        lateFeeCapText = CurrencyFormatters.minorToEditableMajor(p.defaultLateFeeCapMinor, p.defaultCurrencyCode)
        allocationOrder = PaymentAllocationOrder.fromRaw(p.paymentAllocationOrder)
        defaultCurrency = p.defaultCurrencyCode
        defaultAutoLog = p.defaultAutoLogRecurring
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted ->
            viewModel.setNotificationsConfig(granted, prefs.reminderDaysBefore)
        }
    )

    val backupImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) viewModel.restoreFromBackupUri(uri)
        }
    )

    fun shareExportedFile(file: File, mimeType: String) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Export PawLedger Backup"))
    }

    val sem = LocalSemanticColors.current

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Text("Super Admin & Security", style = MaterialTheme.typography.headlineMedium)
            userEmail?.let {
                Text(
                    "Signed in as $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Super Admin Policy Console
        item {
            LedgerCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Outlined.AdminPanelSettings,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("Super Admin Policy Console", style = MaterialTheme.typography.titleMedium)
                    }
                    StatusPill(
                        label = if (adminConsoleUnlocked) "UNLOCKED" else "PROTECTED",
                        textColor = if (adminConsoleUnlocked) sem.positive else sem.warning,
                        containerColor = if (adminConsoleUnlocked) sem.positiveContainer else sem.warningContainer
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (!adminConsoleUnlocked) {
                    Text(
                        "Configure allowed loan interest modes, default rates, late-fee caps, payment allocation, and Account Recovery Transfer.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (adminGateError != null) {
                        Spacer(Modifier.height(6.dp))
                        Text(adminGateError!!, color = sem.danger, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            if (activity != null && BiometricHelper.canAuthenticate(activity)) {
                                BiometricHelper.authenticate(
                                    activity = activity,
                                    title = "Super Admin Access",
                                    subtitle = context.getString(R.string.admin_biometric_subtitle),
                                    onSuccess = {
                                        adminGateError = null
                                        adminConsoleUnlocked = true
                                    },
                                    onError = { msg -> adminGateError = msg }
                                )
                            } else {
                                adminConsoleUnlocked = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("unlock_super_admin_button")
                    ) {
                        Icon(Icons.Outlined.Fingerprint, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Unlock Super Admin Console")
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Allowed Interest Models", style = MaterialTheme.typography.labelLarge)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowZero, onCheckedChange = { allowZero = it })
                            Text("Zero-Interest Personal Loans")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowSimple, onCheckedChange = { allowSimple = it })
                            Text("Simple / Flat-Fee Interest")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = allowAmortized, onCheckedChange = { allowAmortized = it })
                            Text("Amortized Monthly Installments")
                        }

                        Text("Default Interest Mode", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(InterestMode.entries) { mode ->
                                FilterChip(
                                    selected = defaultInterestMode == mode,
                                    onClick = { defaultInterestMode = mode },
                                    label = { Text(mode.label) }
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = rateText,
                                onValueChange = { rateText = it },
                                label = { Text("Default Rate %") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = graceText,
                                onValueChange = { graceText = it.filter { ch -> ch.isDigit() } },
                                label = { Text("Grace Days") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedTextField(
                                value = lateFeeRateText,
                                onValueChange = { lateFeeRateText = it },
                                label = { Text("Late Fee %") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = lateFeeCapText,
                                onValueChange = { lateFeeCapText = it },
                                label = { Text("Late Fee Cap ($defaultCurrency)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Text("Payment Allocation Order", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(PaymentAllocationOrder.entries) { ord ->
                                FilterChip(
                                    selected = allocationOrder == ord,
                                    onClick = { allocationOrder = ord },
                                    label = { Text(ord.label) }
                                )
                            }
                        }

                        Text("Default Currency", style = MaterialTheme.typography.labelLarge)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(CurrencyFormatters.supportedCurrencies) { code ->
                                FilterChip(
                                    selected = defaultCurrency == code,
                                    onClick = { defaultCurrency = code },
                                    label = { Text(code) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Default Auto-Log Recurring", style = MaterialTheme.typography.bodyLarge)
                            Switch(checked = defaultAutoLog, onCheckedChange = { defaultAutoLog = it })
                        }

                        Button(
                            onClick = {
                                val existing = policy ?: return@Button
                                val rateBps = ((rateText.toDoubleOrNull() ?: 0.0) * 100).toInt().coerceIn(0, 100000)
                                val grace = graceText.toIntOrNull()?.coerceIn(0, 365) ?: 3
                                val lfBps = ((lateFeeRateText.toDoubleOrNull() ?: 0.0) * 100).toInt().coerceIn(0, 50000)
                                val lfCap = CurrencyFormatters.parseMajorToMinor(lateFeeCapText, defaultCurrency) ?: 0L
                                viewModel.saveAdminPolicy(
                                    existing.copy(
                                        defaultInterestMode = defaultInterestMode.name,
                                        allowZeroInterest = allowZero || (!allowSimple && !allowAmortized),
                                        allowSimpleFlat = allowSimple,
                                        allowAmortizedMonthly = allowAmortized,
                                        defaultRateBps = rateBps,
                                        defaultGraceDays = grace,
                                        defaultLateFeeBps = lfBps,
                                        defaultLateFeeCapMinor = lfCap,
                                        paymentAllocationOrder = allocationOrder.name,
                                        defaultCurrencyCode = defaultCurrency,
                                        defaultAutoLogRecurring = defaultAutoLog
                                    )
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("save_admin_policy_button")
                        ) {
                            Text("Save Super Admin Policy")
                        }
                    }
                }
            }
        }

        // Super Admin Account Recovery Transfer
        item {
            LedgerCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SwapHoriz, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("Account Recovery Transfer", style = MaterialTheme.typography.titleMedium)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Recover or migrate ledger records to a newly created Gmail account in bounded batches, or export/restore via encrypted JSON bundle.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { showRecoveryConfirmDialog = true },
                    enabled = adminConsoleUnlocked,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("transfer_to_new_gmail_button")
                ) {
                    Text(
                        if (adminConsoleUnlocked) "Transfer Local Ledger to New Gmail"
                        else "Unlock Super Admin Above to Transfer Account"
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { viewModel.exportJsonBackup { shareExportedFile(it, "application/json") } },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("export_json_backup_button")
                    ) {
                        Icon(Icons.Outlined.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("JSON Backup")
                    }
                    OutlinedButton(
                        onClick = { backupImportLauncher.launch(arrayOf("application/json", "*/*")) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("import_json_backup_button")
                    ) {
                        Icon(Icons.Outlined.UploadFile, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Restore Bundle")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { viewModel.exportCsvLedger { shareExportedFile(it, "text/csv") } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("export_csv_button")
                ) {
                    Text("Export Ledger Summary (CSV)")
                }

                if (recoveryLogs.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text("Recent Transfers", style = MaterialTheme.typography.labelLarge)
                    recoveryLogs.take(3).forEach { log ->
                        Text(
                            "${log.transferMode}: ${log.recordsTransferredCount} records • ${log.status}",
                            style = MonoStyles.BadgeMetric,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Security, Encryption & Biometrics
        item {
            LedgerCard {
                Text("Security & Encryption", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Biometric App Lock & Screen Shield", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Require fingerprint/PIN on launch and hide balances in recents",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.biometricLockEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled && activity != null) {
                                BiometricHelper.authenticate(
                                    activity = activity,
                                    title = "Enable App Lock",
                                    subtitle = "Confirm identity to enable Biometric Lock",
                                    onSuccess = { viewModel.setBiometricLockEnabled(true) },
                                    onError = {}
                                )
                            } else {
                                viewModel.setBiometricLockEnabled(false)
                            }
                        },
                        modifier = Modifier.testTag("biometric_lock_switch")
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Optional E2E Encryption Passphrase", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            if (prefs.e2eEnabled) {
                                if (e2eUnlocked) "Active (AES-256-GCM unlocked)" else "Locked — enter passphrase to decrypt"
                            } else "Not configured (cloud TLS + zero-trust rules active)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { showE2EDialog = true },
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (prefs.e2eEnabled) "Manage" else "Set Key")
                    }
                }
            }
        }

        // Notifications & Theme
        item {
            LedgerCard {
                Text("Preferences & Notifications", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Due Date Push Alerts", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Notify ${prefs.reminderDaysBefore} days before installment due dates",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = prefs.notificationsEnabled,
                        onCheckedChange = { enable ->
                            if (enable && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val granted = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.POST_NOTIFICATIONS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (granted) {
                                    viewModel.setNotificationsConfig(true, prefs.reminderDaysBefore)
                                } else {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                }
                            } else {
                                viewModel.setNotificationsConfig(enable, prefs.reminderDaysBefore)
                            }
                        }
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text("Appearance Theme", style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        FilterChip(
                            selected = prefs.themeMode == mode,
                            onClick = { viewModel.setThemeMode(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.testTag("theme_${mode.name.lowercase()}")
                        )
                    }
                }
            }
        }

        // Sign Out
        item {
            OutlinedButton(
                onClick = { viewModel.signOut(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("sign_out_button"),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = sem.danger)
            ) {
                Icon(Icons.AutoMirrored.Outlined.Logout, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Sign Out")
            }
        }
    }

    if (showE2EDialog) {
        E2EPassphraseDialog(
            e2eEnabled = prefs.e2eEnabled,
            isUnlocked = e2eUnlocked,
            onDismiss = { showE2EDialog = false },
            onConfigure = { pass ->
                viewModel.configureE2EPassphrase(pass)
                showE2EDialog = false
            },
            onUnlock = { pass ->
                val ok = viewModel.unlockE2EWithPassphrase(pass)
                if (ok) showE2EDialog = false
                ok
            },
            onDisable = {
                viewModel.disableE2EEncryption()
                showE2EDialog = false
            }
        )
    }

    if (showRecoveryConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showRecoveryConfirmDialog = false },
            title = { Text("Transfer Ledger to New Gmail") },
            text = {
                Text(
                    "You will be prompted to sign in with your newly created or destination Gmail account. All local contracts, counterparties, installments, payments, and proof images will be rebound and synced in chunked batches to the new account."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showRecoveryConfirmDialog = false
                        if (activity != null) {
                            viewModel.transferAccountToNewGmail(activity, credentialManager)
                        }
                    }
                ) {
                    Text("Choose Gmail Account")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRecoveryConfirmDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun E2EPassphraseDialog(
    e2eEnabled: Boolean,
    isUnlocked: Boolean,
    onDismiss: () -> Unit,
    onConfigure: (String) -> Unit,
    onUnlock: (String) -> Boolean,
    onDisable: () -> Unit
) {
    var passphrase by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (!e2eEnabled) "Enable E2E Encryption" else "Manage E2E Passphrase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Derives an AES-256-GCM key via PBKDF2 to encrypt sensitive contact details, notes, and proof payloads before cloud sync.",
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("Passphrase (min 6 chars)") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true
                )
                if (err != null) {
                    Text(err!!, color = LocalSemanticColors.current.danger, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (e2eEnabled && !isUnlocked) {
                        if (!onUnlock(passphrase)) err = "Incorrect passphrase."
                    } else {
                        if (passphrase.length < 6) err = "Enter at least 6 characters."
                        else onConfigure(passphrase)
                    }
                }
            ) {
                Text(if (e2eEnabled && !isUnlocked) "Unlock" else "Save Key")
            }
        },
        dismissButton = {
            Row {
                if (e2eEnabled) {
                    TextButton(onClick = onDisable) { Text("Disable") }
                }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
