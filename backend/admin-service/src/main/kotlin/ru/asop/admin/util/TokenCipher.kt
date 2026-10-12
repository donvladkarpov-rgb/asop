package ru.asop.admin.util

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM шифрование токенов фискализаторов — копия `ru.asop.fiscal.util.TokenCipher`
 * (fiscal-service недоступен как модуль, общий ключ задаётся одинаковым
 * `fiscal.token-secret` в обоих сервисах, default `0123456789ABCDEF0123456789ABCDEF`).
 * Формат ciphertext: IV (12 байт) || AES-GCM(ciphertext+tag).
 */
@Component
class TokenCipher(
    @Value("\${fiscal.token-secret:0123456789ABCDEF0123456789ABCDEF}") private val secret: String
) {
    private val key = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "AES")

    fun encrypt(plaintext: String): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(encoded: ByteArray): ByteArray {
        if (encoded.size < 12) return ByteArray(0)
        val iv = encoded.sliceArray(0..11)
        val ct = encoded.sliceArray(12 until encoded.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(ct)
    }
}
