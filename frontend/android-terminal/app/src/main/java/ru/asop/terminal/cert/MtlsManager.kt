package ru.asop.terminal.cert

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MtlsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val KEY_ALIAS = "asop_terminal"
        private const val PREFS_NAME = "asop_terminal_cert"
        private const val KEY_CERT_PEM = "cert_chain_pem"
        private const val KEY_PUBLIC_B64 = "public_key_b64"
        private const val PREF_KEYGEN_VERSION = "keygen_version"
        private const val KEYGEN_VERSION = 2
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Если ключ сгенерирован старой версией кода (без DIGEST_NONE) — сбросить */
    private fun resetIfStale() {
        if (prefs.getInt(PREF_KEYGEN_VERSION, 0) < KEYGEN_VERSION) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                if (ks.containsAlias(KEY_ALIAS)) {
                    ks.deleteEntry(KEY_ALIAS)
                }
            } catch (_: Exception) {}
            prefs.edit().clear().apply()
        }
    }

    fun hasKeyPair(): Boolean {
        resetIfStale()
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.containsAlias(KEY_ALIAS) && ks.isKeyEntry(KEY_ALIAS)
        } catch (_: Exception) {
            false
        }
    }

    fun hasCertificate(): Boolean {
        resetIfStale()
        return prefs.contains(KEY_CERT_PEM)
    }

    fun generateKeyPair() {
        if (hasKeyPair()) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.deleteEntry(KEY_ALIAS)
        }
        prefs.edit().remove(KEY_CERT_PEM).remove(KEY_PUBLIC_B64).apply()

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512
            )
            .build()

        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        prefs.edit()
            .putString(KEY_PUBLIC_B64, Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP))
            .putInt(PREF_KEYGEN_VERSION, KEYGEN_VERSION)
            .apply()
    }

    fun getPublicKeyBase64(): String {
        prefs.getString(KEY_PUBLIC_B64, null)?.let { return it }

        if (!hasKeyPair()) generateKeyPair()

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cert = ks.getCertificate(KEY_ALIAS) ?: return ""
        val b64 = Base64.encodeToString(cert.publicKey.encoded, Base64.NO_WRAP)
        prefs.edit().putString(KEY_PUBLIC_B64, b64).apply()
        return b64
    }

    fun storeCertificateChain(pemChain: List<String>) {
        val combined = pemChain.joinToString("\n")
        prefs.edit().putString(KEY_CERT_PEM, combined).apply()
    }

    fun storeCertificate(certPem: String) {
        storeCertificateChain(listOf(certPem))
    }

    fun loadCertificateChain(): List<X509Certificate>? {
        val combined = prefs.getString(KEY_CERT_PEM, null) ?: return null
        val cf = CertificateFactory.getInstance("X.509")
        val pems = combined.split("(?=-----BEGIN CERTIFICATE-----)".toRegex())
            .filter { it.contains("-----BEGIN CERTIFICATE-----") }

        return pems.map { pem ->
            val der = pem
                .replace("-----BEGIN CERTIFICATE-----", "")
                .replace("-----END CERTIFICATE-----", "")
                .replace("\\s".toRegex(), "")
            cf.generateCertificate(ByteArrayInputStream(Base64.decode(der, Base64.NO_WRAP))) as X509Certificate
        }
    }

    fun loadCertificate(): X509Certificate? = loadCertificateChain()?.firstOrNull()

    /**
     * Промпт 013: cert-expiry helpers для нижнего informer'а.
     * Возвращает Long.MAX_VALUE если сертификата нет (значит нужно provision()).
     */
    fun getCertificateNotAfterMillis(): Long {
        val cert = loadCertificate() ?: return Long.MAX_VALUE
        return cert.notAfter.time
    }

    fun getDaysUntilExpiry(): Long? {
        val notAfter = getCertificateNotAfterMillis()
        if (notAfter == Long.MAX_VALUE) return null
        val now = System.currentTimeMillis()
        val diffMs = notAfter - now
        return diffMs / (1000L * 60L * 60L * 24L)
    }

    /**
     * Промпт 013: renewal helper — НЕ генерирует новую ключевую пару,
     * подписывает сертификат с существующим public key. Вызывается из UI когда
     * informer показывает <30 дней или <7 дней. Auto-вызов происходит из
     * CertCheckWorker (1 час periodic).
     */
    val publicKeyBase64Cached: String
        get() {
            prefs.getString(KEY_PUBLIC_B64, null)?.let { return it }
            return getPublicKeyBase64()
        }

    fun resetKeyAndCert() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) {
                ks.deleteEntry(KEY_ALIAS)
            }
        } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }
}
