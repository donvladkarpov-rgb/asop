package ru.asop.terminal.cert

import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.X509KeyManager

class AsopKeyManager(
    private val mtlsManager: MtlsManager
) : X509KeyManager {

    private val certChain: Array<X509Certificate>?
        get() = mtlsManager.loadCertificateChain()?.toTypedArray()

    private val privateKey: PrivateKey?
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val entry = ks.getEntry(MTLS_KEY_ALIAS, null) as? KeyStore.PrivateKeyEntry
            return entry?.privateKey
        }

    override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
        return if (certChain != null && privateKey != null) arrayOf(MTLS_KEY_ALIAS) else null
    }

    override fun chooseClientAlias(
        keyType: Array<out String>?,
        issuers: Array<out Principal>?,
        socket: Socket?
    ): String? {
        return if (certChain != null && privateKey != null) MTLS_KEY_ALIAS else null
    }

    override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

    override fun chooseServerAlias(
        keyType: String?,
        issuers: Array<out Principal>?,
        socket: Socket?
    ): String? = null

    override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
        return if (alias == MTLS_KEY_ALIAS) certChain else null
    }

    override fun getPrivateKey(alias: String?): PrivateKey? {
        return if (alias == MTLS_KEY_ALIAS) privateKey else null
    }

    companion object {
        const val MTLS_KEY_ALIAS = "asop_terminal"
    }
}
