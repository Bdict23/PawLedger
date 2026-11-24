package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        AdminPolicyEntity::class,
        CounterpartyEntity::class,
        LoanContractEntity::class,
        InstallmentEntity::class,
        PaymentTransactionEntity::class,
        RecoveryTransferLogEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PawLedgerDatabase : RoomDatabase() {
    abstract fun adminPolicyDao(): AdminPolicyDao
    abstract fun counterpartyDao(): CounterpartyDao
    abstract fun loanContractDao(): LoanContractDao
    abstract fun installmentDao(): InstallmentDao
    abstract fun paymentTransactionDao(): PaymentTransactionDao
    abstract fun recoveryTransferLogDao(): RecoveryTransferLogDao

    companion object {
        @Volatile
        private var INSTANCE: PawLedgerDatabase? = null

        fun getInstance(context: Context): PawLedgerDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    PawLedgerDatabase::class.java,
                    "pawledger.db"
                )
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
