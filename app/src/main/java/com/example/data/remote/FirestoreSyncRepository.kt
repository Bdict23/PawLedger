package com.example.data.remote

import android.content.Context
import com.example.data.local.AdminPolicyEntity
import com.example.data.local.CounterpartyEntity
import com.example.data.local.InstallmentEntity
import com.example.data.local.LoanContractEntity
import com.example.data.local.PawLedgerDatabase
import com.example.data.local.PaymentTransactionEntity
import com.example.data.local.RecoveryTransferLogEntity
import com.example.data.local.UserPreferencesStore
import com.example.security.EncryptionManager
import com.example.security.ProofImageProcessor
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject

class FirestoreSyncRepository(
    private val db: FirebaseFirestore,
    private val localDb: PawLedgerDatabase,
    private val encryptionManager: EncryptionManager,
    private val appContext: Context,
    private val auth: FirebaseAuth = Firebase.auth
) {
    private val prefsStore = UserPreferencesStore(appContext)
    private var listenerJobs: List<Job> = emptyList()
    private var activeListeningUid: String? = null

    private fun requireUserId(expectedUid: String): String {
        val current = auth.currentUser?.uid
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
        check(current == expectedUid) { "Signed-in user changed; refusing cross-user Firestore operation." }
        return current
    }

    fun startRealtimePull(userId: String, scope: CoroutineScope, onSyncError: (String) -> Unit) {
        if (userId.isBlank()) return
        if (activeListeningUid == userId && listenerJobs.any { it.isActive }) return
        stopListening()
        activeListeningUid = userId

        val userRef = db.collection("users").document(userId)

        val policyJob = userRef.collection("admin_policies")
            .snapshots()
            .onEach { snap ->
                for (doc in snap.documents) {
                    val entity = doc.toAdminPolicyEntity(userId) ?: continue
                    val local = localDb.adminPolicyDao().getPolicy(userId)
                    if (local == null || local.syncedToCloud || entity.updatedAtEpoch >= local.updatedAtEpoch) {
                        localDb.adminPolicyDao().upsert(entity)
                    }
                }
            }
            .catch { error -> handleListenerError(error, userId, "users/$userId/admin_policies", onSyncError) }
            .launchIn(scope)

        val cpJob = userRef.collection("counterparties")
            .snapshots()
            .onEach { snap ->
                val list = snap.documents.mapNotNull { it.toCounterpartyEntity(userId) }
                if (list.isNotEmpty()) {
                    localDb.counterpartyDao().upsertAll(list)
                }
            }
            .catch { error -> handleListenerError(error, userId, "users/$userId/counterparties", onSyncError) }
            .launchIn(scope)

        val contractsJob = userRef.collection("contracts")
            .snapshots()
            .onEach { snap ->
                val list = snap.documents.mapNotNull { it.toContractEntity(userId) }.filter { remote ->
                    localDb.loanContractDao().getById(remote.id, userId)?.deletedLocally != true
                }
                if (list.isNotEmpty()) {
                    localDb.loanContractDao().upsertAll(list)
                }
                if (!snap.metadata.hasPendingWrites()) {
                    val remoteIds = snap.documents.mapTo(HashSet()) { it.id }
                    localDb.loanContractDao().getAllSnapshot(userId)
                        .filter { it.cloudCreated && it.syncedToCloud && it.id !in remoteIds }
                        .forEach { localDb.loanContractDao().hardDelete(it.id, userId) }
                }
            }
            .catch { error -> handleListenerError(error, userId, "users/$userId/contracts", onSyncError) }
            .launchIn(scope)

        val instJob = userRef.collection("installments")
            .snapshots()
            .onEach { snap ->
                val list = snap.documents.mapNotNull { it.toInstallmentEntity(userId) }.filter { inst ->
                    localDb.loanContractDao().getById(inst.contractId, userId)?.deletedLocally != true
                }
                for (item in list) {
                    runCatching { localDb.installmentDao().upsert(item) }
                }
                if (!snap.metadata.hasPendingWrites()) {
                    val remoteIds = snap.documents.mapTo(HashSet()) { it.id }
                    localDb.installmentDao().getAllSnapshot(userId)
                        .filter { it.cloudCreated && it.syncedToCloud && it.id !in remoteIds }
                        .forEach { localDb.installmentDao().deleteById(it.id, userId) }
                }
            }
            .catch { error -> handleListenerError(error, userId, "users/$userId/installments", onSyncError) }
            .launchIn(scope)

        val payJob = userRef.collection("payments")
            .snapshots()
            .onEach { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    doc.toPaymentEntity(userId)
                }.filter { pay ->
                    localDb.loanContractDao().getById(pay.contractId, userId)?.deletedLocally != true
                }
                for (item in list) {
                    runCatching { localDb.paymentTransactionDao().upsert(item) }
                }
                if (!snap.metadata.hasPendingWrites()) {
                    val remoteIds = snap.documents.mapTo(HashSet()) { it.id }
                    localDb.paymentTransactionDao().getAllSnapshot(userId)
                        .filter { it.cloudCreated && it.syncedToCloud && it.id !in remoteIds }
                        .forEach { localDb.paymentTransactionDao().deleteById(it.id, userId) }
                }
            }
            .catch { error -> handleListenerError(error, userId, "users/$userId/payments", onSyncError) }
            .launchIn(scope)

        listenerJobs = listOf(policyJob, cpJob, contractsJob, instJob, payJob)
    }

    fun stopListening() {
        listenerJobs.forEach { it.cancel() }
        listenerJobs = emptyList()
        activeListeningUid = null
    }

    private fun handleListenerError(
        error: Throwable,
        uid: String,
        path: String,
        onSyncError: (String) -> Unit
    ) {
        val firestoreError = generateSequence(error) { it.cause }
            .filterIsInstance<FirebaseFirestoreException>()
            .firstOrNull()
        if (firestoreError == null && error is CancellationException) throw error
        val root = firestoreError ?: (error as? Exception)
        if (root != null && auth.currentUser?.uid == uid) {
            handleFirestoreError(root, OperationType.LIST, path)
            onSyncError(root.localizedMessage ?: "Cloud sync interrupted")
        }
    }

    suspend fun pushPendingChanges(userId: String): Result<Int> = withContext(Dispatchers.IO) {
        var currentPath = "users/$userId"
        try {
            val uid = requireUserId(userId)
            val userRef = db.collection("users").document(uid)
            var count = 0

            localDb.adminPolicyDao().getUnsynced(uid).forEach { policy ->
                val ref = userRef.collection("admin_policies").document(policy.id)
                currentPath = ref.path
                if (!policy.cloudCreated) {
                    ref.set(policy.toFirestoreCreateMap(uid)).await()
                } else {
                    ref.update(policy.toFirestoreUpdateMap()).await()
                }
                localDb.adminPolicyDao().markSynced(policy.id)
                count++
            }

            localDb.counterpartyDao().getUnsynced(uid).forEach { cp ->
                val ref = userRef.collection("counterparties").document(cp.id)
                currentPath = ref.path
                if (cp.deletedLocally && cp.cloudCreated) {
                    ref.delete().await()
                } else if (!cp.deletedLocally) {
                    if (!cp.cloudCreated) ref.set(cp.toFirestoreCreateMap(uid)).await()
                    else ref.update(cp.toFirestoreUpdateMap()).await()
                }
                localDb.counterpartyDao().markSynced(cp.id)
                count++
            }

            prefsStore.getPendingCloudDeletes(uid).forEach { (collection, docId) ->
                val ref = userRef.collection(collection).document(docId)
                currentPath = ref.path
                ref.delete().await()
                prefsStore.removePendingCloudDelete(uid, collection, docId)
                count++
            }

            localDb.loanContractDao().getUnsynced(uid).forEach { contract ->
                val ref = userRef.collection("contracts").document(contract.id)
                currentPath = ref.path
                if (contract.deletedLocally) {
                    val childInsts = userRef.collection("installments")
                        .whereEqualTo("contractId", contract.id)
                        .get()
                        .await()
                    val childPays = userRef.collection("payments")
                        .whereEqualTo("contractId", contract.id)
                        .get()
                        .await()
                    val refsToDelete = buildList {
                        childInsts.documents.forEach { add(it.reference) }
                        childPays.documents.forEach { add(it.reference) }
                        if (contract.cloudCreated) add(ref)
                    }
                    refsToDelete.chunked(250).forEach { chunk ->
                        val batch = db.batch()
                        chunk.forEach { batch.delete(it) }
                        batch.commit().await()
                    }
                    localDb.loanContractDao().hardDelete(contract.id, uid)
                    count++
                } else {
                    if (!contract.cloudCreated) ref.set(contract.toFirestoreCreateMap(uid)).await()
                    else ref.update(contract.toFirestoreUpdateMap()).await()
                    localDb.loanContractDao().markSynced(contract.id)
                    count++
                }
            }

            localDb.installmentDao().getUnsynced(uid).forEach { inst ->
                val ref = userRef.collection("installments").document(inst.id)
                currentPath = ref.path
                if (!inst.cloudCreated) ref.set(inst.toFirestoreCreateMap(uid)).await()
                else ref.update(inst.toFirestoreUpdateMap()).await()
                localDb.installmentDao().markSynced(inst.id)
                count++
            }

            localDb.paymentTransactionDao().getUnsynced(uid).forEach { pay ->
                val ref = userRef.collection("payments").document(pay.id)
                currentPath = ref.path
                if (!pay.cloudCreated) ref.set(pay.toFirestoreCreateMap(uid)).await()
                else ref.update(pay.toFirestoreUpdateMap()).await()
                localDb.paymentTransactionDao().markSynced(pay.id)
                count++
            }

            Result.success(count)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, currentPath)
            Result.failure(e)
        }
    }

    /**
     * Chunked, resumable Account Recovery Transfer uploading up to 250 docs per Firestore batch
     * after local records have been rebound to newUid, plus recording an immutable audit entry.
     */
    suspend fun syncRecoveredAccountInChunks(
        previousUid: String,
        newUid: String,
        transferSessionId: String,
        transferMode: String
    ): Result<Int> = withContext(Dispatchers.IO) {
        val auditRef = db.collection("users").document(newUid)
            .collection("recovery_audits").document(transferSessionId)
        try {
            requireUserId(newUid)
            auditRef.set(
                mapOf(
                    "userId" to newUid,
                    "transferSessionId" to transferSessionId,
                    "previousUid" to previousUid,
                    "newUid" to newUid,
                    "recordsTransferredCount" to 0,
                    "transferMode" to transferMode,
                    "status" to "IN_PROGRESS",
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            val userRef = db.collection("users").document(newUid)
            val ops = mutableListOf<Pair<com.google.firebase.firestore.DocumentReference, Map<String, Any>>>()

            localDb.adminPolicyDao().getUnsynced(newUid).forEach {
                ops += userRef.collection("admin_policies").document(it.id) to it.toFirestoreCreateMap(newUid)
            }
            localDb.counterpartyDao().getUnsynced(newUid).filter { !it.deletedLocally }.forEach {
                ops += userRef.collection("counterparties").document(it.id) to it.toFirestoreCreateMap(newUid)
            }
            localDb.loanContractDao().getUnsynced(newUid).filter { !it.deletedLocally }.forEach {
                ops += userRef.collection("contracts").document(it.id) to it.toFirestoreCreateMap(newUid)
            }
            localDb.installmentDao().getUnsynced(newUid).forEach {
                ops += userRef.collection("installments").document(it.id) to it.toFirestoreCreateMap(newUid)
            }
            localDb.paymentTransactionDao().getUnsynced(newUid).forEach {
                ops += userRef.collection("payments").document(it.id) to it.toFirestoreCreateMap(newUid)
            }

            var uploaded = 0
            ops.chunked(250).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { (ref, payload) -> batch.set(ref, payload) }
                batch.commit().await()
                uploaded += chunk.size
            }

            localDb.adminPolicyDao().getUnsynced(newUid).forEach { localDb.adminPolicyDao().markSynced(it.id) }
            localDb.counterpartyDao().getUnsynced(newUid).forEach { localDb.counterpartyDao().markSynced(it.id) }
            localDb.loanContractDao().getUnsynced(newUid).forEach { localDb.loanContractDao().markSynced(it.id) }
            localDb.installmentDao().getUnsynced(newUid).forEach { localDb.installmentDao().markSynced(it.id) }
            localDb.paymentTransactionDao().getUnsynced(newUid).forEach { localDb.paymentTransactionDao().markSynced(it.id) }

            auditRef.update(
                mapOf(
                    "recordsTransferredCount" to uploaded,
                    "status" to "COMPLETED",
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()

            localDb.recoveryTransferLogDao().upsert(
                RecoveryTransferLogEntity(
                    id = transferSessionId,
                    transferSessionId = transferSessionId,
                    previousUid = previousUid,
                    newUid = newUid,
                    transferredAtEpoch = System.currentTimeMillis(),
                    recordsTransferredCount = uploaded,
                    transferMode = transferMode,
                    status = "COMPLETED"
                )
            )
            Result.success(uploaded)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, auditRef.path)
            localDb.recoveryTransferLogDao().upsert(
                RecoveryTransferLogEntity(
                    id = transferSessionId,
                    transferSessionId = transferSessionId,
                    previousUid = previousUid,
                    newUid = newUid,
                    transferredAtEpoch = System.currentTimeMillis(),
                    recordsTransferredCount = 0,
                    transferMode = transferMode,
                    status = "FAILED"
                )
            )
            Result.failure(e)
        }
    }

    // --- Serialization helpers preserving 1:1 parity with firestore.rules ---

    private fun AdminPolicyEntity.toFirestoreCreateMap(uid: String): Map<String, Any> =
        (mapOf(
            "userId" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        ) + toFirestoreUpdateMap()).filterValues { it != null } as Map<String, Any>

    private fun AdminPolicyEntity.toFirestoreUpdateMap(): Map<String, Any> =
        mapOf(
            "defaultInterestMode" to defaultInterestMode,
            "allowZeroInterest" to allowZeroInterest,
            "allowSimpleFlat" to allowSimpleFlat,
            "allowAmortizedMonthly" to allowAmortizedMonthly,
            "defaultRateBps" to defaultRateBps,
            "defaultGraceDays" to defaultGraceDays,
            "defaultLateFeeBps" to defaultLateFeeBps,
            "defaultLateFeeCapMinor" to defaultLateFeeCapMinor,
            "paymentAllocationOrder" to paymentAllocationOrder,
            "defaultCurrencyCode" to defaultCurrencyCode,
            "defaultAutoLogRecurring" to defaultAutoLogRecurring,
            "encryptedPayload" to encryptedPayload,
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>

    private fun CounterpartyEntity.toFirestoreCreateMap(uid: String): Map<String, Any> =
        (mapOf(
            "userId" to uid,
            "createdAt" to FieldValue.serverTimestamp()
        ) + toFirestoreUpdateMap()).filterValues { it != null } as Map<String, Any>

    private fun CounterpartyEntity.toFirestoreUpdateMap(): Map<String, Any> {
        val sensitiveCipher = if (encryptionManager.isUnlocked()) {
            val json = JSONObject().apply {
                put("phone", phone)
                put("email", email)
                put("whatsapp", whatsapp)
                put("notes", notes)
            }.toString()
            encryptionManager.encryptString(json)
        } else encryptedPayload

        val useCipher = sensitiveCipher != null
        return mapOf(
            "name" to name,
            "phone" to if (useCipher) null else phone.takeIf { it.isNotBlank() },
            "email" to if (useCipher) null else email.takeIf { it.isNotBlank() },
            "whatsapp" to if (useCipher) null else whatsapp.takeIf { it.isNotBlank() },
            "notes" to if (useCipher) null else notes.takeIf { it.isNotBlank() },
            "encryptedPayload" to sensitiveCipher,
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    private fun LoanContractEntity.toFirestoreCreateMap(uid: String): Map<String, Any> {
        val sensitiveCipher = if (encryptionManager.isUnlocked() && notes.isNotBlank()) {
            encryptionManager.encryptString(JSONObject().apply { put("notes", notes) }.toString())
        } else encryptedPayload
        val useCipher = sensitiveCipher != null
        return mapOf(
            "userId" to uid,
            "counterpartyId" to counterpartyId,
            "direction" to direction,
            "title" to title,
            "principalMinor" to principalMinor,
            "currencyCode" to currencyCode,
            "startDateIso" to startDateIso,
            "termCount" to termCount,
            "frequency" to frequency,
            "interestMode" to interestMode,
            "interestRateBps" to interestRateBps,
            "graceDays" to graceDays,
            "lateFeeBps" to lateFeeBps,
            "lateFeeCapMinor" to lateFeeCapMinor,
            "paymentAllocationOrder" to paymentAllocationOrder,
            "autoLogRecurring" to autoLogRecurring,
            "status" to status,
            "notes" to if (useCipher) null else notes.takeIf { it.isNotBlank() },
            "encryptedPayload" to sensitiveCipher,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    private fun LoanContractEntity.toFirestoreUpdateMap(): Map<String, Any> {
        val sensitiveCipher = if (encryptionManager.isUnlocked() && notes.isNotBlank()) {
            encryptionManager.encryptString(JSONObject().apply { put("notes", notes) }.toString())
        } else null
        val useCipher = sensitiveCipher != null
        return mapOf(
            "counterpartyId" to counterpartyId,
            "direction" to direction,
            "title" to title,
            "principalMinor" to principalMinor,
            "currencyCode" to currencyCode,
            "startDateIso" to startDateIso,
            "termCount" to termCount,
            "frequency" to frequency,
            "interestMode" to interestMode,
            "interestRateBps" to interestRateBps,
            "graceDays" to graceDays,
            "lateFeeBps" to lateFeeBps,
            "lateFeeCapMinor" to lateFeeCapMinor,
            "paymentAllocationOrder" to paymentAllocationOrder,
            "autoLogRecurring" to autoLogRecurring,
            "status" to status,
            "notes" to if (useCipher || notes.isBlank()) FieldValue.delete() else notes,
            "encryptedPayload" to (sensitiveCipher ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    private fun InstallmentEntity.toFirestoreCreateMap(uid: String): Map<String, Any> =
        mapOf(
            "userId" to uid,
            "contractId" to contractId,
            "installmentNumber" to installmentNumber,
            "dueDateIso" to dueDateIso,
            "principalDueMinor" to principalDueMinor,
            "interestDueMinor" to interestDueMinor,
            "lateFeeDueMinor" to lateFeeDueMinor,
            "paidMinor" to paidMinor,
            "status" to status,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

    private fun InstallmentEntity.toFirestoreUpdateMap(): Map<String, Any> =
        mapOf(
            "dueDateIso" to dueDateIso,
            "principalDueMinor" to principalDueMinor,
            "interestDueMinor" to interestDueMinor,
            "lateFeeDueMinor" to lateFeeDueMinor,
            "paidMinor" to paidMinor,
            "status" to status,
            "updatedAt" to FieldValue.serverTimestamp()
        )

    private fun PaymentTransactionEntity.toFirestoreCreateMap(uid: String): Map<String, Any> {
        val sensitiveCipher = if (encryptionManager.isUnlocked() && (note.isNotBlank() || !proofBase64.isNullOrBlank())) {
            val json = JSONObject().apply {
                put("note", note)
                if (!proofBase64.isNullOrBlank()) put("proofBase64", proofBase64)
            }.toString()
            encryptionManager.encryptString(json)
        } else encryptedPayload
        val useCipher = sensitiveCipher != null
        return mapOf(
            "userId" to uid,
            "contractId" to contractId,
            "installmentId" to installmentId?.takeIf { it.isNotBlank() },
            "amountMinor" to amountMinor,
            "paidDateIso" to paidDateIso,
            "paidAtEpoch" to paidAtEpoch,
            "autoLogged" to autoLogged,
            "note" to if (useCipher) null else note.takeIf { it.isNotBlank() },
            "proofBase64" to if (useCipher) null else proofBase64?.takeIf { it.isNotBlank() },
            "proofSha256" to proofSha256?.takeIf { it.isNotBlank() },
            "encryptedPayload" to sensitiveCipher,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        ).filterValues { it != null } as Map<String, Any>
    }

    private fun PaymentTransactionEntity.toFirestoreUpdateMap(): Map<String, Any> {
        val sensitiveCipher = if (encryptionManager.isUnlocked() && (note.isNotBlank() || !proofBase64.isNullOrBlank())) {
            val json = JSONObject().apply {
                put("note", note)
                if (!proofBase64.isNullOrBlank()) put("proofBase64", proofBase64)
            }.toString()
            encryptionManager.encryptString(json)
        } else null
        val useCipher = sensitiveCipher != null
        return mapOf(
            "installmentId" to (installmentId?.takeIf { it.isNotBlank() } ?: FieldValue.delete()),
            "amountMinor" to amountMinor,
            "paidDateIso" to paidDateIso,
            "paidAtEpoch" to paidAtEpoch,
            "note" to if (useCipher || note.isBlank()) FieldValue.delete() else note,
            "proofBase64" to if (useCipher || proofBase64.isNullOrBlank()) FieldValue.delete() else proofBase64!!,
            "proofSha256" to (proofSha256?.takeIf { it.isNotBlank() } ?: FieldValue.delete()),
            "encryptedPayload" to (sensitiveCipher ?: FieldValue.delete()),
            "updatedAt" to FieldValue.serverTimestamp()
        )
    }

    // --- Deserialization helpers with safe Number coercion & ESTIMATE timestamps ---

    private fun DocumentSnapshot.toAdminPolicyEntity(uid: String): AdminPolicyEntity? {
        if (!exists()) return null
        val updatedTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return AdminPolicyEntity(
            id = id,
            userId = uid,
            defaultInterestMode = getString("defaultInterestMode") ?: "SIMPLE_FLAT",
            allowZeroInterest = getBoolean("allowZeroInterest") ?: true,
            allowSimpleFlat = getBoolean("allowSimpleFlat") ?: true,
            allowAmortizedMonthly = getBoolean("allowAmortizedMonthly") ?: true,
            defaultRateBps = (getLong("defaultRateBps") ?: 500L).toInt(),
            defaultGraceDays = (getLong("defaultGraceDays") ?: 3L).toInt(),
            defaultLateFeeBps = (getLong("defaultLateFeeBps") ?: 200L).toInt(),
            defaultLateFeeCapMinor = getLong("defaultLateFeeCapMinor") ?: 2500L,
            paymentAllocationOrder = getString("paymentAllocationOrder") ?: "FEES_INTEREST_PRINCIPAL",
            defaultCurrencyCode = getString("defaultCurrencyCode") ?: "USD",
            defaultAutoLogRecurring = getBoolean("defaultAutoLogRecurring") ?: false,
            encryptedPayload = getString("encryptedPayload"),
            updatedAtEpoch = updatedTs?.toDate()?.time ?: System.currentTimeMillis(),
            cloudCreated = true,
            syncedToCloud = true
        )
    }

    private fun DocumentSnapshot.toCounterpartyEntity(uid: String): CounterpartyEntity? {
        if (!exists()) return null
        val cipher = getString("encryptedPayload")
        val decrypted = cipher?.let { encryptionManager.decryptString(it) }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val createdTs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        val updatedTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return CounterpartyEntity(
            id = id,
            userId = uid,
            name = getString("name") ?: "Counterparty",
            phone = decrypted?.optString("phone") ?: (getString("phone") ?: ""),
            email = decrypted?.optString("email") ?: (getString("email") ?: ""),
            whatsapp = decrypted?.optString("whatsapp") ?: (getString("whatsapp") ?: ""),
            notes = decrypted?.optString("notes") ?: (getString("notes") ?: ""),
            encryptedPayload = cipher,
            createdAtEpoch = createdTs?.toDate()?.time ?: System.currentTimeMillis(),
            updatedAtEpoch = updatedTs?.toDate()?.time ?: System.currentTimeMillis(),
            cloudCreated = true,
            syncedToCloud = true,
            deletedLocally = false
        )
    }

    private fun DocumentSnapshot.toContractEntity(uid: String): LoanContractEntity? {
        if (!exists()) return null
        val cipher = getString("encryptedPayload")
        val decrypted = cipher?.let { encryptionManager.decryptString(it) }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val createdTs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        val updatedTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return LoanContractEntity(
            id = id,
            userId = uid,
            counterpartyId = getString("counterpartyId") ?: return null,
            direction = getString("direction") ?: "LENT",
            title = getString("title") ?: "Loan Contract",
            principalMinor = getLong("principalMinor") ?: 0L,
            currencyCode = getString("currencyCode") ?: "USD",
            startDateIso = getString("startDateIso") ?: "2026-01-01",
            termCount = (getLong("termCount") ?: 1L).toInt(),
            frequency = getString("frequency") ?: "MONTHLY",
            interestMode = getString("interestMode") ?: "ZERO_INTEREST",
            interestRateBps = (getLong("interestRateBps") ?: 0L).toInt(),
            graceDays = (getLong("graceDays") ?: 3L).toInt(),
            lateFeeBps = (getLong("lateFeeBps") ?: 0L).toInt(),
            lateFeeCapMinor = getLong("lateFeeCapMinor") ?: 0L,
            paymentAllocationOrder = getString("paymentAllocationOrder") ?: "FEES_INTEREST_PRINCIPAL",
            autoLogRecurring = getBoolean("autoLogRecurring") ?: false,
            status = getString("status") ?: "ACTIVE",
            notes = decrypted?.optString("notes") ?: (getString("notes") ?: ""),
            encryptedPayload = cipher,
            createdAtEpoch = createdTs?.toDate()?.time ?: System.currentTimeMillis(),
            updatedAtEpoch = updatedTs?.toDate()?.time ?: System.currentTimeMillis(),
            cloudCreated = true,
            syncedToCloud = true,
            deletedLocally = false
        )
    }

    private fun DocumentSnapshot.toInstallmentEntity(uid: String): InstallmentEntity? {
        if (!exists()) return null
        val updatedTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return InstallmentEntity(
            id = id,
            userId = uid,
            contractId = getString("contractId") ?: return null,
            installmentNumber = (getLong("installmentNumber") ?: 1L).toInt(),
            dueDateIso = getString("dueDateIso") ?: "2026-01-01",
            principalDueMinor = getLong("principalDueMinor") ?: 0L,
            interestDueMinor = getLong("interestDueMinor") ?: 0L,
            lateFeeDueMinor = getLong("lateFeeDueMinor") ?: 0L,
            paidMinor = getLong("paidMinor") ?: 0L,
            status = getString("status") ?: "PENDING",
            updatedAtEpoch = updatedTs?.toDate()?.time ?: System.currentTimeMillis(),
            cloudCreated = true,
            syncedToCloud = true
        )
    }

    private suspend fun DocumentSnapshot.toPaymentEntity(uid: String): PaymentTransactionEntity? {
        if (!exists()) return null
        val cipher = getString("encryptedPayload")
        val decrypted = cipher?.let { encryptionManager.decryptString(it) }?.let {
            runCatching { JSONObject(it) }.getOrNull()
        }
        val b64 = decrypted?.optString("proofBase64")?.takeIf { it.isNotBlank() }
            ?: getString("proofBase64")
        val sha = getString("proofSha256")
        val restoredLocalPath = if (!b64.isNullOrBlank()) {
            ProofImageProcessor.restoreFromBase64IfValid(appContext, uid, id, b64, sha)
        } else null
        val createdTs = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        val updatedTs = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
        return PaymentTransactionEntity(
            id = id,
            userId = uid,
            contractId = getString("contractId") ?: return null,
            installmentId = getString("installmentId"),
            amountMinor = getLong("amountMinor") ?: 0L,
            paidDateIso = getString("paidDateIso") ?: "2026-01-01",
            paidAtEpoch = getLong("paidAtEpoch") ?: System.currentTimeMillis(),
            autoLogged = getBoolean("autoLogged") ?: false,
            note = decrypted?.optString("note") ?: (getString("note") ?: ""),
            proofLocalUri = restoredLocalPath,
            proofBase64 = b64,
            proofSha256 = sha,
            encryptedPayload = cipher,
            createdAtEpoch = createdTs?.toDate()?.time ?: System.currentTimeMillis(),
            updatedAtEpoch = updatedTs?.toDate()?.time ?: System.currentTimeMillis(),
            cloudCreated = true,
            syncedToCloud = true
        )
    }
}
