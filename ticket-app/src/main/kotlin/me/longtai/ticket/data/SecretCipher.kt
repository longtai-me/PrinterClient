package me.longtai.ticket.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Encrypts secrets (the ticket signing key) at rest with an AES-256-GCM key held in the
 * Android Keystore, so the raw secret never sits in plaintext in DataStore. If the Keystore is
 * unavailable it degrades to storing the value unencrypted (marked) rather than breaking the app,
 * and it still reads values written by older builds (plain text, no marker).
 */
@Singleton
class SecretCipher @Inject constructor() {

    /** Returns a storable string: encrypted when possible, otherwise marked plaintext. */
    fun protect(plain: String): String {
        if (plain.isEmpty()) return ""
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val blob = cipher.iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            PREFIX_ENC + Base64.encodeToString(blob, Base64.NO_WRAP)
        } catch (e: Exception) {
            Timber.w(e, "keystore encrypt unavailable; storing unprotected")
            PREFIX_RAW + plain
        }
    }

    /** Reverses [protect]; also accepts legacy unmarked plaintext. Returns "" if decryption fails. */
    fun reveal(stored: String): String = when {
        stored.isEmpty() -> ""
        stored.startsWith(PREFIX_ENC) -> try {
            val blob = Base64.decode(stored.removePrefix(PREFIX_ENC), Base64.NO_WRAP)
            val iv = blob.copyOfRange(0, GCM_IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(blob.copyOfRange(GCM_IV_BYTES, blob.size)), Charsets.UTF_8)
        } catch (e: Exception) {
            Timber.w(e, "keystore decrypt failed")
            ""
        }
        stored.startsWith(PREFIX_RAW) -> stored.removePrefix(PREFIX_RAW)
        else -> stored // legacy value written before encryption was added
    }

    private fun getOrCreateKey(): SecretKey {
        val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keystore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "ticket_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_IV_BYTES = 12
        const val GCM_TAG_BITS = 128
        const val PREFIX_ENC = "enc:"
        const val PREFIX_RAW = "raw:"
    }
}
