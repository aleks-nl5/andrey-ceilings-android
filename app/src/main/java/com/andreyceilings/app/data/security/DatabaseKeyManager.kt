package com.andreyceilings.app.data.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.security.SecureRandom

/**
 * Creates a random SQLCipher passphrase and stores only its AES-GCM-encrypted form.
 * The wrapping key lives in Android Keystore and is never written to app files.
 */
internal object DatabaseKeyManager {
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val WRAPPING_KEY_ALIAS = "andrey_ceilings_db_wrapping_key_v1"
    private const val PREFS_NAME = "secure_db_key_v1"
    private const val PREF_WRAPPED_KEY = "wrapped_passphrase"
    private const val PREF_RESTORE_ORIGINAL_KEY = "restore_pending_original_passphrase"
    private const val GCM_TAG_BITS = 128

    @Synchronized
    fun getOrCreatePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val existing = prefs.getString(PREF_WRAPPED_KEY, null)
        if (existing != null) return unwrap(existing)

        val passphrase = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = wrap(passphrase)
        check(prefs.edit().putString(PREF_WRAPPED_KEY, wrapped).commit()) {
            "Could not persist the encrypted database key"
        }
        return passphrase
    }

    /**
     * Installs a passphrase imported from a password-protected portable backup.
     * The imported passphrase is re-wrapped with this installation's Keystore key.
     */
    @Synchronized
    fun replacePassphrase(context: Context, passphrase: ByteArray) {
        require(passphrase.size == 32) { "Некоректний ключ бази даних." }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val wrapped = wrap(passphrase)
        check(prefs.edit().putString(PREF_WRAPPED_KEY, wrapped).commit()) {
            "Не вдалося зберегти новий захищений ключ бази даних."
        }
    }

    /**
     * Original key is persisted only as an already Keystore-wrapped value.
     * Its durable presence marks a recoverable, not-yet-committed restore.
     */
    @Synchronized
    fun beginPendingRestore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(!prefs.contains(PREF_RESTORE_ORIGINAL_KEY)) {
            "Попереднє відновлення ще не завершене."
        }
        val prior = checkNotNull(prefs.getString(PREF_WRAPPED_KEY, null)) {
            "Не знайдено поточний ключ БД."
        }
        check(prefs.edit().putString(PREF_RESTORE_ORIGINAL_KEY, prior).commit()) {
            "Не вдалося зберегти маркер відновлення."
        }
    }

    @Synchronized
    fun pendingRestoreOriginalPassphrase(context: Context): ByteArray? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_RESTORE_ORIGINAL_KEY, null)?.let(::unwrap)

    @Synchronized
    fun rollbackPendingRestoreKey(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val prior = checkNotNull(prefs.getString(PREF_RESTORE_ORIGINAL_KEY, null)) {
            "Попередній ключ для відкату відсутній."
        }
        check(prefs.edit().putString(PREF_WRAPPED_KEY, prior)
            .remove(PREF_RESTORE_ORIGINAL_KEY).commit()) {
            "Не вдалося відновити ключ БД."
        }
    }

    @Synchronized
    fun finishPendingRestore(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        check(prefs.contains(PREF_RESTORE_ORIGINAL_KEY)) {
            "Маркер відновлення відсутній."
        }
        check(prefs.edit().remove(PREF_RESTORE_ORIGINAL_KEY).commit()) {
            "Не вдалося завершити відновлення."
        }
    }

    private fun getOrCreateWrappingKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(WRAPPING_KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                WRAPPING_KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private fun wrap(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateWrappingKey())
        val encrypted = cipher.doFinal(plain)
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun unwrap(encoded: String): ByteArray {
        val combined = Base64.decode(encoded, Base64.NO_WRAP)
        require(combined.size > 12) { "Encrypted database key is malformed" }
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateWrappingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }
}
