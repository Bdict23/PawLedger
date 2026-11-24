package com.example.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.PawLedgerRepository
import com.example.data.local.AdminPolicyEntity
import com.example.data.local.AppPreferences
import com.example.data.local.ContractSummaryRow
import com.example.data.local.CounterpartyEntity
import com.example.data.local.InstallmentEntity
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.RecoveryTransferLogEntity
import com.example.data.local.UpcomingInstallmentRow
import com.example.data.local.UserPreferencesStore
import com.example.data.remote.AuthManager
import com.example.data.remote.FirestoreSyncRepository
import com.example.domain.AnalyticsSortOption
import com.example.domain.ContractStatus
import com.example.domain.DashboardDirectionFilter
import com.example.domain.InstallmentStatus
import com.example.domain.InterestMode
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import com.example.domain.PaymentAllocationOrder
import com.example.domain.RepaymentFrequency
import com.example.domain.ThemeMode
import com.example.security.EncryptionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

data class CurrencyPortfolioSummary(
    val currencyCode: String,
    val activeLentPrincipalMinor: Long,
    val lentScheduledMinor: Long,
    val lentRepaidMinor: Long,
    val lentOutstandingMinor: Long,
    val activeBorrowedPrincipalMinor: Long,
    val borrowedScheduledMinor: Long,
    val borrowedRepaidMinor: Long,
    val borrowedOutstandingMinor: Long,
    val activeContractsCount: Int,
    val overdueContractsCount: Int,
    val returnRatePercent: Double,
    val onTimeRatePercent: Double
)

data class CounterpartyScorecard(
    val counterparty: CounterpartyEntity,
    val totalContracts: Int,
    val activeContracts: Int,
    val overdueContracts: Int,
    val settledContracts: Int,
    val totalLentPrincipalMinor: Long,
    val totalBorrowedPrincipalMinor: Long,
    val totalScheduledMinor: Long,
    val totalRepaidMinor: Long,
    val outstandingMinor: Long,
    val primaryCurrency: String,
    val onTimeRatePercent: Double,
    val returnRatePercent: Double,
    val avgDaysLate: Double,
    val contracts: List<ContractSummaryRow>
)

sealed interface SyncState {
    data object Idle : SyncState
    data object Syncing : SyncState
    data class Synced(val message: String) : SyncState
    data class Error(val message: String) : SyncState
}

