package ru.asop.crypto.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.asop.crypto.config.RootCaProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import java.util.Base64

/**
 * Выделенный ключ шифрования данных АСОП (RSA-2048).
 *
 * Используется для защиты ключей карт at-rest в БД (ASOP_KEYS.KEY_MATERIAL):
 * записи всегда хранятся зашифрованными ПУБЛИЧНЫМ ключом сервера, расшифровка возможна
 * только здесь приватным ключом (мастер-система). Доставка на терминал — по mTLS (вариант Б),
 * per-terminal шифрования нет.
 *
 * Dev-режим (asop.crypto.server-key.dev-mode-enabled=true): генерация всегда возвращает
 * единственный заранее известный ключ (см. asop.crypto.server-key.dev-key-base64),
 * чтобы не тратить дорогие карты при разработке/отладке.
 */
@Service
class ServerKeyService(
    private val properties: RootCaProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cfg = properties.serverKey

    private val rsaKeyPair: KeyPair by lazy { loadOrCreateKeyPair() }

    private fun loadOrCreateKeyPair(): KeyPair {
        val keystoreFile = File(cfg.keystorePath)
        if (keystoreFile.exists()) {
            return loadKeyPair()
        }
        log.info("Generating new server encryption keypair at {}", cfg.keystorePath)
        val keyPair = generateRsaKeyPair()
        saveKeyPair(keyPair)
        return keyPair
    }

    private fun loadKeyPair(): KeyPair {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(cfg.keystorePath).use { fis ->
            keyStore.load(fis, cfg.keystorePassword.toCharArray())
        }
        val privateKey = keyStore.getKey(
            cfg.keyAlias,
            cfg.keystorePassword.toCharArray()
        ) as PrivateKey
        val cert = keyStore.getCertificate(cfg.keyAlias)
        return KeyPair(cert.publicKey, privateKey)
    }

    private fun saveKeyPair(keyPair: KeyPair) {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, cfg.keystorePassword.toCharArray())

        // Без сертификата нельзя, генерируем самоподписанный (только как контейнер ключа)
        val certGenerator = java.security.cert.CertificateFactory.getInstance("X.509")
        val x509 = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            org.bouncycastle.asn1.x500.X500Name("CN=ASOP Server Key, O=ASOP, C=RU"),
            java.math.BigInteger.valueOf(System.currentTimeMillis()),
            java.util.Date(),
            java.util.Date(System.currentTimeMillis() + 10L * 365 * 24 * 60 * 60 * 1000),
            org.bouncycastle.asn1.x500.X500Name("CN=ASOP Server Key, O=ASOP, C=RU"),
            keyPair.public
        ).build(
            org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private)
        )
        val cert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter().getCertificate(x509)

        keyStore.setKeyEntry(
            cfg.keyAlias,
            keyPair.private,
            cfg.keystorePassword.toCharArray(),
            arrayOf(cert)
        )
        keystoreFileParent().mkdirs()
        FileOutputStream(cfg.keystorePath).use { fos ->
            keyStore.store(fos, cfg.keystorePassword.toCharArray())
        }
    }

    private fun keystoreFileParent(): File = File(cfg.keystorePath).parentFile ?: File(".")

    private fun generateRsaKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        return generator.generateKeyPair()
    }

    fun getPublicKey(): PublicKey = rsaKeyPair.public

    /**
     * Шифрует ключ публичным ключом сервера (RSA-OAEP-SHA256).
     * Поддерживает ключи произвольной длины (RSA-2048 OAEP вмещает до ~190 байт plaintext).
     * Сейчас генерируются 24-байтные 3K3DES для DESFire; MIFARE Classic использует первые 6 байт.
     */
    fun encryptKey(plainBase64: String): String {
        val plain = Base64.getDecoder().decode(plainBase64)
        require(plain.isNotEmpty() && plain.size <= 190) { "Key material must be 1-190 bytes, got ${plain.size}" }
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val spec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair.public, spec)
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }

    /**
     * Расшифровывает ключ приватным ключом сервера. Отдаёт открытый plaintext (base64).
     */
    fun decryptKey(cipherBase64: String): String {
        val cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding")
        val spec = OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT
        )
        cipher.init(Cipher.DECRYPT_MODE, rsaKeyPair.private, spec)
        val plain = cipher.doFinal(Base64.getDecoder().decode(cipherBase64))
        require(plain.isNotEmpty()) { "Decrypted key material is empty" }
        return Base64.getEncoder().encodeToString(plain)
    }

    /**
     * Генерирует новый случайный 24-байтный 3K3DES-ключ и сразу шифрует его публичным ключом.
     * В dev-режиме всегда возвращает единственный заранее известный ключ.
     */
    fun generateKey(): Pair<String, String> {
        val plainBase64: String
        val keyId: String
        if (cfg.devModeEnabled) {
            plainBase64 = cfg.devKeyBase64
            keyId = "dev-fixed-asop-key"
            log.warn("DEV MODE: returning fixed ASOP key {}", keyId)
        } else {
            val key = ByteArray(24)
            SecureRandom().nextBytes(key)
            plainBase64 = Base64.getEncoder().encodeToString(key)
            keyId = ru.asop.common.util.UuidUtils.newId().toString()
        }
        return keyId to encryptKey(plainBase64)
    }

    fun isDevModeEnabled(): Boolean = cfg.devModeEnabled

    /**
     * Подписывает данные (canonical JSON cardIdentity) RSA-PSS-SHA256 приватным ключом сервера.
     */
    fun sign(data: ByteArray): String {
        val signature = Signature.getInstance("RSASSA-PSS")
        val spec = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
        signature.setParameter(spec)
        signature.initSign(rsaKeyPair.private)
        signature.update(data)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * Проверяет подпись RSA-PSS-SHA256 публичным ключом сервера.
     */
    fun verify(data: ByteArray, signatureBase64: String): Boolean {
        return runCatching {
            val signature = Signature.getInstance("RSASSA-PSS")
            val spec = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
            signature.setParameter(spec)
            signature.initVerify(rsaKeyPair.public)
            signature.update(data)
            signature.verify(Base64.getDecoder().decode(signatureBase64))
        }.getOrDefault(false)
    }
}