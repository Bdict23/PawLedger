package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class ContractSummaryRow(
    val id: String,
    val userId: String,
    val counterpartyId: String,
    val counterpartyName: String,
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
    val notes: String,
    val encryptedPayload: String?,
    val totalScheduledMinor: Long,
    val totalRepaidMinor: Long,
    val nextDueDateIso: String?,
    val nextDueAmountMinor: Long,
    val overdueInstallmentsCount: Int,
    val paidInstallmentsCount: Int,
    val updatedAtEpoch: Long
) {
    val remainingMinor: Long get() = (totalScheduledMinor - totalRepaidMinor).coerceAtLeast(0L)
}

data class UpcomingInstallmentRow(
    val installmentId: String,
    val contractId: String,
    val contractTitle: String,
    val counterpartyId: String,
    val counterpartyName: String,
    val direction: String,
    val currencyCode: String,
    val installmentNumber: Int,
    val totalTerms: Int,
    val dueDateIso: String,
    val graceDays: Int,
    val dueTotalMinor: Long,
    val paidMinor: Long,
    val status: String,
    val autoLogRecurring: Boolean
) {
    val outstandingMinor: Long get() = (dueTotalMinor - paidMinor).coerceAtLeast(0L)
}

@Dao
interface AdminPolicyDao {
    @Query("SELECT * FROM admin_policies WHERE userId = :userId LIMIT 1")
    fun observePolicy(userId: String): Flow<AdminPolicyEntity?>

    @Query("SELECT * FROM admin_policies WHERE userId = :userId LIMIT 1")
    suspend fun getPolicy(userId: String): AdminPolicyEntity?

    @Upsert
    suspend fun upsert(policy: AdminPolicyEntity)

    @Query("SELECT * FROM admin_policies WHERE userId = :userId AND syncedToCloud = 0")
    suspend fun getUnsynced(userId: String): List<AdminPolicyEntity>

    @Query("UPDATE admin_policies SET cloudCreated = 1, syncedToCloud = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE admin_policies SET userId = :newUid, cloudCreated = 0, syncedToCloud = 0 WHERE userId = :oldUid")
    suspend fun rebindUser(oldUid: String, newUid: String): Int
}

@Dao
interface CounterpartyDao {
    @Query("SELECT * FROM counterparties WHERE userId = :userId AND deletedLocally = 0 ORDER BY name ASC")
    fun observeAll(userId: String): Flow<List<CounterpartyEntity>>

    @Query("SELECT * FROM counterparties WHERE userId = :userId AND deletedLocally = 0")
    suspend fun getAllSnapshot(userId: String): List<CounterpartyEntity>

    @Query("SELECT * FROM counterparties WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getById(id: String, userId: String): CounterpartyEntity?

    @Upsert
    suspend fun upsert(entity: CounterpartyEntity)

    @Upsert
    suspend fun upsertAll(entities: List<CounterpartyEntity>)

    @Query("UPDATE counterparties SET deletedLocally = 1, syncedToCloud = 0, updatedAtEpoch = :now WHERE id = :id AND userId = :userId")
    suspend fun softDelete(id: String, userId: String, now: Long)

    @Query("SELECT * FROM counterparties WHERE userId = :userId AND syncedToCloud = 0")
    suspend fun getUnsynced(userId: String): List<CounterpartyEntity>

    @Query("UPDATE counterparties SET cloudCreated = 1, syncedToCloud = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE counterparties SET userId = :newUid, cloudCreated = 0, syncedToCloud = 0 WHERE userId = :oldUid")
    suspend fun rebindUser(oldUid: String, newUid: String): Int
}

@Dao
interface LoanContractDao {
    @Query(
        """
        SELECT 
            c.id AS id,
            c.userId AS userId,
            c.counterpartyId AS counterpartyId,
            COALESCE(cp.name, 'Unknown') AS counterpartyName,
            c.direction AS direction,
            c.title AS title,
            c.principalMinor AS principalMinor,
            c.currencyCode AS currencyCode,
            c.startDateIso AS startDateIso,
            c.termCount AS termCount,
            c.frequency AS frequency,
            c.interestMode AS interestMode,
            c.interestRateBps AS interestRateBps,
            c.graceDays AS graceDays,
            c.lateFeeBps AS lateFeeBps,
            c.lateFeeCapMinor AS lateFeeCapMinor,
            c.paymentAllocationOrder AS paymentAllocationOrder,
            c.autoLogRecurring AS autoLogRecurring,
            c.status AS status,
            c.notes AS notes,
            c.encryptedPayload AS encryptedPayload,
            COALESCE((SELECT SUM(i.principalDueMinor + i.interestDueMinor + i.lateFeeDueMinor) FROM installments i WHERE i.contractId = c.id), c.principalMinor) AS totalScheduledMinor,
            COALESCE((SELECT SUM(p.amountMinor) FROM payment_transactions p WHERE p.contractId = c.id), 0) AS totalRepaidMinor,
            (SELECT MIN(i.dueDateIso) FROM installments i WHERE i.contractId = c.id AND i.status != 'PAID') AS nextDueDateIso,
            COALESCE((SELECT (i.principalDueMinor + i.interestDueMinor + i.lateFeeDueMinor - i.paidMinor) FROM installments i WHERE i.contractId = c.id AND i.status != 'PAID' ORDER BY i.dueDateIso ASC LIMIT 1), 0) AS nextDueAmountMinor,
            COALESCE((SELECT COUNT(*) FROM installments i WHERE i.contractId = c.id AND i.status = 'OVERDUE'), 0) AS overdueInstallmentsCount,
            COALESCE((SELECT COUNT(*) FROM installments i WHERE i.contractId = c.id AND i.status = 'PAID'), 0) AS paidInstallmentsCount,
            c.updatedAtEpoch AS updatedAtEpoch
        FROM loan_contracts c
        LEFT JOIN counterparties cp ON c.counterpartyId = cp.id
        WHERE c.userId = :userId AND c.deletedLocally = 0
        ORDER BY c.updatedAtEpoch DESC
        """
    )
    fun observeContractSummaries(userId: String): Flow<List<ContractSummaryRow>>

