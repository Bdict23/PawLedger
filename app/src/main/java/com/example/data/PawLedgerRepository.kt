package com.example.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.example.data.local.AdminPolicyEntity
import com.example.data.local.ContractSummaryRow
import com.example.data.local.CounterpartyEntity
import com.example.data.local.InstallmentEntity
import com.example.data.local.LoanContractEntity
import com.example.data.local.PawLedgerDatabase
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.RecoveryTransferLogEntity
import com.example.data.local.UpcomingInstallmentRow
import com.example.data.local.UserPreferencesStore
import com.example.data.remote.FirestoreSyncRepository
import com.example.domain.ContractStatus
import com.example.domain.CurrencyFormatters
import com.example.domain.FinancialCalculator
import com.example.domain.InstallmentStatus
import com.example.domain.InterestMode
import com.example.domain.IsoDate
import com.example.domain.LoanDirection
import com.example.domain.PaymentAllocationOrder
import com.example.domain.RepaymentFrequency
import com.example.security.ProofImageProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class BackupBundle(
    val version: Int = 1,
    val exportedAtEpoch: Long,
    val sourceUid: String,
    val policy: BackupPolicy?,
    val counterparties: List<BackupCounterparty>,
    val contracts: List<BackupContract>,
    val installments: List<BackupInstallment>,
    val payments: List<BackupPayment>
)

@Serializable
data class BackupPolicy(
    val id: String,
    val defaultInterestMode: String,
    val allowZeroInterest: Boolean,
    val allowSimpleFlat: Boolean,
    val allowAmortizedMonthly: Boolean,
    val defaultRateBps: Int,
    val defaultGraceDays: Int,
    val defaultLateFeeBps: Int,
    val defaultLateFeeCapMinor: Long,
    val paymentAllocationOrder: String,
    val defaultCurrencyCode: String,
    val defaultAutoLogRecurring: Boolean
)

@Serializable
data class BackupCounterparty(
    val id: String,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val whatsapp: String = "",
    val notes: String = ""
)

@Serializable
data class BackupContract(
    val id: String,
    val counterpartyId: String,
    val direction: String,
    val title: String,
    val principalMinor: Long,
    val currencyCode: String,
    val startDateIso: String,
    val termCount: Int,
    val frequency: String,
    val interestMode: String,
    val interestRateBps: Int,
    val graceDays: Int,
    val lateFeeBps: Int,
    val lateFeeCapMinor: Long,
    val paymentAllocationOrder: String,
    val autoLogRecurring: Boolean,
    val status: String,
    val notes: String = ""
)

@Serializable
data class BackupInstallment(
    val id: String,
    val contractId: String,
    val installmentNumber: Int,
    val dueDateIso: String,
    val principalDueMinor: Long,
    val interestDueMinor: Long,
    val lateFeeDueMinor: Long,
    val paidMinor: Long,
    val status: String
)

@Serializable
data class BackupPayment(
    val id: String,
    val contractId: String,
    val installmentId: String? = null,
    val amountMinor: Long,
    val paidDateIso: String,
    val paidAtEpoch: Long,
    val autoLogged: Boolean,
    val note: String = "",
    val proofBase64: String? = null,
    val proofSha256: String? = null
)

