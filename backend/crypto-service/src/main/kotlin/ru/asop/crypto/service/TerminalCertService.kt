package ru.asop.crypto.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.asop.crypto.config.RootCaProperties
import java.security.PublicKey
import java.security.cert.X509Certificate

@Service
class TerminalCertService(
    private val rootCaService: RootCaService,
    private val properties: RootCaProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Выпуск сертификата для терминала.
     *
     * @param terminalSerial Серийный номер терминала
     * @param carrierId ID перевозчика
     * @param publicKey Публичный ключ терминала (из CSR или напрямую)
     * @return Подписанный X.509 сертификат
     */
    fun issueTerminalCertificate(
        terminalSerial: String,
        carrierId: String,
        publicKey: PublicKey
    ): X509Certificate {
        // Формируем DN из шаблона
        val dn = properties.terminalCert.dnTemplate
            .replace("{terminalSerial}", terminalSerial)
            .replace("{carrierId}", carrierId)

        log.info("Issuing terminal certificate for serial={}, carrierId={}, dn={}",
            terminalSerial, carrierId, dn)

        // Делегируем подпись RootCaService
        val certificate = rootCaService.signCertificate(
            publicKey = publicKey,
            dn = dn,
            validityYears = properties.terminalCert.validityYears
        )

        log.info("Terminal certificate issued. Serial: {}, valid until: {}",
            certificate.serialNumber.toString(16), certificate.notAfter)

        return certificate
    }
}