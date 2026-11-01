package com.pablopinheiroo.telegramninjaprobe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Stores Telegram API credentials and the TDLib database key encrypted with Android Keystore. */
class ProbeVault(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "telegramninja_probe_secure", Context.MODE_PRIVATE
    )

    @Synchronized
    fun putString(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        preferences.edit().putString(
            name,
            Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
        ).apply()
    }

    @Synchronized
    fun getString(name: String): String? {
        val encoded = preferences.getString(name, null) ?: return null
        return runCatching {
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > IV_BYTES)
            val iv = packed.copyOfRange(0, IV_BYTES)
            val encrypted = packed.copyOfRange(IV_BYTES, packed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        }.getOrNull()
    }

    @Synchronized
    fun removeString(name: String) {
        preferences.edit().remove(name).apply()
    }

    @Synchronized
    fun getOrCreateBytes(name: String, size: Int): ByteArray {
        val current = getString(name)?.let {
            runCatching { Base64.decode(it, Base64.NO_WRAP) }.getOrNull()
        }
        if (current != null && current.size == size) return current
        val bytes = ByteArray(size).also { SecureRandom().nextBytes(it) }
        putString(name, Base64.encodeToString(bytes, Base64.NO_WRAP))
        return bytes
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "telegramninja_probe_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}
