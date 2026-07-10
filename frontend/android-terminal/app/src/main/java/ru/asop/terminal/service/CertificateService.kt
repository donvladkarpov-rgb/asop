package ru.asop.terminal.service

import ru.asop.terminal.cert.MtlsManager
import ru.asop.terminal.network.CryptoApi
import ru.asop.terminal.network.models.TerminalCertRequest
import ru.asop.terminal.network.models.TerminalCertResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CertificateService @Inject constructor(
    private val mtlsManager: MtlsManager,
    private val cryptoApi: CryptoApi
) {

    enum class State {
        NEEDS_PROVISIONING,
        KEYPAIR_READY,
        CERTIFICATE_READY
    }

    fun getState(): State = when {
        !mtlsManager.hasKeyPair() -> State.NEEDS_PROVISIONING
        !mtlsManager.hasCertificate() -> State.KEYPAIR_READY
        else -> State.CERTIFICATE_READY
    }

    suspend fun provision(terminalSerial: String): TerminalCertResponse {
        mtlsManager.generateKeyPair()
        val publicKeyB64 = mtlsManager.getPublicKeyBase64()

        val response = cryptoApi.requestCertificate(
            TerminalCertRequest(
                terminalSerial = terminalSerial,
                carrierId = "00000000-0000-0000-0000-000000000000",
                publicKeyBase64 = publicKeyB64
            )
        )

        val certPem = response.certificateBase64
        mtlsManager.storeCertificate(certPem)
        mtlsManager.installIntoKeyChain()
        return response
    }
}
