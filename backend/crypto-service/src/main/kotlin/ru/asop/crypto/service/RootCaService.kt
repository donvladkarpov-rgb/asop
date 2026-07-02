package ru.asop.crypto.service

import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.asop.crypto.config.RootCaProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Date

@Service
class RootCaService(
    private val properties: RootCaProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private lateinit var rootCaPrivateKey: PrivateKey
    private lateinit var rootCaCert: X509Certificate
    private lateinit var intermediateCaPrivateKey: PrivateKey
    private lateinit var intermediateCaCert: X509Certificate

    init {
        initializeCaHierarchy()
    }

    /**
     * Инициализация иерархии CA при старте сервиса.
     * Root CA загружается из PKCS#12 хранилища (или генерируется).
     * Intermediate CA генерируется каждый раз (для MVP).
     */
    private fun initializeCaHierarchy() {
        val keystoreFile = File(properties.rootCa.keystorePath)

        if (keystoreFile.exists()) {
            log.info("Loading existing Root CA from {}", properties.rootCa.keystorePath)
            loadRootCa()
        } else {
            log.info("Generating new Root CA")
            generateRootCa()
        }

        // Intermediate CA генерируется при каждом старте (для MVP)
        // В production его тоже нужно сохранять в хранилище
        generateIntermediateCa()

        log.info("CA hierarchy initialized. Root CA subject: {}, Intermediate CA subject: {}",
            rootCaCert.subjectX500Principal.name,
            intermediateCaCert.subjectX500Principal.name)
    }

    /**
     * Загрузка Root CA из PKCS#12 хранилища
     */
    private fun loadRootCa() {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(properties.rootCa.keystorePath).use { fis ->
            keyStore.load(fis, properties.rootCa.keystorePassword.toCharArray())
        }

        rootCaPrivateKey = keyStore.getKey(
            properties.rootCa.keyAlias,
            properties.rootCa.keystorePassword.toCharArray()
        ) as PrivateKey

        rootCaCert = keyStore.getCertificate(properties.rootCa.keyAlias) as X509Certificate

        log.info("Root CA loaded successfully. Valid until: {}", rootCaCert.notAfter)
    }

    /**
     * Генерация нового Root CA (self-signed сертификат)
     */
    private fun generateRootCa() {
        val keyPair = generateEcKeyPair()

        val dn = X500Name(properties.rootCa.dn)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() +
                properties.rootCa.validityYears * 365L * 24 * 60 * 60 * 1000)

        val contentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .build(keyPair.private)

        val certBuilder = JcaX509v3CertificateBuilder(
            dn, serialNumber, notBefore, notAfter, dn, keyPair.public
        )

        // Root CA — это CA, может подписывать другие CA
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val certHolder = certBuilder.build(contentSigner)
        rootCaCert = JcaX509CertificateConverter().getCertificate(certHolder)
        rootCaPrivateKey = keyPair.private

        saveRootCa(keyPair, rootCaCert)

        log.info("Root CA generated and saved to {}", properties.rootCa.keystorePath)
    }

    /**
     * Сохранение Root CA в PKCS#12 хранилище
     */
    private fun saveRootCa(keyPair: KeyPair, certificate: X509Certificate) {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, properties.rootCa.keystorePassword.toCharArray())

        keyStore.setKeyEntry(
            properties.rootCa.keyAlias,
            keyPair.private,
            properties.rootCa.keystorePassword.toCharArray(),
            arrayOf(certificate)
        )

        File(properties.rootCa.keystorePath).parentFile?.mkdirs()
        FileOutputStream(properties.rootCa.keystorePath).use { fos ->
            keyStore.store(fos, properties.rootCa.keystorePassword.toCharArray())
        }
    }

    /**
     * Генерация Intermediate CA (подписывается Root CA)
     */
    private fun generateIntermediateCa() {
        val keyPair = generateEcKeyPair()

        val rootDn = X500Name(rootCaCert.subjectX500Principal.name)
        val intermediateDn = X500Name(properties.intermediateCa.dn)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() +
                properties.intermediateCa.validityYears * 365L * 24 * 60 * 60 * 1000)

        val contentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .build(rootCaPrivateKey)

        val certBuilder = JcaX509v3CertificateBuilder(
            rootDn, serialNumber, notBefore, notAfter, intermediateDn, keyPair.public
        )

        // Intermediate CA — это CA с глубиной 0 (может подписывать только end-entity)
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val certHolder = certBuilder.build(contentSigner)
        intermediateCaCert = JcaX509CertificateConverter().getCertificate(certHolder)
        intermediateCaPrivateKey = keyPair.private

        log.info("Intermediate CA generated. Valid until: {}", intermediateCaCert.notAfter)
    }

    /**
     * Генерация ECC P-256 ключевой пары
     */
    private fun generateEcKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        return keyPairGenerator.generateKeyPair()
    }

    /**
     * Подписать публичный ключ и выпустить X.509 сертификат.
     * Используется для выпуска сертификатов терминалов и водителей.
     *
     * @param publicKey Публичный ключ, который нужно подписать
     * @param dn Distinguished Name для сертификата
     * @param validityYears Срок действия в годах
     * @return Подписанный X.509 сертификат
     */
    fun signCertificate(publicKey: PublicKey, dn: String, validityYears: Int): X509Certificate {
        val issuerDn = X500Name(intermediateCaCert.subjectX500Principal.name)
        val subjectDn = X500Name(dn)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + validityYears * 365L * 24 * 60 * 60 * 1000)

        val contentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .build(intermediateCaPrivateKey)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuerDn, serialNumber, notBefore, notAfter, subjectDn, publicKey
        )

        // End-entity сертификат — не CA
        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        val certHolder = certBuilder.build(contentSigner)
        return JcaX509CertificateConverter().getCertificate(certHolder)
    }

    /**
     * Получить публичный сертификат Root CA (для экспорта клиентам)
     */
    fun getRootCaCertificate(): X509Certificate = rootCaCert

    /**
     * Получить публичный сертификат Intermediate CA
     */
    fun getIntermediateCaCertificate(): X509Certificate = intermediateCaCert
}