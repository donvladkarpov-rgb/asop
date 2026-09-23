package ru.asop.payment.cert

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.io.ByteArrayInputStream
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec

/**
 * mTLS-идентичность app-payment (порт MtlsManager/CertManager из терминала/distributor).
 * ECC P-256 в AndroidKeyStore, PEM-цепочка в SharedPreferences.
 * Алиас/префикс `asop_payment_*` — отдельные, не конфликтуют с distributor/terminal
 * на том же устройстве.
 */
class CertManager(private val context: Context) {

    companion object {
        const val KEY_ALIAS = "asop_payment_cert"
        private const val PREFS_NAME = "asop_payment_cert"
        private const val KEY_CERT_PEM = "cert_chain_pem"
        private const val KEY_PUBLIC_B64 = "public_key_b64"
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasKeyPair(): Boolean = try {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.containsAlias(KEY_ALIAS) && ks.isKeyEntry(KEY_ALIAS)
    } catch (_: Exception) {
        false
    }

    fun hasCertificate(): Boolean = prefs.contains(KEY_CERT_PEM)

    fun generateKeyPair() {
        if (hasKeyPair()) {
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                ks.deleteEntry(KEY_ALIAS)
            } catch (_: Exception) {}
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
        prefs.edit().putString(KEY_CERT_PEM, pemChain.joinToString("\n")).apply()
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
            cf.generateCertificate(ByteArrayInputStream(Base64.decode(der, Base64.NO_WRAP))).let {
                it as X509Certificate
            }
        }
    }

    fun resetKeyAndCert() {
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {}
        prefs.edit().clear().apply()
    }
}
