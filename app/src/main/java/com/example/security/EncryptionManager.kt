package com.example.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Client-side AES-256-GCM End-to-End Encryption manager.
 * Supports optional user passphrase derivation via PBKDF2WithHmacSHA256
 * plus Android Keystore local key-wrapping for biometric unlock convenience.
 */
class EncryptionManager {
    @Volatile
    private var activeSessionKey: SecretKey? = null

    fun isUnlocked(): Boolean = activeSessionKey != null

    fun lockSession() {
        activeSessionKey = null
    }

    fun generateSaltBase64(): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    fun unlockWithPassphrase(passphrase: String, saltBase64: String): SecretKey {
        val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, 256)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(spec).encoded
        val aesKey = SecretKeySpec(keyBytes, "AES")
        activeSessionKey = aesKey
        return aesKey
    }

    fun wrapKeyWithAndroidKeystore(sessionKey: SecretKey): Pair<String, String> {
        val ksKey = getOrCreateKeystoreKey()
        val cipher = Cipher.getInstance(AES_GCM)
        cipher.init(Cipher.ENCRYPT_MODE, ksKey)
        val wrapped = cipher.doFinal(sessionKey.encoded)
        val iv = cipher.iv
        return Base64.encodeToString(wrapped, Base64.NO_WRAP) to
            Base64.encodeToString(iv, Base64.NO_WRAP)
    }

    fun unlockFromWrappedKey(wrappedKeyBase64: String, ivBase64: String): Boolean {
        return try {
            val ksKey = getOrCreateKeystoreKey()
            val cipher = Cipher.getInstance(AES_GCM)
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            cipher.init(Cipher.DECRYPT_MODE, ksKey, GCMParameterSpec(128, iv))
            val rawKey = cipher.doFinal(Base64.decode(wrappedKeyBase64, Base64.NO_WRAP))
            activeSessionKey = SecretKeySpec(rawKey, "AES")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun encryptString(plaintext: String): String? {
        val key = activeSessionKey ?: return null
        return try {
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val ivB64 = Base64.encodeToString(iv, Base64.NO_WRAP)
            val ctB64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            "$ENVELOPE_PREFIX$ivB64:$ctB64"
        } catch (_: Exception) {
            null
        }
    }

    fun decryptString(envelope: String): String? {
        val key = activeSessionKey ?: return null
        if (!envelope.startsWith(ENVELOPE_PREFIX)) return null
        return try {
            val parts = envelope.removePrefix(ENVELOPE_PREFIX).split(":")
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ct = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(AES_GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    private companion object {
        const val AES_GCM = "AES/GCM/NoPadding"
        const val KEYSTORE_ALIAS = "PawLedgerE2EWrapperKey"
        const val ENVELOPE_PREFIX = "v1:"
        const val PBKDF2_ITERATIONS = 120_000
    }
}
