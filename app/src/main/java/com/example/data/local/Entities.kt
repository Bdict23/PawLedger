package com.example.data.local

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "admin_policies",
    indices = [
        Index(value = ["userId"])
    ]
)
data class AdminPolicyEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val defaultInterestMode: String = "SIMPLE_FLAT",
    val allowZeroInterest: Boolean = true,
    val allowSimpleFlat: Boolean = true,
    val allowAmortizedMonthly: Boolean = true,
    val defaultRateBps: Int = 500,
    val defaultGraceDays: Int = 3,
    val defaultLateFeeBps: Int = 200,
    val defaultLateFeeCapMinor: Long = 2500L,
    val paymentAllocationOrder: String = "FEES_INTEREST_PRINCIPAL",
    val defaultCurrencyCode: String = "USD",
    val defaultAutoLogRecurring: Boolean = false,
    val encryptedPayload: String? = null,
    val updatedAtEpoch: Long = System.currentTimeMillis(),
    val cloudCreated: Boolean = false,
    val syncedToCloud: Boolean = false
)

@Entity(
    tableName = "counterparties",
    indices = [
        Index(value = ["userId", "name"]),
        Index(value = ["userId", "deletedLocally"])
    ]
)
data class CounterpartyEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val name: String,
    val phone: String = "",
    val email: String = "",
    val whatsapp: String = "",
    val notes: String = "",
    val encryptedPayload: String? = null,
    val createdAtEpoch: Long = System.currentTimeMillis(),
    val updatedAtEpoch: Long = System.currentTimeMillis(),
    val cloudCreated: Boolean = false,
    val syncedToCloud: Boolean = false,
    val deletedLocally: Boolean = false
)

@Entity(
    tableName = "loan_contracts",
    indices = [
        Index(value = ["userId", "direction", "status"]),
        Index(value = ["userId", "counterpartyId"]),
        Index(value = ["userId", "deletedLocally"])
    ]
)
data class LoanContractEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val counterpartyId: String,
    val direction: String, // LENT | BORROWED
    val title: String,
    val principalMinor: Long,
    val currencyCode: String,
    val startDateIso: String,
    val termCount: Int,
    val frequency: String, // WEEKLY | BIWEEKLY | MONTHLY
    val interestMode: String, // ZERO_INTEREST | SIMPLE_FLAT | AMORTIZED_MONTHLY
    val interestRateBps: Int,
    val graceDays: Int,
    val lateFeeBps: Int,
    val lateFeeCapMinor: Long,
    val paymentAllocationOrder: String, // FEES_INTEREST_PRINCIPAL | PRINCIPAL_FIRST
    val autoLogRecurring: Boolean,
    val status: String, // ACTIVE | OVERDUE | SETTLED | DEFAULTED
    val notes: String = "",
    val encryptedPayload: String? = null,
    val createdAtEpoch: Long = System.currentTimeMillis(),
    val updatedAtEpoch: Long = System.currentTimeMillis(),
    val cloudCreated: Boolean = false,
    val syncedToCloud: Boolean = false,
    val deletedLocally: Boolean = false
)

@Entity(
    tableName = "installments",
    foreignKeys = [
        ForeignKey(
            entity = LoanContractEntity::class,
            parentColumns = ["id"],
            childColumns = ["contractId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId", "dueDateIso", "status"]),
        Index(value = ["contractId", "installmentNumber"]),
        Index(value = ["contractId", "dueDateIso", "status"])
    ]
)
data class InstallmentEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val contractId: String,
    val installmentNumber: Int,
    val dueDateIso: String,
    val principalDueMinor: Long,
    val interestDueMinor: Long,
    val lateFeeDueMinor: Long = 0L,
    val paidMinor: Long = 0L,
    val status: String = "PENDING", // PENDING | PARTIAL | PAID | OVERDUE
    val updatedAtEpoch: Long = System.currentTimeMillis(),
    val cloudCreated: Boolean = false,
    val syncedToCloud: Boolean = false
)

@Entity(
    tableName = "payment_transactions",
    foreignKeys = [
        ForeignKey(
            entity = LoanContractEntity::class,
            parentColumns = ["id"],
            childColumns = ["contractId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId", "paidAtEpoch"]),
        Index(value = ["contractId", "paidAtEpoch"])
    ]
)
data class PaymentTransactionEntity(
    @PrimaryKey val id: String,
    val userId: String,
    val contractId: String,
    val installmentId: String? = null,
    val amountMinor: Long,
    val paidDateIso: String,
    val paidAtEpoch: Long,
    val autoLogged: Boolean = false,
    val note: String = "",
    val proofLocalUri: String? = null,
    val proofBase64: String? = null,
    val proofSha256: String? = null,
    val encryptedPayload: String? = null,
    val createdAtEpoch: Long = System.currentTimeMillis(),
    val updatedAtEpoch: Long = System.currentTimeMillis(),
    val cloudCreated: Boolean = false,
    val syncedToCloud: Boolean = false
)

@Entity(
    tableName = "recovery_transfer_logs",
    indices = [
        Index(value = ["newUid", "transferredAtEpoch"])
    ]
)
data class RecoveryTransferLogEntity(
    @PrimaryKey val id: String,
    val transferSessionId: String,
    val previousUid: String,
    val newUid: String,
    val transferredAtEpoch: Long,
    val recordsTransferredCount: Int,
    val transferMode: String,
    val status: String // IN_PROGRESS | COMPLETED | FAILED
)