    @Query("SELECT * FROM loan_contracts WHERE userId = :userId AND deletedLocally = 0")
    suspend fun getAllSnapshot(userId: String): List<LoanContractEntity>

    @Query("SELECT * FROM loan_contracts WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getById(id: String, userId: String): LoanContractEntity?

    @Query("SELECT * FROM loan_contracts WHERE userId = :userId AND deletedLocally = 0 AND autoLogRecurring = 1 AND status != 'SETTLED'")
    suspend fun getAutoLogContracts(userId: String): List<LoanContractEntity>

    @Query("SELECT * FROM loan_contracts WHERE userId = :userId AND deletedLocally = 0 AND status != 'SETTLED'")
    suspend fun getActiveContracts(userId: String): List<LoanContractEntity>

    @Upsert
    suspend fun upsert(contract: LoanContractEntity)

    @Upsert
    suspend fun upsertAll(contracts: List<LoanContractEntity>)

    @Query("UPDATE loan_contracts SET deletedLocally = 1, syncedToCloud = 0, updatedAtEpoch = :now WHERE id = :id AND userId = :userId")
    suspend fun softDelete(id: String, userId: String, now: Long)

    @Query("DELETE FROM loan_contracts WHERE id = :id AND userId = :userId")
    suspend fun hardDelete(id: String, userId: String)

    @Query("SELECT * FROM loan_contracts WHERE userId = :userId AND syncedToCloud = 0")
    suspend fun getUnsynced(userId: String): List<LoanContractEntity>

    @Query("UPDATE loan_contracts SET cloudCreated = 1, syncedToCloud = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE loan_contracts SET userId = :newUid, cloudCreated = 0, syncedToCloud = 0 WHERE userId = :oldUid")
    suspend fun rebindUser(oldUid: String, newUid: String): Int
}

@Dao
interface InstallmentDao {
    @Query("SELECT * FROM installments WHERE contractId = :contractId AND userId = :userId ORDER BY installmentNumber ASC")
    fun observeForContract(contractId: String, userId: String): Flow<List<InstallmentEntity>>

    @Query("SELECT * FROM installments WHERE contractId = :contractId AND userId = :userId ORDER BY installmentNumber ASC")
    suspend fun getForContract(contractId: String, userId: String): List<InstallmentEntity>

    @Query("SELECT * FROM installments WHERE userId = :userId")
    suspend fun getAllSnapshot(userId: String): List<InstallmentEntity>

