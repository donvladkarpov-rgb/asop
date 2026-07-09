package ru.asop.crypto.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.asop.crypto.config.RootCaProperties
import java.security.PublicKey
import java.security.cert.X509Certificate

enum class SmartCardRole {
    PASSENGER_ANONYMOUS,
    PASSENGER_BENEFIT,
    DRIVER,
    CONTROLLER,
    DISPATCHER,
    CARRIER_ADMIN,
    REGION_ADMIN,
    SUPER_ADMIN,
    DISTRIBUTOR_ADMIN,
    DISTRIBUTOR_TERMINAL,
    SERVICE
}

@Service
class SmartCardCertService(
    private val rootCaService: RootCaService,
    private val properties: RootCaProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun issueSmartCardCertificate(
        cardId: String,
        role: SmartCardRole,
        carrierId: String?,
        publicKey: PublicKey
    ): X509Certificate {
        // Выбираем DN-шаблон по роли
        val template = properties.smartCardCert.dnTemplates[role.name]
            ?: properties.smartCardCert.dnTemplates["DEFAULT"]
            ?: throw IllegalArgumentException("No DN template for role: $role")

        // Формируем DN
        val dn = template
            .replace("{cardId}", cardId)
            .replace("{role}", role.name)
            .replace("{carrierId}", carrierId ?: "none")

        log.info("Issuing smart card certificate: cardId={}, role={}, dn={}", cardId, role, dn)

        val certificate = rootCaService.signCertificate(
            publicKey = publicKey,
            dn = dn,
            validityYears = properties.smartCardCert.validityYears
        )

        log.info("Smart card certificate issued. Serial: {}, valid until: {}",
            certificate.serialNumber.toString(16), certificate.notAfter)

        return certificate
    }
}