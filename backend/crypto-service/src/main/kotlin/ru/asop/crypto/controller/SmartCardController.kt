package ru.asop.crypto.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import ru.asop.crypto.service.SmartCardCertService
import ru.asop.crypto.service.SmartCardRole
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import ru.asop.api.crypto.dto.request.SmartCardCertRequest
import ru.asop.api.crypto.dto.response.SmartCardCertResponse

@RestController
@RequestMapping("/api/v1/smart-cards")
class SmartCardController(
    private val smartCardCertService: SmartCardCertService
) {

    @PostMapping("/issue")
    fun issueSmartCard(
        @RequestBody request: SmartCardCertRequest
    ): Mono<ResponseEntity<SmartCardCertResponse>> {
        return Mono.fromCallable {
            val keyBytes = Base64.getDecoder().decode(request.publicKeyBase64)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)

            val role = SmartCardRole.valueOf(request.cardRole)

            val certificate = smartCardCertService.issueSmartCardCertificate(
                cardId = request.cardId,
                role = role,
                carrierId = request.carrierId,
                publicKey = publicKey
            )

            val certBase64 = Base64.getEncoder().encodeToString(certificate.encoded)

            SmartCardCertResponse(
                certificateBase64 = certBase64,
                serialNumber = certificate.serialNumber.toString(16),
                subjectDn = certificate.subjectX500Principal.name,
                validFrom = certificate.notBefore.toInstant(),
                validUntil = certificate.notAfter.toInstant()
            )
        }.map { response ->
            ResponseEntity.ok(response)
        }
    }
}