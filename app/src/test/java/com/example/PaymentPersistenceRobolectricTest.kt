package com.example

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.CounterpartyEntity
import com.example.data.local.InstallmentEntity
import com.example.data.local.LoanContractEntity
import com.example.data.local.PawLedgerDatabase
import com.example.data.local.PaymentTransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PaymentPersistenceRobolectricTest {

    private lateinit var db: PawLedgerDatabase

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, PawLedgerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun upsertingContractAfterRecordingPaymentPreservesPaymentsAndInstallments() = runBlocking {
        val uid = "uid_test_1"
        val cpId = "cp_1"
        val contractId = "ctr_1"

        db.counterpartyDao().upsert(
            CounterpartyEntity(id = cpId, userId = uid, name = "Alex Morgan")
        )
        val contract = LoanContractEntity(
            id = contractId,
            userId = uid,
            counterpartyId = cpId,
            direction = "LENT",
            title = "15-30 Salary Loan",
            principalMinor = 100_00L,
            currencyCode = "PHP",
            startDateIso = "2026-11-10",
            termCount = 2,
            frequency = "SEMI_MONTHLY_15_30",
            interestMode = "ZERO_INTEREST",
            interestRateBps = 0,
            graceDays = 3,
            lateFeeBps = 0,
            lateFeeCapMinor = 0L,
            paymentAllocationOrder = "FEES_INTEREST_PRINCIPAL",
            autoLogRecurring = false,
            status = "ACTIVE"
        )
        val installments = listOf(
            InstallmentEntity(
                id = "${contractId}_i1",
                userId = uid,
                contractId = contractId,
                installmentNumber = 1,
                dueDateIso = "2026-11-15",
                principalDueMinor = 50_00L,
                interestDueMinor = 0L
            ),
            InstallmentEntity(
                id = "${contractId}_i2",
                userId = uid,
                contractId = contractId,
                installmentNumber = 2,
                dueDateIso = "2026-11-30",
                principalDueMinor = 50_00L,
                interestDueMinor = 0L
            )
        )

        db.withTransaction {
            db.loanContractDao().upsert(contract)
            db.installmentDao().upsertAll(installments)
        }

        val payment = PaymentTransactionEntity(
            id = "pay_1",
            userId = uid,
            contractId = contractId,
            installmentId = "${contractId}_i1",
            amountMinor = 50_00L,
            paidDateIso = "2026-11-15",
            paidAtEpoch = 1_794_700_800_000L,
            note = "First 15-30 payment"
        )
        val updatedInstallments = listOf(
            installments[0].copy(paidMinor = 50_00L, status = "PAID"),
            installments[1]
        )

        db.withTransaction {
            db.paymentTransactionDao().insertIfAbsent(payment)
            db.installmentDao().upsertAll(updatedInstallments)
            db.loanContractDao().upsert(contract.copy(status = "ACTIVE", syncedToCloud = false))
        }

        val savedPayments = db.paymentTransactionDao().observeForContract(contractId, uid).first()
        val savedInstallments = db.installmentDao().getForContract(contractId, uid)
        val summary = db.loanContractDao().observeContractSummaries(uid).first().first()

        assertEquals(1, savedPayments.size)
        assertEquals(50_00L, savedPayments.first().amountMinor)
        assertEquals(2, savedInstallments.size)
        assertEquals("PAID", savedInstallments[0].status)
        assertEquals(50_00L, summary.totalRepaidMinor)
        assertEquals(50_00L, summary.remainingMinor)
    }

    @Test
    fun editScheduleShrinkingTermsAndDeletingPaymentAndContractCascadesCleanly() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = com.example.data.local.UserPreferencesStore(context)
        val uid = "uid_test_edit_del"
        val cpId = "cp_2"
        val contractId = "ctr_2"

        db.counterpartyDao().upsert(
            CounterpartyEntity(id = cpId, userId = uid, name = "Jamie Taylor")
        )
        val contract = LoanContractEntity(
            id = contractId,
            userId = uid,
            counterpartyId = cpId,
            direction = "BORROWED",
            title = "Studio Gear",
            principalMinor = 300_00L,
            currencyCode = "USD",
            startDateIso = "2026-11-01",
            termCount = 3,
            frequency = "MONTHLY",
            interestMode = "ZERO_INTEREST",
            interestRateBps = 0,
            graceDays = 3,
            lateFeeBps = 0,
            lateFeeCapMinor = 0L,
            paymentAllocationOrder = "FEES_INTEREST_PRINCIPAL",
            autoLogRecurring = false,
            status = "ACTIVE"
        )
        val initialInstallments = (1..3).map { num ->
            InstallmentEntity(
                id = "${contractId}_i$num",
                userId = uid,
                contractId = contractId,
                installmentNumber = num,
                dueDateIso = "2026-12-0$num",
                principalDueMinor = 100_00L,
                interestDueMinor = 0L
            )
        }
        val payment1 = PaymentTransactionEntity(
            id = "pay_e1",
            userId = uid,
            contractId = contractId,
            installmentId = "${contractId}_i1",
            amountMinor = 100_00L,
            paidDateIso = "2026-11-05",
            paidAtEpoch = 1_793_836_800_000L,
            note = "Part 1",
            cloudCreated = true
        )
        val payment2 = PaymentTransactionEntity(
            id = "pay_e2",
            userId = uid,
            contractId = contractId,
            installmentId = "${contractId}_i2",
            amountMinor = 50_00L,
            paidDateIso = "2026-11-10",
            paidAtEpoch = 1_794_268_800_000L,
            note = "Part 2"
        )

        db.withTransaction {
            db.loanContractDao().upsert(contract)
            db.installmentDao().upsertAll(initialInstallments)
            db.paymentTransactionDao().insertIfAbsent(payment1)
            db.paymentTransactionDao().insertIfAbsent(payment2)
        }

        // Shrink termCount from 3 to 2 and delete payment2
        db.withTransaction {
            db.loanContractDao().upsert(contract.copy(termCount = 2, title = "Studio Gear Updated"))
            db.installmentDao().deleteExtraInstallments(contractId, uid, 2)
            db.paymentTransactionDao().deleteById("pay_e2", uid)
        }

        val remainingInstallments = db.installmentDao().getForContract(contractId, uid)
        val remainingPayments = db.paymentTransactionDao().getForContract(contractId, uid)
        val summaryAfterEdit = db.loanContractDao().observeContractSummaries(uid).first().first()

        assertEquals(2, remainingInstallments.size)
        assertEquals(1, remainingPayments.size)
        assertEquals("Studio Gear Updated", summaryAfterEdit.title)
        assertEquals(200_00L, summaryAfterEdit.totalScheduledMinor)
        assertEquals(100_00L, summaryAfterEdit.totalRepaidMinor)

        // Verify pending cloud delete queue tracks deleted cloud-synced items cleanly
        prefs.addPendingCloudDeletes(uid, listOf("payments" to "pay_e1", "installments" to "${contractId}_i3"))
        val pending = prefs.getPendingCloudDeletes(uid)
        assertEquals(2, pending.size)
        prefs.removePendingCloudDelete(uid, "payments", "pay_e1")
        assertEquals(listOf("installments" to "${contractId}_i3"), prefs.getPendingCloudDeletes(uid))

        // Hard delete contract should cascade-delete all remaining installments & payments
        db.loanContractDao().hardDelete(contractId, uid)
        assertEquals(0, db.installmentDao().getForContract(contractId, uid).size)
        assertEquals(0, db.paymentTransactionDao().getForContract(contractId, uid).size)
        assertEquals(0, db.loanContractDao().observeContractSummaries(uid).first().size)
    }
}
