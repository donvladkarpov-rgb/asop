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
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun hasKeyPair(): Boolean {
        return try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            ks.containsAlias(KEY_ALIAS) && ks.isKeyEntry(KEY_ALIAS)
        } catch (_: Exception) {
            false
        }
    }

    fun hasCertificate(): Boolean = prefs.contains(KEY_CERT_PEM)

    fun generateKeyPair() {
        if (hasKeyPair()) return

        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY
        )
            .setKeySize(256)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA384)
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
}