    @Query(
        """
        SELECT 
            i.id AS installmentId,
            c.id AS contractId,
            c.title AS contractTitle,
            c.counterpartyId AS counterpartyId,
            COALESCE(cp.name, 'Unknown') AS counterpartyName,
            c.direction AS direction,
            c.currencyCode AS currencyCode,
            i.installmentNumber AS installmentNumber,
            c.termCount AS totalTerms,
            i.dueDateIso AS dueDateIso,
            c.graceDays AS graceDays,
            (i.principalDueMinor + i.interestDueMinor + i.lateFeeDueMinor) AS dueTotalMinor,
            i.paidMinor AS paidMinor,
            i.status AS status,
            c.autoLogRecurring AS autoLogRecurring
        FROM installments i
        INNER JOIN loan_contracts c ON i.contractId = c.id
        LEFT JOIN counterparties cp ON c.counterpartyId = cp.id
        WHERE i.userId = :userId AND c.deletedLocally = 0 AND c.status != 'SETTLED' AND i.status != 'PAID'
        ORDER BY i.dueDateIso ASC
        """
    )
    fun observeUpcomingOpenInstallments(userId: String): Flow<List<UpcomingInstallmentRow>>

    @Upsert
    suspend fun upsert(installment: InstallmentEntity)

    @Upsert
    suspend fun upsertAll(installments: List<InstallmentEntity>)

    @Query("SELECT * FROM installments WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getById(id: String, userId: String): InstallmentEntity?

    @Query("DELETE FROM installments WHERE contractId = :contractId AND userId = :userId AND installmentNumber > :maxInstallmentNumber")
    suspend fun deleteExtraInstallments(contractId: String, userId: String, maxInstallmentNumber: Int)

    @Query("DELETE FROM installments WHERE id = :id AND userId = :userId")
    suspend fun deleteById(id: String, userId: String)

    @Query("SELECT * FROM installments WHERE userId = :userId AND syncedToCloud = 0")
    suspend fun getUnsynced(userId: String): List<InstallmentEntity>

    @Query("UPDATE installments SET cloudCreated = 1, syncedToCloud = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE installments SET userId = :newUid, cloudCreated = 0, syncedToCloud = 0 WHERE userId = :oldUid")
    suspend fun rebindUser(oldUid: String, newUid: String): Int
}

@Dao
interface PaymentTransactionDao {
    @Query("SELECT * FROM payment_transactions WHERE contractId = :contractId AND userId = :userId ORDER BY paidAtEpoch DESC")
    fun observeForContract(contractId: String, userId: String): Flow<List<PaymentTransactionEntity>>

    @Query("SELECT * FROM payment_transactions WHERE userId = :userId ORDER BY paidAtEpoch DESC")
    fun observeAllForUser(userId: String): Flow<List<PaymentTransactionEntity>>

    @Query("SELECT * FROM payment_transactions WHERE contractId = :contractId AND userId = :userId ORDER BY paidDateIso ASC, paidAtEpoch ASC")
    suspend fun getForContract(contractId: String, userId: String): List<PaymentTransactionEntity>

    @Query("SELECT * FROM payment_transactions WHERE userId = :userId")
    suspend fun getAllSnapshot(userId: String): List<PaymentTransactionEntity>

    @Query("SELECT * FROM payment_transactions WHERE id = :id AND userId = :userId LIMIT 1")
    suspend fun getById(id: String, userId: String): PaymentTransactionEntity?

    @Query("DELETE FROM payment_transactions WHERE id = :id AND userId = :userId")
    suspend fun deleteById(id: String, userId: String)

    @Upsert
    suspend fun upsert(payment: PaymentTransactionEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(payment: PaymentTransactionEntity): Long

    @Upsert
    suspend fun upsertAll(payments: List<PaymentTransactionEntity>)

    @Query("SELECT * FROM payment_transactions WHERE userId = :userId AND syncedToCloud = 0")
    suspend fun getUnsynced(userId: String): List<PaymentTransactionEntity>

    @Query("UPDATE payment_transactions SET cloudCreated = 1, syncedToCloud = 1 WHERE id = :id")
    suspend fun markSynced(id: String)

    @Query("UPDATE payment_transactions SET userId = :newUid, cloudCreated = 0, syncedToCloud = 0 WHERE userId = :oldUid")
    suspend fun rebindUser(oldUid: String, newUid: String): Int
}

@Dao
interface RecoveryTransferLogDao {
    @Query("SELECT * FROM recovery_transfer_logs WHERE newUid = :uid OR previousUid = :uid ORDER BY transferredAtEpoch DESC")
    fun observeLogs(uid: String): Flow<List<RecoveryTransferLogEntity>>

    @Upsert
    suspend fun upsert(log: RecoveryTransferLogEntity)
}
