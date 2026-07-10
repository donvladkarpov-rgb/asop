package ru.asop.terminal.cert

import android.content.Context
import android.security.KeyChain
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.security.*
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MtlsManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val keyStore: KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    companion object {
        private const val KEY_ALIAS = "asop_terminal_key"
        private const val CERT_ALIAS = "asop_terminal_cert"
    }

    fun hasKeyPair(): Boolean = keyStore.containsAlias(KEY_ALIAS)

    fun hasCertificate(): Boolean {
        val prefs = context.getSharedPreferences("asop_cert", Context.MODE_PRIVATE)
        return prefs.contains("cert_pem")
    }

    fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore")
        val spec = java.security.spec.ECGenParameterSpec("secp256r1")
        generator.initialize(spec)
        val keyPair = generator.generateKeyPair()

        val publicKey = keyPair.public as java.security.interfaces.ECPublicKey
        val prefs = context.getSharedPreferences("asop_cert", Context.MODE_PRIVATE)
        prefs.edit().putString("public_key", Base64.encodeToString(publicKey.encoded, Base64.NO_WRAP)).apply()

        return keyPair
    }

    fun getPublicKeyBase64(): String {
        val prefs = context.getSharedPreferences("asop_cert", Context.MODE_PRIVATE)
        return prefs.getString("public_key", null) ?: run {
            val keyPair = generateKeyPair()
            Base64.encodeToString(keyPair.public.encoded, Base64.NO_WRAP)
        }
    }

    fun storeCertificate(certPem: String) {
        val prefs = context.getSharedPreferences("asop_cert", Context.MODE_PRIVATE)
        prefs.edit().putString("cert_pem", certPem).apply()
    }

    fun loadCertificate(): X509Certificate? {
        val prefs = context.getSharedPreferences("asop_cert", Context.MODE_PRIVATE)
        val certPem = prefs.getString("cert_pem", null) ?: return null

        val derBytes = certPem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace("\\s".toRegex(), "")
        val decoded = Base64.decode(derBytes, Base64.NO_WRAP)

        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(decoded)) as X509Certificate
    }

    fun installIntoKeyChain() {
        val cert = loadCertificate() ?: throw IllegalStateException("No certificate stored")
        val alias = "ASOP Terminal"

        try {
            KeyChain.createInstallIntent().apply {
                putExtra(KeyChain.EXTRA_CERTIFICATE, cert.encoded)
                putExtra(KeyChain.EXTRA_NAME, alias)
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(this)
            }
        } catch (_: Exception) {
            // Fallback: cert already stored in app prefs + AndroidKeyStore
        }
    }
}
