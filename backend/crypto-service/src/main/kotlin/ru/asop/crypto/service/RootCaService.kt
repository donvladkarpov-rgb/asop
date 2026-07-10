package ru.asop.crypto.service

import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
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
        ensureServerCert()
    }

    private fun initializeCaHierarchy() {
        val keystoreFile = File(properties.rootCa.keystorePath)

        if (keystoreFile.exists()) {
            log.info("Loading existing Root CA from {}", properties.rootCa.keystorePath)
            loadRootCa()
        } else {
            log.info("Generating new Root CA")
            generateRootCa()
        }

        val intermediateFile = File(properties.intermediateCa.keystorePath)
        if (intermediateFile.exists()) {
            log.info("Loading existing Intermediate CA from {}", properties.intermediateCa.keystorePath)
            loadIntermediateCa()
        } else {
            log.info("Generating new Intermediate CA")
            generateIntermediateCa()
        }

        log.info("CA hierarchy initialized. Root CA subject: {}, Intermediate CA subject: {}",
            rootCaCert.subjectX500Principal.name,
            intermediateCaCert.subjectX500Principal.name)
    }

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

    private fun loadIntermediateCa() {
        val keyStore = KeyStore.getInstance("PKCS12")
        FileInputStream(properties.intermediateCa.keystorePath).use { fis ->
            keyStore.load(fis, properties.intermediateCa.keystorePassword.toCharArray())
        }

        intermediateCaPrivateKey = keyStore.getKey(
            properties.intermediateCa.keyAlias,
            properties.intermediateCa.keystorePassword.toCharArray()
        ) as PrivateKey

        intermediateCaCert = keyStore.getCertificate(properties.intermediateCa.keyAlias) as X509Certificate

        log.info("Intermediate CA loaded successfully. Valid until: {}", intermediateCaCert.notAfter)
    }

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

    private fun generateIntermediateCa() {
        val keyPair = generateEcKeyPair()

        val rootDn = X500Name.getInstance(ASN1Sequence.getInstance(rootCaCert.subjectX500Principal.encoded))
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

        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign)
        )

        val certHolder = certBuilder.build(contentSigner)
        intermediateCaCert = JcaX509CertificateConverter().getCertificate(certHolder)
        intermediateCaPrivateKey = keyPair.private

        saveIntermediateCa(keyPair, intermediateCaCert)

        log.info("Intermediate CA generated and saved to {}", properties.intermediateCa.keystorePath)
    }

    private fun saveIntermediateCa(keyPair: KeyPair, certificate: X509Certificate) {
        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, properties.intermediateCa.keystorePassword.toCharArray())

        keyStore.setKeyEntry(
            properties.intermediateCa.keyAlias,
            keyPair.private,
            properties.intermediateCa.keystorePassword.toCharArray(),
            arrayOf(certificate)
        )

        File(properties.intermediateCa.keystorePath).parentFile?.mkdirs()
        FileOutputStream(properties.intermediateCa.keystorePath).use { fos ->
            keyStore.store(fos, properties.intermediateCa.keystorePassword.toCharArray())
        }
    }

    private fun generateEcKeyPair(): KeyPair {
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(256)
        return keyPairGenerator.generateKeyPair()
    }

    fun signCertificate(
        publicKey: PublicKey,
        dn: String,
        validityYears: Int,
        dnsNames: List<String> = emptyList()
    ): X509Certificate {
        val issuerDn = X500Name.getInstance(ASN1Sequence.getInstance(intermediateCaCert.subjectX500Principal.encoded))
        val subjectDn = X500Name(dn)
        val serialNumber = BigInteger.valueOf(System.currentTimeMillis())
        val notBefore = Date()
        val notAfter = Date(System.currentTimeMillis() + validityYears * 365L * 24 * 60 * 60 * 1000)

        val contentSigner = JcaContentSignerBuilder("SHA256withECDSA")
            .build(intermediateCaPrivateKey)

        val certBuilder = JcaX509v3CertificateBuilder(
            issuerDn, serialNumber, notBefore, notAfter, subjectDn, publicKey
        )

        certBuilder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        certBuilder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment)
        )

        if (dnsNames.isNotEmpty()) {
            val names = dnsNames.map { GeneralName(GeneralName.dNSName, it) }.toTypedArray()
            certBuilder.addExtension(
                Extension.subjectAlternativeName, false,
                GeneralNames(names)
            )
        }

        val certHolder = certBuilder.build(contentSigner)
        return JcaX509CertificateConverter().getCertificate(certHolder)
    }

    fun getRootCaCertificate(): X509Certificate = rootCaCert

    fun getIntermediateCaCertificate(): X509Certificate = intermediateCaCert

    fun getServerCertInfo(): Pair<String, String> {
        val cfg = properties.serverCert
        return Pair(cfg.keystorePath, cfg.keystorePassword)
    }

    private fun ensureServerCert() {
        val cfg = properties.serverCert
        val keystoreFile = File(cfg.keystorePath)
        if (keystoreFile.exists()) {
            log.info("Server cert keystore exists at {}", cfg.keystorePath)
            return
        }

        log.info("Generating bootstrap server cert for crypto-service at {}", cfg.keystorePath)
        val keyPair = generateEcKeyPair()
        val dn = "CN=${cfg.commonName}, O=ASOP"
        val cert = signCertificate(keyPair.public, dn, cfg.validityYears, cfg.dnsNames)

        val keyStore = KeyStore.getInstance("PKCS12")
        keyStore.load(null, cfg.keystorePassword.toCharArray())
        keyStore.setKeyEntry(cfg.keyAlias, keyPair.private, cfg.keystorePassword.toCharArray(), arrayOf(cert))
        keystoreFile.parentFile?.mkdirs()
        FileOutputStream(keystoreFile).use { keyStore.store(it, cfg.keystorePassword.toCharArray()) }
        log.info("Bootstrap server cert generated: serial={}", cert.serialNumber.toString(16))
    }
}
