package ru.asop.terminal.db

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Локальное шифрование ключей ASOP_KEYS at-rest (вариант Б).
 * Один аппаратный AES-GCM-ключ (Android Keystore, PURPOSE_ENCRYPT|DECRYPT,
 * неэкспортируемый, TEE/StrongBox) на все записи. Ключ расшифровывается
 * только в памяти при карточных операциях и наружу не выходит.
 */
@Singleton
class TerminalKeyCryptor @Inject constructor() {

    companion object {
        private const val KEY_ALIAS = "asop_terminal_keys_aes"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
    }

    private val key: SecretKey by lazy {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey) ?: generateAndStore()
    }

    private fun generateAndStore(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ct = cipher.doFinal(plain)
        // формат: IV (12) || ciphertext
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): ByteArray {
        val blob = Base64.decode(stored, Base64.NO_WRAP)
        val iv = blob.copyOfRange(0, IV_LENGTH)
        val ct = blob.copyOfRange(IV_LENGTH, blob.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}