class PawLedgerViewModel(
    private val currentUserId: String,
    private val repository: PawLedgerRepository,
    private val syncRepository: FirestoreSyncRepository,
    private val prefsStore: UserPreferencesStore,
    private val encryptionManager: EncryptionManager
) : ViewModel() {

    private val _syncState = MutableStateFlow<SyncState>(SyncState.Idle)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    private val _dashboardDirection = MutableStateFlow(DashboardDirectionFilter.ALL)
    val dashboardDirection: StateFlow<DashboardDirectionFilter> = _dashboardDirection.asStateFlow()

    private val _analyticsQuery = MutableStateFlow("")
    val analyticsQuery: StateFlow<String> = _analyticsQuery.asStateFlow()

    private val _analyticsDirection = MutableStateFlow(DashboardDirectionFilter.ALL)
    val analyticsDirection: StateFlow<DashboardDirectionFilter> = _analyticsDirection.asStateFlow()

    private val _analyticsStatus = MutableStateFlow<ContractStatus?>(null)
    val analyticsStatus: StateFlow<ContractStatus?> = _analyticsStatus.asStateFlow()

    private val _analyticsSort = MutableStateFlow(AnalyticsSortOption.ON_TIME_SCORE)
    val analyticsSort: StateFlow<AnalyticsSortOption> = _analyticsSort.asStateFlow()

    private val _e2eUnlocked = MutableStateFlow(encryptionManager.isUnlocked())
    val e2eUnlocked: StateFlow<Boolean> = _e2eUnlocked.asStateFlow()

    private val _feedbackMessage = MutableStateFlow<String?>(null)
    val feedbackMessage: StateFlow<String?> = _feedbackMessage.asStateFlow()

    val preferences: StateFlow<AppPreferences> = prefsStore.preferencesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), AppPreferences())

    val adminPolicy: StateFlow<AdminPolicyEntity?> = repository.observePolicy(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), null)

    val counterparties: StateFlow<List<CounterpartyEntity>> = repository.observeCounterparties(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val contracts: StateFlow<List<ContractSummaryRow>> = repository.observeContractSummaries(currentUserId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val upcomingInstallments: StateFlow<List<UpcomingInstallmentRow>> =
        repository.observeUpcomingInstallments(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val allPayments: StateFlow<List<PaymentTransactionEntity>> =
        repository.observeAllPayments(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val recoveryLogs: StateFlow<List<RecoveryTransferLogEntity>> =
        repository.observeRecoveryLogs(currentUserId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val portfolioSummaries: StateFlow<List<CurrencyPortfolioSummary>> = combine(
        contracts,
        upcomingInstallments
    ) { contractList, openInstallments ->
        val grouped = contractList.groupBy { it.currencyCode }
        grouped.map { (currency, list) ->
            val lent = list.filter { it.direction == LoanDirection.LENT.name }
            val borrowed = list.filter { it.direction == LoanDirection.BORROWED.name }

            val lentPrincipal = lent.sumOf { it.principalMinor }
            val lentScheduled = lent.sumOf { it.totalScheduledMinor }
            val lentRepaid = lent.sumOf { it.totalRepaidMinor }
            val lentOutstanding = lent.sumOf { it.remainingMinor }

            val borrowedPrincipal = borrowed.sumOf { it.principalMinor }
            val borrowedScheduled = borrowed.sumOf { it.totalScheduledMinor }
            val borrowedRepaid = borrowed.sumOf { it.totalRepaidMinor }
            val borrowedOutstanding = borrowed.sumOf { it.remainingMinor }

            val activeCount = list.count { it.status != ContractStatus.SETTLED.name }
            val overdueCount = list.count { it.status == ContractStatus.OVERDUE.name }

            val returnRate = if (lentPrincipal > 0L) {
                (lentRepaid.toDouble() / lentPrincipal.toDouble()) * 100.0
            } else 0.0

            val totalPaidInstallments = list.sumOf { it.paidInstallmentsCount }
            val totalOverdueInstallments = openInstallments
                .count { it.currencyCode == currency && it.status == InstallmentStatus.OVERDUE.name }
            val denominator = totalPaidInstallments + totalOverdueInstallments
            val onTimeRate = if (denominator > 0) {
                (totalPaidInstallments.toDouble() / denominator.toDouble()) * 100.0
            } else 100.0

            CurrencyPortfolioSummary(
                currencyCode = currency,
                activeLentPrincipalMinor = lentPrincipal,
                lentScheduledMinor = lentScheduled,
                lentRepaidMinor = lentRepaid,
                lentOutstandingMinor = lentOutstanding,
                activeBorrowedPrincipalMinor = borrowedPrincipal,
                borrowedScheduledMinor = borrowedScheduled,
                borrowedRepaidMinor = borrowedRepaid,
                borrowedOutstandingMinor = borrowedOutstanding,
                activeContractsCount = activeCount,
                overdueContractsCount = overdueCount,
                returnRatePercent = returnRate,
                onTimeRatePercent = onTimeRate
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    val filteredScorecards: StateFlow<List<CounterpartyScorecard>> = combine(
        counterparties,
        contracts,
        analyticsQuery,
        analyticsDirection,
        analyticsStatus,
        analyticsSort
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        val cps = values[0] as List<CounterpartyEntity>
        @Suppress("UNCHECKED_CAST")
        val ctrList = values[1] as List<ContractSummaryRow>
        val query = (values[2] as String).trim().lowercase()
        val dirFilter = values[3] as DashboardDirectionFilter
        val statusFilter = values[4] as ContractStatus?
        val sortOpt = values[5] as AnalyticsSortOption

        val today = IsoDate.todayIso()
        val cards = cps.mapNotNull { cp ->
            var matchedContracts = ctrList.filter { it.counterpartyId == cp.id }
            if (dirFilter == DashboardDirectionFilter.LENT) {
                matchedContracts = matchedContracts.filter { it.direction == LoanDirection.LENT.name }
            } else if (dirFilter == DashboardDirectionFilter.BORROWED) {
                matchedContracts = matchedContracts.filter { it.direction == LoanDirection.BORROWED.name }
            }
            if (statusFilter != null) {
                matchedContracts = matchedContracts.filter { it.status == statusFilter.name }
            }
            if (query.isNotEmpty() && !cp.name.lowercase().contains(query) &&
                matchedContracts.none { it.title.lowercase().contains(query) }
            ) {
                return@mapNotNull null
            }
            if (matchedContracts.isEmpty() && (dirFilter != DashboardDirectionFilter.ALL || statusFilter != null)) {
                return@mapNotNull null
            }

            val lentSum = matchedContracts.filter { it.direction == LoanDirection.LENT.name }.sumOf { it.principalMinor }
            val borrowedSum = matchedContracts.filter { it.direction == LoanDirection.BORROWED.name }.sumOf { it.principalMinor }
            val schedSum = matchedContracts.sumOf { it.totalScheduledMinor }
            val repaidSum = matchedContracts.sumOf { it.totalRepaidMinor }
            val outstandingSum = matchedContracts.sumOf { it.remainingMinor }
            val paidInst = matchedContracts.sumOf { it.paidInstallmentsCount }
            val overdueInst = matchedContracts.sumOf { it.overdueInstallmentsCount }
            val denom = paidInst + overdueInst
            val onTime = if (denom > 0) (paidInst.toDouble() / denom.toDouble()) * 100.0 else 100.0
            val returnRate = if (schedSum > 0L) (repaidSum.toDouble() / schedSum.toDouble()) * 100.0 else 0.0

            val avgLate = matchedContracts.mapNotNull { c ->
                val due = c.nextDueDateIso
                if (c.overdueInstallmentsCount > 0 && due != null) {
                    IsoDate.daysBetween(due, today).coerceAtLeast(0L).toDouble()
                } else null
            }.let { list -> if (list.isEmpty()) 0.0 else list.average() }

            CounterpartyScorecard(
                counterparty = cp,
                totalContracts = matchedContracts.size,
                activeContracts = matchedContracts.count { it.status == ContractStatus.ACTIVE.name },
                overdueContracts = matchedContracts.count { it.status == ContractStatus.OVERDUE.name },
                settledContracts = matchedContracts.count { it.status == ContractStatus.SETTLED.name },
                totalLentPrincipalMinor = lentSum,
                totalBorrowedPrincipalMinor = borrowedSum,
                totalScheduledMinor = schedSum,
                totalRepaidMinor = repaidSum,
                outstandingMinor = outstandingSum,
                primaryCurrency = matchedContracts.firstOrNull()?.currencyCode ?: "USD",
                onTimeRatePercent = onTime,
                returnRatePercent = returnRate,
                avgDaysLate = avgLate,
                contracts = matchedContracts
            )
        }

        when (sortOpt) {
            AnalyticsSortOption.ON_TIME_SCORE -> cards.sortedByDescending { it.onTimeRatePercent }
            AnalyticsSortOption.RETURN_RATE -> cards.sortedByDescending { it.returnRatePercent }
            AnalyticsSortOption.TOTAL_VOLUME -> cards.sortedByDescending { it.totalScheduledMinor }
            AnalyticsSortOption.NAME -> cards.sortedBy { it.counterparty.name.lowercase() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000L), emptyList())

    init {
        viewModelScope.launch(Dispatchers.IO) {
            prefsStore.setLastKnownUid(currentUserId)
            val prefs = prefsStore.current()
            if (prefs.e2eEnabled && !encryptionManager.isUnlocked() &&
                prefs.wrappedKeyBase64 != null && prefs.wrappedKeyIvBase64 != null
            ) {
                val ok = encryptionManager.unlockFromWrappedKey(prefs.wrappedKeyBase64, prefs.wrappedKeyIvBase64)
                _e2eUnlocked.value = ok
            }
            repository.ensureDefaultAdminPolicy(currentUserId)
            repository.evaluateOverdueForUser(currentUserId)
            repository.processRecurringAutoLogs(currentUserId)
            syncRepository.startRealtimePull(currentUserId, viewModelScope) { err ->
                _syncState.value = SyncState.Error(err)
            }
            syncNow()
        }
    }

    override fun onCleared() {
        super.onCleared()
        syncRepository.stopListening()
    }

    fun observeInstallments(contractId: String): Flow<List<InstallmentEntity>> =
        repository.observeInstallments(contractId, currentUserId)

    fun observePayments(contractId: String): Flow<List<PaymentTransactionEntity>> =
        repository.observePayments(contractId, currentUserId)

    fun setDashboardDirection(filter: DashboardDirectionFilter) {
        _dashboardDirection.value = filter
    }

    fun setAnalyticsQuery(query: String) {
        _analyticsQuery.value = query
    }

    fun setAnalyticsDirection(filter: DashboardDirectionFilter) {
        _analyticsDirection.value = filter
    }

    fun setAnalyticsStatus(status: ContractStatus?) {
        _analyticsStatus.value = status
    }

    fun setAnalyticsSort(sort: AnalyticsSortOption) {
        _analyticsSort.value = sort
    }

    fun clearFeedback() {
        _feedbackMessage.value = null
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            val res = syncRepository.pushPendingChanges(currentUserId)
            _syncState.value = res.fold(
                onSuccess = { SyncState.Synced("Synced") },
                onFailure = { SyncState.Error(it.localizedMessage ?: "Offline mode") }
            )
        }
    }

    fun runRecurringCheckNow() {
        viewModelScope.launch {
            val overdue = repository.evaluateOverdueForUser(currentUserId)
            val logged = repository.processRecurringAutoLogs(currentUserId)
            syncNow()
            _feedbackMessage.value = "Checked deadlines: $logged recurring logged, $overdue overdue."
        }
    }

    fun addCounterparty(
        name: String,
        phone: String,
        email: String,
        whatsapp: String,
        notes: String,
        onCreated: (CounterpartyEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.saveCounterparty(currentUserId, name, phone, email, whatsapp, notes)
            res.onSuccess {
                _feedbackMessage.value = "Added ${it.name}"
                onCreated(it)
            }.onFailure {
                _feedbackMessage.value = it.localizedMessage ?: "Could not save counterparty"
            }
        }
    }

    fun createContract(
        counterpartyId: String,
        direction: LoanDirection,
        title: String,
        principalMinor: Long,
        currencyCode: String,
        startDateIso: String,
        termCount: Int,
        frequency: RepaymentFrequency,
        interestMode: InterestMode,
        interestRateBps: Int,
        graceDays: Int,
        lateFeeBps: Int,
        lateFeeCapMinor: Long,
        allocationOrder: PaymentAllocationOrder,
        autoLogRecurring: Boolean,
        notes: String,
        onCreated: (String) -> Unit
    ) {
        viewModelScope.launch {
            val res = repository.createContractWithSchedule(
                userId = currentUserId,
                counterpartyId = counterpartyId,
                direction = direction,
                title = title,
                principalMinor = principalMinor,
                currencyCode = currencyCode,
                startDateIso = startDateIso,
                termCount = termCount,
                frequency = frequency,
                interestMode = interestMode,
                interestRateBps = interestRateBps,
                graceDays = graceDays,
                lateFeeBps = lateFeeBps,
                lateFeeCapMinor = lateFeeCapMinor,
                allocationOrder = allocationOrder,
                autoLogRecurring = autoLogRecurring,
                notes = notes
            )
            res.onSuccess { id ->
                _feedbackMessage.value = "Contract created with $termCount scheduled installments."
                onCreated(id)
            }.onFailure {
                _feedbackMessage.value = it.localizedMessage ?: "Failed to create contract"
            }
        }
    }

    fun toggleAutoLog(contractId: String, enabled: Boolean) {
        viewModelScope.launch {
            val res = repository.toggleContractAutoLog(currentUserId, contractId, enabled)
            res.onFailure {
                _feedbackMessage.value = it.localizedMessage ?: "Could not update auto-log setting"
            }
        }
    }

    fun recordPayment(
        contractId: String,
        amountMinor: Long,
        paidDateIso: String,
        note: String,
        proofUri: Uri?,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.recordPayment(
                userId = currentUserId,
                contractId = contractId,
                amountMinor = amountMinor,
                paidDateIso = paidDateIso,
                note = note,
                proofUri = proofUri
            )
            res.onSuccess {
                _feedbackMessage.value = if (proofUri != null) {
                    "Payment recorded with SHA-256 verified proof."
                } else {
                    "Payment recorded."
                }
                onSuccess()
            }.onFailure { err ->
                val message = err.localizedMessage ?: "Failed to record payment"
                _feedbackMessage.value = message
                onError(message)
            }
        }
    }

    fun updateContract(
        contractId: String,
        counterpartyId: String,
        direction: LoanDirection,
        title: String,
        principalMinor: Long,
        currencyCode: String,
        startDateIso: String,
        termCount: Int,
        frequency: RepaymentFrequency,
        interestMode: InterestMode,
        interestRateBps: Int,
        graceDays: Int,
        lateFeeBps: Int,
        lateFeeCapMinor: Long,
        allocationOrder: PaymentAllocationOrder,
        autoLogRecurring: Boolean,
        notes: String,
        onSaved: () -> Unit
    ) {
        viewModelScope.launch {
            val res = repository.updateContractWithSchedule(
                userId = currentUserId,
                contractId = contractId,
                counterpartyId = counterpartyId,
                direction = direction,
                title = title,
                principalMinor = principalMinor,
                currencyCode = currencyCode,
                startDateIso = startDateIso,
                termCount = termCount,
                frequency = frequency,
                interestMode = interestMode,
                interestRateBps = interestRateBps,
                graceDays = graceDays,
                lateFeeBps = lateFeeBps,
                lateFeeCapMinor = lateFeeCapMinor,
                allocationOrder = allocationOrder,
                autoLogRecurring = autoLogRecurring,
                notes = notes
            )
            res.onSuccess {
                _feedbackMessage.value = "Contract and repayment schedule updated."
                onSaved()
            }.onFailure {
                _feedbackMessage.value = it.localizedMessage ?: "Failed to update contract"
            }
        }
    }

    fun deleteContract(contractId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            val res = repository.deleteContract(currentUserId, contractId)
            res.onSuccess {
                _feedbackMessage.value = "Contract removed."
                onDeleted()
            }.onFailure {
                _feedbackMessage.value = it.localizedMessage ?: "Failed to remove contract"
            }
        }
    }

    fun updatePayment(
        contractId: String,
        paymentId: String,
        amountMinor: Long,
        paidDateIso: String,
        note: String,
        proofUri: Uri?,
        removeProof: Boolean,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.updatePayment(
                userId = currentUserId,
                contractId = contractId,
                paymentId = paymentId,
                amountMinor = amountMinor,
                paidDateIso = paidDateIso,
                note = note,
                proofUri = proofUri,
                removeProof = removeProof
            )
            res.onSuccess {
                _feedbackMessage.value = "Repayment updated."
                onSuccess()
            }.onFailure { err ->
                val message = err.localizedMessage ?: "Failed to update repayment"
                _feedbackMessage.value = message
                onError(message)
            }
        }
    }

    fun deletePayment(
        contractId: String,
        paymentId: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.deletePayment(currentUserId, contractId, paymentId)
            res.onSuccess {
                _feedbackMessage.value = "Repayment deleted."
                onSuccess()
            }.onFailure { err ->
                val message = err.localizedMessage ?: "Failed to delete repayment"
                _feedbackMessage.value = message
                onError(message)
            }
        }
    }

    fun updateInstallment(
        contractId: String,
        installmentId: String,
        dueDateIso: String,
        principalDueMinor: Long,
        interestDueMinor: Long,
        lateFeeDueMinor: Long,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            val res = repository.updateInstallment(
                userId = currentUserId,
                contractId = contractId,
                installmentId = installmentId,
                dueDateIso = dueDateIso,
                principalDueMinor = principalDueMinor,
                interestDueMinor = interestDueMinor,
                lateFeeDueMinor = lateFeeDueMinor
            )
            res.onSuccess {
                _feedbackMessage.value = "Installment schedule updated."
                onSuccess()
            }.onFailure { err ->
                val message = err.localizedMessage ?: "Failed to update installment"
                _feedbackMessage.value = message
                onError(message)
            }
        }
    }

    fun saveAdminPolicy(policy: AdminPolicyEntity) {
        viewModelScope.launch {
            val res = repository.saveAdminPolicy(policy)
            res.onSuccess {
                _feedbackMessage.value = "Super Admin policy updated and synced."
            }.onFailure {
                _feedbackMessage.value = it.localizedMessage ?: "Failed to save policy"
            }
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { prefsStore.setThemeMode(mode) }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch { prefsStore.setBiometricLockEnabled(enabled) }
    }

    fun setNotificationsConfig(enabled: Boolean, daysBefore: Int) {
        viewModelScope.launch { prefsStore.setNotificationsEnabled(enabled, daysBefore) }
    }

    fun configureE2EPassphrase(passphrase: String) {
        viewModelScope.launch {
            if (passphrase.length < 6) {
                _feedbackMessage.value = "Passphrase must be at least 6 characters."
                return@launch
            }
            val salt = encryptionManager.generateSaltBase64()
            val key = encryptionManager.unlockWithPassphrase(passphrase, salt)
            val (wrapped, iv) = encryptionManager.wrapKeyWithAndroidKeystore(key)
            prefsStore.saveE2EKeyMaterial(
                enabled = true,
                saltBase64 = salt,
                wrappedKeyBase64 = wrapped,
                wrappedKeyIvBase64 = iv
            )
            _e2eUnlocked.value = true
            _feedbackMessage.value = "End-to-End AES-256-GCM encryption enabled."
        }
    }

    fun unlockE2EWithPassphrase(passphrase: String): Boolean {
        val prefs = preferences.value
        val salt = prefs.e2eSaltBase64 ?: return false
        return try {
            val key = encryptionManager.unlockWithPassphrase(passphrase, salt)
            val (wrapped, iv) = encryptionManager.wrapKeyWithAndroidKeystore(key)
            viewModelScope.launch {
                prefsStore.saveE2EKeyMaterial(true, salt, wrapped, iv)
            }
            _e2eUnlocked.value = true
            true
        } catch (_: Exception) {
            false
        }
    }

    fun disableE2EEncryption() {
        viewModelScope.launch {
            encryptionManager.lockSession()
            prefsStore.saveE2EKeyMaterial(false, null, null, null)
            _e2eUnlocked.value = false
            _feedbackMessage.value = "Passphrase E2E encryption disabled."
        }
    }

    fun exportJsonBackup(onFileReady: (File) -> Unit) {
        viewModelScope.launch {
            repository.exportJsonBackup(currentUserId)
                .onSuccess(onFileReady)
                .onFailure { _feedbackMessage.value = it.localizedMessage ?: "Export failed" }
        }
    }

    fun exportCsvLedger(onFileReady: (File) -> Unit) {
        viewModelScope.launch {
            repository.exportCsvLedger(currentUserId)
                .onSuccess(onFileReady)
                .onFailure { _feedbackMessage.value = it.localizedMessage ?: "CSV export failed" }
        }
    }

    fun restoreFromBackupUri(uri: Uri) {
        viewModelScope.launch {
            _syncState.value = SyncState.Syncing
            repository.restoreFromBackupBundleUri(currentUserId, uri)
                .onSuccess { count ->
                    _syncState.value = SyncState.Synced("Restored")
                    _feedbackMessage.value = "Restored & synced $count records into this account."
                }
                .onFailure { err ->
                    _syncState.value = SyncState.Error("Restore failed")
                    _feedbackMessage.value = err.localizedMessage ?: "Backup restore failed"
                }
        }
    }

    fun transferAccountToNewGmail(
        activity: Activity,
        credentialManager: CredentialManager
    ) {
        viewModelScope.launch {
            val previousUid = currentUserId
            syncRepository.stopListening()
            val signInRes = AuthManager.signInWithAnyGoogleAccount(activity, credentialManager)
            signInRes.onSuccess { newUser ->
                if (newUser.uid == previousUid) {
                    _feedbackMessage.value = "Please select a newly created or different Gmail account."
                    syncRepository.startRealtimePull(previousUid, viewModelScope) { err ->
                        _syncState.value = SyncState.Error(err)
                    }
                    return@onSuccess
                }
                _syncState.value = SyncState.Syncing
                repository.executeLocalAccountRecoveryTransfer(previousUid, newUser.uid)
                    .onSuccess { migratedCount ->
                        prefsStore.setLastKnownUid(newUser.uid)
                        _syncState.value = SyncState.Synced("Transferred")
                        _feedbackMessage.value = "Transferred $migratedCount records to ${newUser.email ?: newUser.uid}."
                    }
                    .onFailure { err ->
                        _syncState.value = SyncState.Error("Transfer failed")
                        _feedbackMessage.value = err.localizedMessage ?: "Recovery transfer failed."
                    }
            }.onFailure { err ->
                _feedbackMessage.value = err.localizedMessage ?: "New Gmail selection cancelled."
                syncRepository.startRealtimePull(previousUid, viewModelScope) { e ->
                    _syncState.value = SyncState.Error(e)
                }
            }
        }
    }

    fun signOut(context: Context) {
        syncRepository.stopListening()
        encryptionManager.lockSession()
        val cm = CredentialManager.create(context)
        AuthManager.signOut(cm, viewModelScope)
    }
}