class PawLedgerRepository(
    private val localDb: PawLedgerDatabase,
    private val syncRepo: FirestoreSyncRepository,
    private val appContext: Context
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val prefsStore = UserPreferencesStore(appContext)

    fun observePolicy(userId: String): Flow<AdminPolicyEntity?> =
        localDb.adminPolicyDao().observePolicy(userId)

    fun observeCounterparties(userId: String): Flow<List<CounterpartyEntity>> =
        localDb.counterpartyDao().observeAll(userId)

    fun observeContractSummaries(userId: String): Flow<List<ContractSummaryRow>> =
        localDb.loanContractDao().observeContractSummaries(userId)

    fun observeUpcomingInstallments(userId: String): Flow<List<UpcomingInstallmentRow>> =
        localDb.installmentDao().observeUpcomingOpenInstallments(userId)

    fun observeInstallments(contractId: String, userId: String): Flow<List<InstallmentEntity>> =
        localDb.installmentDao().observeForContract(contractId, userId)

    fun observePayments(contractId: String, userId: String): Flow<List<PaymentTransactionEntity>> =
        localDb.paymentTransactionDao().observeForContract(contractId, userId)

    fun observeAllPayments(userId: String): Flow<List<PaymentTransactionEntity>> =
        localDb.paymentTransactionDao().observeAllForUser(userId)

    fun observeRecoveryLogs(userId: String): Flow<List<RecoveryTransferLogEntity>> =
        localDb.recoveryTransferLogDao().observeLogs(userId)

    suspend fun ensureDefaultAdminPolicy(userId: String): AdminPolicyEntity = withContext(Dispatchers.IO) {
        val existing = localDb.adminPolicyDao().getPolicy(userId)
        if (existing != null) return@withContext existing
        val created = AdminPolicyEntity(
            id = "default",
            userId = userId,
            updatedAtEpoch = System.currentTimeMillis(),
            cloudCreated = false,
            syncedToCloud = false
        )
        localDb.adminPolicyDao().upsert(created)
        syncRepo.pushPendingChanges(userId)
        created
    }

    suspend fun saveAdminPolicy(policy: AdminPolicyEntity): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val updated = policy.copy(
                updatedAtEpoch = System.currentTimeMillis(),
                syncedToCloud = false
            )
            localDb.adminPolicyDao().upsert(updated)
            syncRepo.pushPendingChanges(policy.userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveCounterparty(
        userId: String,
        name: String,
        phone: String,
        email: String,
        whatsapp: String,
        notes: String
    ): Result<CounterpartyEntity> = withContext(Dispatchers.IO) {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || trimmed.length > 120) {
            return@withContext Result.failure(IllegalArgumentException("Counterparty name must be 1 to 120 characters."))
        }
        val now = System.currentTimeMillis()
        val entity = CounterpartyEntity(
            id = "cp_${UUID.randomUUID().toString().replace("-", "").take(16)}",
            userId = userId,
            name = trimmed,
            phone = phone.trim().take(40),
            email = email.trim().take(120),
            whatsapp = whatsapp.trim().take(40),
            notes = notes.trim().take(2000),
            createdAtEpoch = now,
            updatedAtEpoch = now,
            cloudCreated = false,
            syncedToCloud = false
        )
        localDb.counterpartyDao().upsert(entity)
        syncRepo.pushPendingChanges(userId)
        Result.success(entity)
    }

    suspend fun createContractWithSchedule(
        userId: String,
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
        notes: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (title.trim().isEmpty()) {
            return@withContext Result.failure(IllegalArgumentException("Contract title is required."))
        }
        if (principalMinor <= 0L) {
            return@withContext Result.failure(IllegalArgumentException("Principal must be greater than zero."))
        }
        if (termCount !in 1..360) {
            return@withContext Result.failure(IllegalArgumentException("Term count must be between 1 and 360."))
        }
        if (!IsoDate.isValid(startDateIso)) {
            return@withContext Result.failure(IllegalArgumentException("Invalid start date."))
        }

        val contractId = "ctr_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val now = System.currentTimeMillis()
        val generated = FinancialCalculator.generateSchedule(
            principalMinor = principalMinor,
            startDateIso = startDateIso,
            termCount = termCount,
            frequency = frequency,
            interestMode = interestMode,
            interestRateBps = interestRateBps
        )

        val contract = LoanContractEntity(
            id = contractId,
            userId = userId,
            counterpartyId = counterpartyId,
            direction = direction.name,
            title = title.trim().take(140),
            principalMinor = principalMinor,
            currencyCode = currencyCode.uppercase(),
            startDateIso = startDateIso,
            termCount = termCount,
            frequency = frequency.name,
            interestMode = interestMode.name,
            interestRateBps = interestRateBps.coerceIn(0, 100000),
            graceDays = graceDays.coerceIn(0, 365),
            lateFeeBps = lateFeeBps.coerceIn(0, 50000),
            lateFeeCapMinor = lateFeeCapMinor.coerceAtLeast(0L),
            paymentAllocationOrder = allocationOrder.name,
            autoLogRecurring = autoLogRecurring,
            status = ContractStatus.ACTIVE.name,
            notes = notes.trim().take(2000),
            createdAtEpoch = now,
            updatedAtEpoch = now,
            cloudCreated = false,
            syncedToCloud = false
        )

        val installments = generated.map { item ->
            InstallmentEntity(
                id = "${contractId}_i${item.installmentNumber}",
                userId = userId,
                contractId = contractId,
                installmentNumber = item.installmentNumber,
                dueDateIso = item.dueDateIso,
                principalDueMinor = item.principalDueMinor,
                interestDueMinor = item.interestDueMinor,
                lateFeeDueMinor = 0L,
                paidMinor = 0L,
                status = InstallmentStatus.PENDING.name,
                updatedAtEpoch = now,
                cloudCreated = false,
                syncedToCloud = false
            )
        }

        localDb.withTransaction {
            localDb.loanContractDao().upsert(contract)
            localDb.installmentDao().upsertAll(installments)
        }

        evaluateOverdueForUser(userId)
        syncRepo.pushPendingChanges(userId)
        Result.success(contractId)
    }

    suspend fun updateContractWithSchedule(
        userId: String,
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
        notes: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (title.trim().isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("Contract title is required."))
            }
            if (principalMinor <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Principal must be greater than zero."))
            }
            if (termCount !in 1..360) {
                return@withContext Result.failure(IllegalArgumentException("Term count must be between 1 and 360."))
            }
            if (!IsoDate.isValid(startDateIso)) {
                return@withContext Result.failure(IllegalArgumentException("Invalid start date."))
            }
            val existing = localDb.loanContractDao().getById(contractId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Contract not found."))

            val now = System.currentTimeMillis()
            val oldInstallments = localDb.installmentDao().getForContract(contractId, userId)
            val oldByNumber = oldInstallments.associateBy { it.installmentNumber }
            val extraOldInstallments = oldInstallments.filter { it.installmentNumber > termCount }

            val generated = FinancialCalculator.generateSchedule(
                principalMinor = principalMinor,
                startDateIso = startDateIso,
                termCount = termCount,
                frequency = frequency,
                interestMode = interestMode,
                interestRateBps = interestRateBps
            )

            val updatedContract = existing.copy(
                counterpartyId = counterpartyId,
                direction = direction.name,
                title = title.trim().take(140),
                principalMinor = principalMinor,
                currencyCode = currencyCode.uppercase(),
                startDateIso = startDateIso,
                termCount = termCount,
                frequency = frequency.name,
                interestMode = interestMode.name,
                interestRateBps = interestRateBps.coerceIn(0, 100000),
                graceDays = graceDays.coerceIn(0, 365),
                lateFeeBps = lateFeeBps.coerceIn(0, 50000),
                lateFeeCapMinor = lateFeeCapMinor.coerceAtLeast(0L),
                paymentAllocationOrder = allocationOrder.name,
                autoLogRecurring = autoLogRecurring,
                notes = notes.trim().take(2000),
                updatedAtEpoch = now,
                syncedToCloud = false
            )

            val newInstallments = generated.map { item ->
                val prev = oldByNumber[item.installmentNumber]
                InstallmentEntity(
                    id = "${contractId}_i${item.installmentNumber}",
                    userId = userId,
                    contractId = contractId,
                    installmentNumber = item.installmentNumber,
                    dueDateIso = item.dueDateIso,
                    principalDueMinor = item.principalDueMinor,
                    interestDueMinor = item.interestDueMinor,
                    lateFeeDueMinor = if (lateFeeBps > 0) (prev?.lateFeeDueMinor ?: 0L) else 0L,
                    paidMinor = 0L,
                    status = InstallmentStatus.PENDING.name,
                    updatedAtEpoch = now,
                    cloudCreated = prev?.cloudCreated ?: false,
                    syncedToCloud = false
                )
            }

            if (extraOldInstallments.isNotEmpty()) {
                prefsStore.addPendingCloudDeletes(
                    userId,
                    extraOldInstallments.filter { it.cloudCreated }.map { "installments" to it.id }
                )
            }

            localDb.withTransaction {
                localDb.loanContractDao().upsert(updatedContract)
                if (extraOldInstallments.isNotEmpty()) {
                    localDb.installmentDao().deleteExtraInstallments(contractId, userId, termCount)
                }
                localDb.installmentDao().upsertAll(newInstallments)
                recalculateContractAllocationsLocked(userId, contractId, now)
            }

            evaluateOverdueForUser(userId)
            syncRepo.pushPendingChanges(userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteContract(userId: String, contractId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val contract = localDb.loanContractDao().getById(contractId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Contract not found."))
            val payments = localDb.paymentTransactionDao().getForContract(contractId, userId)
            payments.forEach { pay ->
                pay.proofLocalUri?.let { path -> runCatching { File(path).delete() } }
            }
            val now = System.currentTimeMillis()
            if (contract.cloudCreated) {
                localDb.loanContractDao().softDelete(contractId, userId, now)
                syncRepo.pushPendingChanges(userId)
            } else {
                localDb.loanContractDao().hardDelete(contractId, userId)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun toggleContractAutoLog(userId: String, contractId: String, enabled: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            val contract = localDb.loanContractDao().getById(contractId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Contract not found."))
            if (contract.status == ContractStatus.SETTLED.name) {
                return@withContext Result.failure(IllegalStateException("Settled contracts are locked."))
            }
            localDb.loanContractDao().upsert(
                contract.copy(
                    autoLogRecurring = enabled,
                    updatedAtEpoch = System.currentTimeMillis(),
                    syncedToCloud = false
                )
            )
            syncRepo.pushPendingChanges(userId)
            Result.success(Unit)
        }

    suspend fun recordPayment(
        userId: String,
        contractId: String,
        amountMinor: Long,
        paidDateIso: String,
        note: String,
        proofUri: Uri?,
        deterministicId: String? = null,
        autoLogged: Boolean = false
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (amountMinor <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Payment amount must be positive."))
            }
            if (!IsoDate.isValid(paidDateIso)) {
                return@withContext Result.failure(IllegalArgumentException("Please select a valid payment date."))
            }
            val contract = localDb.loanContractDao().getById(contractId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Contract not found."))
            if (contract.status == ContractStatus.SETTLED.name) {
                return@withContext Result.failure(IllegalStateException("Contract is already settled."))
            }

            val paymentId = deterministicId ?: "pay_${UUID.randomUUID().toString().replace("-", "").take(16)}"

            // Idempotency check for automated recurring transactions
            if (deterministicId != null && localDb.paymentTransactionDao().getById(paymentId, userId) != null) {
                return@withContext Result.success(paymentId)
            }

            val processedProof = if (proofUri != null) {
                val res = ProofImageProcessor.processAndStoreProof(appContext, userId, paymentId, proofUri)
                if (res.isFailure) {
                    return@withContext Result.failure(res.exceptionOrNull() ?: IllegalStateException("Proof compression failed."))
                }
                res.getOrNull()
            } else null

            val now = System.currentTimeMillis()
            val installments = localDb.installmentDao().getForContract(contractId, userId)
                .sortedBy { it.installmentNumber }

            val targetInstallmentId = installments.firstOrNull { it.status != InstallmentStatus.PAID.name }?.id

            val payment = PaymentTransactionEntity(
                id = paymentId,
                userId = userId,
                contractId = contractId,
                installmentId = targetInstallmentId,
                amountMinor = amountMinor,
                paidDateIso = paidDateIso,
                paidAtEpoch = now,
                autoLogged = autoLogged,
                note = note.trim().take(1000),
                proofLocalUri = processedProof?.localPath,
                proofBase64 = processedProof?.base64,
                proofSha256 = processedProof?.sha256,
                createdAtEpoch = now,
                updatedAtEpoch = now,
                cloudCreated = false,
                syncedToCloud = false
            )

            var remainingToAllocate = amountMinor
            val today = IsoDate.todayIso()
            val updatedInstallments = installments.map { inst ->
                val totalDue = inst.principalDueMinor + inst.interestDueMinor + inst.lateFeeDueMinor
                val openGap = (totalDue - inst.paidMinor).coerceAtLeast(0L)
                if (remainingToAllocate > 0L && openGap > 0L) {
                    val applied = minOf(remainingToAllocate, openGap)
                    remainingToAllocate -= applied
                    val newPaid = inst.paidMinor + applied
                    val newStatus = when {
                        newPaid >= totalDue -> InstallmentStatus.PAID.name
                        IsoDate.daysBetween(inst.dueDateIso, today) > contract.graceDays -> InstallmentStatus.OVERDUE.name
                        newPaid > 0L -> InstallmentStatus.PARTIAL.name
                        else -> InstallmentStatus.PENDING.name
                    }
                    inst.copy(
                        paidMinor = newPaid,
                        status = newStatus,
                        updatedAtEpoch = now,
                        syncedToCloud = false
                    )
                } else {
                    inst
                }
            }

            val allPaid = updatedInstallments.isNotEmpty() && updatedInstallments.all { it.status == InstallmentStatus.PAID.name }
            val anyOverdue = updatedInstallments.any { it.status == InstallmentStatus.OVERDUE.name }
            val nextContractStatus = when {
                allPaid -> ContractStatus.SETTLED.name
                anyOverdue -> ContractStatus.OVERDUE.name
                else -> ContractStatus.ACTIVE.name
            }

            localDb.withTransaction {
                val inserted = localDb.paymentTransactionDao().insertIfAbsent(payment)
                if (inserted != -1L) {
                    localDb.installmentDao().upsertAll(updatedInstallments)
                    localDb.loanContractDao().upsert(
                        contract.copy(
                            status = nextContractStatus,
                            updatedAtEpoch = now,
                            syncedToCloud = false
                        )
                    )
                }
            }

            syncRepo.pushPendingChanges(userId)
            Result.success(paymentId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updatePayment(
        userId: String,
        contractId: String,
        paymentId: String,
        amountMinor: Long,
        paidDateIso: String,
        note: String,
        proofUri: Uri?,
        removeProof: Boolean
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (amountMinor <= 0L) {
                return@withContext Result.failure(IllegalArgumentException("Payment amount must be positive."))
            }
            if (!IsoDate.isValid(paidDateIso)) {
                return@withContext Result.failure(IllegalArgumentException("Please select a valid payment date."))
            }
            val existing = localDb.paymentTransactionDao().getById(paymentId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Payment record not found."))

            var proofLocalUri = existing.proofLocalUri
            var proofBase64 = existing.proofBase64
            var proofSha256 = existing.proofSha256

            if (proofUri != null) {
                val res = ProofImageProcessor.processAndStoreProof(appContext, userId, paymentId, proofUri)
                if (res.isFailure) {
                    return@withContext Result.failure(res.exceptionOrNull() ?: IllegalStateException("Proof compression failed."))
                }
                val processed = res.getOrThrow()
                proofLocalUri = processed.localPath
                proofBase64 = processed.base64
                proofSha256 = processed.sha256
            } else if (removeProof) {
                existing.proofLocalUri?.let { path -> runCatching { File(path).delete() } }
                proofLocalUri = null
                proofBase64 = null
                proofSha256 = null
            }

            val now = System.currentTimeMillis()
            val updated = existing.copy(
                amountMinor = amountMinor,
                paidDateIso = paidDateIso,
                note = note.trim().take(1000),
                proofLocalUri = proofLocalUri,
                proofBase64 = proofBase64,
                proofSha256 = proofSha256,
                updatedAtEpoch = now,
                syncedToCloud = false
            )

            localDb.withTransaction {
                localDb.paymentTransactionDao().upsert(updated)
                recalculateContractAllocationsLocked(userId, contractId, now)
            }

            syncRepo.pushPendingChanges(userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePayment(
        userId: String,
        contractId: String,
        paymentId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val existing = localDb.paymentTransactionDao().getById(paymentId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Payment record not found."))
            existing.proofLocalUri?.let { path -> runCatching { File(path).delete() } }
            if (existing.cloudCreated) {
                prefsStore.addPendingCloudDeletes(userId, listOf("payments" to paymentId))
            }
            val now = System.currentTimeMillis()
            localDb.withTransaction {
                localDb.paymentTransactionDao().deleteById(paymentId, userId)
                recalculateContractAllocationsLocked(userId, contractId, now)
            }
            syncRepo.pushPendingChanges(userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun updateInstallment(
        userId: String,
        contractId: String,
        installmentId: String,
        dueDateIso: String,
        principalDueMinor: Long,
        interestDueMinor: Long,
        lateFeeDueMinor: Long
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!IsoDate.isValid(dueDateIso)) {
                return@withContext Result.failure(IllegalArgumentException("Please select a valid due date."))
            }
            if (principalDueMinor < 0L || interestDueMinor < 0L || lateFeeDueMinor < 0L ||
                (principalDueMinor + interestDueMinor + lateFeeDueMinor) <= 0L
            ) {
                return@withContext Result.failure(IllegalArgumentException("Installment total due must be positive."))
            }
            val existing = localDb.installmentDao().getById(installmentId, userId)
                ?: return@withContext Result.failure(IllegalArgumentException("Installment not found."))
            val now = System.currentTimeMillis()
            val updated = existing.copy(
                dueDateIso = dueDateIso,
                principalDueMinor = principalDueMinor,
                interestDueMinor = interestDueMinor,
                lateFeeDueMinor = lateFeeDueMinor,
                updatedAtEpoch = now,
                syncedToCloud = false
            )
            localDb.withTransaction {
                localDb.installmentDao().upsert(updated)
                recalculateContractAllocationsLocked(userId, contractId, now)
            }
            syncRepo.pushPendingChanges(userId)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private suspend fun recalculateContractAllocationsLocked(
        userId: String,
        contractId: String,
        now: Long
    ) {
        val contract = localDb.loanContractDao().getById(contractId, userId) ?: return
        val installments = localDb.installmentDao().getForContract(contractId, userId)
            .sortedBy { it.installmentNumber }
        val payments = localDb.paymentTransactionDao().getForContract(contractId, userId)

        val today = IsoDate.todayIso()
        val paidBuckets = LongArray(installments.size)
        val updatedPayments = mutableListOf<PaymentTransactionEntity>()

        for (p in payments) {
            var rem = p.amountMinor
            var firstTargetId: String? = null
            for (idx in installments.indices) {
                if (rem <= 0L) break
                val inst = installments[idx]
                val totalDue = inst.principalDueMinor + inst.interestDueMinor + inst.lateFeeDueMinor
                val openGap = (totalDue - paidBuckets[idx]).coerceAtLeast(0L)
                if (openGap > 0L) {
                    if (firstTargetId == null) firstTargetId = inst.id
                    val applied = minOf(rem, openGap)
                    paidBuckets[idx] += applied
                    rem -= applied
                }
            }
            if (firstTargetId != p.installmentId) {
                updatedPayments += p.copy(
                    installmentId = firstTargetId,
                    updatedAtEpoch = now,
                    syncedToCloud = false
                )
            }
        }
        if (updatedPayments.isNotEmpty()) {
            localDb.paymentTransactionDao().upsertAll(updatedPayments)
        }

        val updatedInstallments = installments.mapIndexed { idx, inst ->
            val newPaid = paidBuckets[idx]
            val totalDue = inst.principalDueMinor + inst.interestDueMinor + inst.lateFeeDueMinor
            val newStatus = when {
                newPaid >= totalDue -> InstallmentStatus.PAID.name
                IsoDate.daysBetween(inst.dueDateIso, today) > contract.graceDays -> InstallmentStatus.OVERDUE.name
                newPaid > 0L -> InstallmentStatus.PARTIAL.name
                else -> InstallmentStatus.PENDING.name
            }
            if (newPaid != inst.paidMinor || newStatus != inst.status) {
                inst.copy(
                    paidMinor = newPaid,
                    status = newStatus,
                    updatedAtEpoch = now,
                    syncedToCloud = false
                )
            } else {
                inst
            }
        }
        localDb.installmentDao().upsertAll(updatedInstallments)

        val allPaid = updatedInstallments.isNotEmpty() && updatedInstallments.all { it.status == InstallmentStatus.PAID.name }
        val anyOverdue = updatedInstallments.any { it.status == InstallmentStatus.OVERDUE.name }
        val nextContractStatus = when {
            allPaid -> ContractStatus.SETTLED.name
            anyOverdue -> ContractStatus.OVERDUE.name
            else -> ContractStatus.ACTIVE.name
        }
        localDb.loanContractDao().upsert(
            contract.copy(
                status = nextContractStatus,
                updatedAtEpoch = now,
                syncedToCloud = false
            )
        )
    }

    /**
     * Evaluates overdue installments across active contracts, applying capped late fees once past graceDays.
     */
    suspend fun evaluateOverdueForUser(userId: String): Int = withContext(Dispatchers.IO) {
        val today = IsoDate.todayIso()
        val now = System.currentTimeMillis()
        var overdueCount = 0

        val activeContracts = localDb.loanContractDao().getActiveContracts(userId)
        for (contract in activeContracts) {
            val installments = localDb.installmentDao().getForContract(contract.id, userId)
            var changed = false
            var hasOverdue = false
            val updated = installments.map { inst ->
                if (inst.status == InstallmentStatus.PAID.name) {
                    inst
                } else {
                    val daysPastDue = IsoDate.daysBetween(inst.dueDateIso, today)
                    if (daysPastDue > contract.graceDays) {
                        hasOverdue = true
                        overdueCount++
                        val baseUnpaid = (inst.principalDueMinor + inst.interestDueMinor - inst.paidMinor).coerceAtLeast(0L)
                        val fee = if (inst.lateFeeDueMinor == 0L && contract.lateFeeBps > 0) {
                            FinancialCalculator.computeLateFee(baseUnpaid, contract.lateFeeBps, contract.lateFeeCapMinor)
                        } else inst.lateFeeDueMinor
                        if (inst.status != InstallmentStatus.OVERDUE.name || fee != inst.lateFeeDueMinor) {
                            changed = true
                            inst.copy(
                                lateFeeDueMinor = fee,
                                status = InstallmentStatus.OVERDUE.name,
                                updatedAtEpoch = now,
                                syncedToCloud = false
                            )
                        } else inst
                    } else inst
                }
            }
            if (changed) {
                localDb.installmentDao().upsertAll(updated)
            }
            val expectedStatus = if (hasOverdue) ContractStatus.OVERDUE.name else ContractStatus.ACTIVE.name
            if (contract.status != expectedStatus && contract.status != ContractStatus.SETTLED.name) {
                localDb.loanContractDao().upsert(
                    contract.copy(status = expectedStatus, updatedAtEpoch = now, syncedToCloud = false)
                )
            }
        }
        overdueCount
    }

    /**
     * Idempotent recurring auto-logger invoked by WorkManager.
     * Uses deterministic IDs `autolog_{contractId}_{installmentNumber}` so multiple devices never duplicate logs.
     */
    suspend fun processRecurringAutoLogs(userId: String): Int = withContext(Dispatchers.IO) {
        val today = IsoDate.todayIso()
        val autoContracts = localDb.loanContractDao().getAutoLogContracts(userId)
        var logged = 0
        for (contract in autoContracts) {
            val installments = localDb.installmentDao().getForContract(contract.id, userId)
            for (inst in installments) {
                if (inst.status != InstallmentStatus.PAID.name && inst.dueDateIso <= today) {
                    val dueAmount = (inst.principalDueMinor + inst.interestDueMinor + inst.lateFeeDueMinor - inst.paidMinor)
                        .coerceAtLeast(0L)
                    if (dueAmount > 0L) {
                        val deterministicId = "autolog_${contract.id}_i${inst.installmentNumber}"
                        val existing = localDb.paymentTransactionDao().getById(deterministicId, userId)
                        if (existing == null) {
                            val res = recordPayment(
                                userId = userId,
                                contractId = contract.id,
                                amountMinor = dueAmount,
                                paidDateIso = today,
                                note = "Auto-logged recurring installment #${inst.installmentNumber}",
                                proofUri = null,
                                deterministicId = deterministicId,
                                autoLogged = true
                            )
                            if (res.isSuccess) logged++
                        }
                    }
                }
            }
        }
        logged
    }

    suspend fun exportJsonBackup(userId: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val policy = localDb.adminPolicyDao().getPolicy(userId)?.let {
                BackupPolicy(
                    id = it.id,
                    defaultInterestMode = it.defaultInterestMode,
                    allowZeroInterest = it.allowZeroInterest,
                    allowSimpleFlat = it.allowSimpleFlat,
                    allowAmortizedMonthly = it.allowAmortizedMonthly,
                    defaultRateBps = it.defaultRateBps,
                    defaultGraceDays = it.defaultGraceDays,
                    defaultLateFeeBps = it.defaultLateFeeBps,
                    defaultLateFeeCapMinor = it.defaultLateFeeCapMinor,
                    paymentAllocationOrder = it.paymentAllocationOrder,
                    defaultCurrencyCode = it.defaultCurrencyCode,
                    defaultAutoLogRecurring = it.defaultAutoLogRecurring
                )
            }
            val cps = localDb.counterpartyDao().getAllSnapshot(userId).map {
                BackupCounterparty(it.id, it.name, it.phone, it.email, it.whatsapp, it.notes)
            }
            val ctrs = localDb.loanContractDao().getAllSnapshot(userId).map {
                BackupContract(
                    it.id, it.counterpartyId, it.direction, it.title, it.principalMinor,
                    it.currencyCode, it.startDateIso, it.termCount, it.frequency,
                    it.interestMode, it.interestRateBps, it.graceDays, it.lateFeeBps,
                    it.lateFeeCapMinor, it.paymentAllocationOrder, it.autoLogRecurring,
                    it.status, it.notes
                )
            }
            val insts = localDb.installmentDao().getAllSnapshot(userId).map {
                BackupInstallment(
                    it.id, it.contractId, it.installmentNumber, it.dueDateIso,
                    it.principalDueMinor, it.interestDueMinor, it.lateFeeDueMinor,
                    it.paidMinor, it.status
                )
            }
            val pays = localDb.paymentTransactionDao().getAllSnapshot(userId).map {
                BackupPayment(
                    it.id, it.contractId, it.installmentId, it.amountMinor,
                    it.paidDateIso, it.paidAtEpoch, it.autoLogged, it.note,
                    it.proofBase64, it.proofSha256
                )
            }
            val bundle = BackupBundle(
                exportedAtEpoch = System.currentTimeMillis(),
                sourceUid = userId,
                policy = policy,
                counterparties = cps,
                contracts = ctrs,
                installments = insts,
                payments = pays
            )
            val dir = File(appContext.filesDir, "backups").apply { mkdirs() }
            val file = File(dir, "pawledger_backup_${IsoDate.todayIso()}.json")
            file.writeText(json.encodeToString(bundle))
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun exportCsvLedger(userId: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val contracts = localDb.loanContractDao().getAllSnapshot(userId)
            val cps = localDb.counterpartyDao().getAllSnapshot(userId).associateBy { it.id }
            val sb = StringBuilder()
            sb.appendLine("ContractID,Title,Direction,Counterparty,Currency,Principal,StartDate,Terms,Frequency,InterestMode,RateBps,Status")
            for (c in contracts) {
                val cpName = cps[c.counterpartyId]?.name?.replace(",", " ") ?: "Unknown"
                val title = c.title.replace(",", " ")
                val principalStr = CurrencyFormatters.minorToEditableMajor(c.principalMinor, c.currencyCode)
                sb.appendLine("${c.id},\"$title\",${c.direction},\"$cpName\",${c.currencyCode},$principalStr,${c.startDateIso},${c.termCount},${c.frequency},${c.interestMode},${c.interestRateBps},${c.status}")
            }
            val dir = File(appContext.filesDir, "backups").apply { mkdirs() }
            val file = File(dir, "pawledger_ledger_${IsoDate.todayIso()}.csv")
            file.writeText(sb.toString())
            Result.success(file)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Path 1 of Super Admin Account Recovery Transfer:
     * Re-binds all local Room records (and private proof files) from oldUid -> newUid,
     * then pushes to Firestore in bounded 250-op chunks.
     */
    suspend fun executeLocalAccountRecoveryTransfer(
        previousUid: String,
        newUid: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        if (previousUid == newUid) {
            return@withContext Result.failure(IllegalArgumentException("Select a different Gmail account to transfer to."))
        }
        val sessionId = "rec_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        try {
            localDb.withTransaction {
                localDb.adminPolicyDao().rebindUser(previousUid, newUid)
                localDb.counterpartyDao().rebindUser(previousUid, newUid)
                localDb.loanContractDao().rebindUser(previousUid, newUid)
                localDb.installmentDao().rebindUser(previousUid, newUid)
                localDb.paymentTransactionDao().rebindUser(previousUid, newUid)
            }
            // Restore proof image files under newUid directory if needed
            val oldDir = File(appContext.filesDir, "proofs/$previousUid")
            val newDir = File(appContext.filesDir, "proofs/$newUid").apply { mkdirs() }
            if (oldDir.exists()) {
                oldDir.listFiles()?.forEach { f ->
                    f.copyTo(File(newDir, f.name), overwrite = true)
                }
            }
            syncRepo.syncRecoveredAccountInChunks(previousUid, newUid, sessionId, "LOCAL_REBIND")
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Path 2 of Super Admin Account Recovery Transfer:
     * Imports a user-exported JSON BackupBundle into the newly authenticated Gmail account
     * and syncs in bounded batches.
     */
    suspend fun restoreFromBackupBundleUri(
        targetUid: String,
        bundleUri: Uri
    ): Result<Int> = withContext(Dispatchers.IO) {
        val sessionId = "rec_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        try {
            val raw = appContext.contentResolver.openInputStream(bundleUri)?.bufferedReader()?.use { it.readText() }
                ?: return@withContext Result.failure(IllegalStateException("Unable to read backup file."))
            val bundle = json.decodeFromString<BackupBundle>(raw)
            val now = System.currentTimeMillis()

            localDb.withTransaction {
                bundle.policy?.let { p ->
                    localDb.adminPolicyDao().upsert(
                        AdminPolicyEntity(
                            id = p.id,
                            userId = targetUid,
                            defaultInterestMode = p.defaultInterestMode,
                            allowZeroInterest = p.allowZeroInterest,
                            allowSimpleFlat = p.allowSimpleFlat,
                            allowAmortizedMonthly = p.allowAmortizedMonthly,
                            defaultRateBps = p.defaultRateBps,
                            defaultGraceDays = p.defaultGraceDays,
                            defaultLateFeeBps = p.defaultLateFeeBps,
                            defaultLateFeeCapMinor = p.defaultLateFeeCapMinor,
                            paymentAllocationOrder = p.paymentAllocationOrder,
                            defaultCurrencyCode = p.defaultCurrencyCode,
                            defaultAutoLogRecurring = p.defaultAutoLogRecurring,
                            updatedAtEpoch = now,
                            cloudCreated = false,
                            syncedToCloud = false
                        )
                    )
                }
                localDb.counterpartyDao().upsertAll(
                    bundle.counterparties.map { cp ->
                        CounterpartyEntity(
                            id = cp.id,
                            userId = targetUid,
                            name = cp.name,
                            phone = cp.phone,
                            email = cp.email,
                            whatsapp = cp.whatsapp,
                            notes = cp.notes,
                            createdAtEpoch = now,
                            updatedAtEpoch = now,
                            cloudCreated = false,
                            syncedToCloud = false
                        )
                    }
                )
                localDb.loanContractDao().upsertAll(
                    bundle.contracts.map { c ->
                        LoanContractEntity(
                            id = c.id,
                            userId = targetUid,
                            counterpartyId = c.counterpartyId,
                            direction = c.direction,
                            title = c.title,
                            principalMinor = c.principalMinor,
                            currencyCode = c.currencyCode,
                            startDateIso = c.startDateIso,
                            termCount = c.termCount,
                            frequency = c.frequency,
                            interestMode = c.interestMode,
                            interestRateBps = c.interestRateBps,
                            graceDays = c.graceDays,
                            lateFeeBps = c.lateFeeBps,
                            lateFeeCapMinor = c.lateFeeCapMinor,
                            paymentAllocationOrder = c.paymentAllocationOrder,
                            autoLogRecurring = c.autoLogRecurring,
                            status = c.status,
                            notes = c.notes,
                            createdAtEpoch = now,
                            updatedAtEpoch = now,
                            cloudCreated = false,
                            syncedToCloud = false
                        )
                    }
                )
                localDb.installmentDao().upsertAll(
                    bundle.installments.map { i ->
                        InstallmentEntity(
                            id = i.id,
                            userId = targetUid,
                            contractId = i.contractId,
                            installmentNumber = i.installmentNumber,
                            dueDateIso = i.dueDateIso,
                            principalDueMinor = i.principalDueMinor,
                            interestDueMinor = i.interestDueMinor,
                            lateFeeDueMinor = i.lateFeeDueMinor,
                            paidMinor = i.paidMinor,
                            status = i.status,
                            updatedAtEpoch = now,
                            cloudCreated = false,
                            syncedToCloud = false
                        )
                    }
                )
                val restoredPayments = bundle.payments.map { p ->
                    val localProof = if (!p.proofBase64.isNullOrBlank()) {
                        ProofImageProcessor.restoreFromBase64IfValid(
                            appContext, targetUid, p.id, p.proofBase64, p.proofSha256
                        )
                    } else null
                    PaymentTransactionEntity(
                        id = p.id,
                        userId = targetUid,
                        contractId = p.contractId,
                        installmentId = p.installmentId,
                        amountMinor = p.amountMinor,
                        paidDateIso = p.paidDateIso,
                        paidAtEpoch = p.paidAtEpoch,
                        autoLogged = p.autoLogged,
                        note = p.note,
                        proofLocalUri = localProof,
                        proofBase64 = p.proofBase64,
                        proofSha256 = p.proofSha256,
                        createdAtEpoch = now,
                        updatedAtEpoch = now,
                        cloudCreated = false,
                        syncedToCloud = false
                    )
                }
                localDb.paymentTransactionDao().upsertAll(restoredPayments)
            }

            syncRepo.syncRecoveredAccountInChunks(
                previousUid = bundle.sourceUid.ifBlank { "backup_bundle" },
                newUid = targetUid,
                transferSessionId = sessionId,
                transferMode = "BACKUP_BUNDLE"
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
