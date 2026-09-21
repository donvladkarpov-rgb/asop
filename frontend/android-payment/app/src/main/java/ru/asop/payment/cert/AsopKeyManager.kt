package ru.asop.payment.cert

import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

/**
 * X509KeyManager, отдающий сертификат app-payment (порт AsopKeyManager из distributor).
 */
class AsopKeyManager(
    private val certManager: CertManager
) : X509KeyManager {

    private val certChain: Array<X509Certificate>?
        get() = certManager.loadCertificateChain()?.toTypedArray()

    private val privateKey: PrivateKey?
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = ks.getEntry(CertManager.KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            return entry?.privateKey
        }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
        return if (certChain != null && privateKey != null) arrayOf(CertManager.KEY_ALIAS) else null
    }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?
    ): String? {
        return if (certChain != null && privateKey != null) CertManager.KEY_ALIAS else null
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        return if (alias == CertManager.KEY_ALIAS) certChain else null
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        return if (alias == CertManager.KEY_ALIAS) privateKey else null
    }
}
