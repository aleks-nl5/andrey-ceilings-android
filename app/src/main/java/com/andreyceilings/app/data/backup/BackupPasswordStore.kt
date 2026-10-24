package com.andreyceilings.app.data.backup

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores the portable-backup password encrypted with an Android Keystore key.
 * This enables unattended backups on this installation; users must keep the
 * password separately to restore a portable backup on another device.
 */
internal object BackupPasswordStore {
    private const val PREFS = "portable_backup_settings_v1"
    private const val KEY_PASSWORD = "encrypted_password"
    private const val KEY_FOLDER = "folder_uri"
    private const val KEY_ALIAS = "portable_backup_password_key_v1"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"

    fun savePassword(context: Context, password: CharArray) {
        require(password.size >= 10) { "Пароль має містити щонайменше 10 символів." }
        val plain = String(password).toByteArray(Charsets.UTF_8)
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val bytes = cipher.iv + cipher.doFinal(plain)
            check(prefs(context).edit().putString(KEY_PASSWORD, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
                "Не вдалося зберегти пароль резервної копії."
            }
        } finally {
            plain.fill(0)
        }
    }

    fun readPassword(context: Context): CharArray? {
        val encoded = prefs(context).getString(KEY_PASSWORD, null) ?: return null
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        require(bytes.size > 12) { "Збережений пароль резервної копії пошкоджений." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        val plain = cipher.doFinal(bytes.copyOfRange(12, bytes.size))
        return try { plain.toString(Charsets.UTF_8).toCharArray() } finally { plain.fill(0); bytes.fill(0) }
    }

    fun setFolder(context: Context, uri: Uri) {
        check(prefs(context).edit().putString(KEY_FOLDER, uri.toString()).commit())
    }

    fun folder(context: Context): Uri? =
        prefs(context).getString(KEY_FOLDER, null)?.let(Uri::parse)

    fun clearPassword(context: Context) {
        prefs(context).edit().remove(KEY_PASSWORD).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build())
        return generator.generateKey()
    }
}
