package ru.asop.crypto.controller

import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.crypto.controller.ServerCertApi
import ru.asop.api.crypto.dto.request.ServerCertRequest
import ru.asop.api.crypto.dto.response.ServerCertResponse
import ru.asop.crypto.service.RootCaService
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@RestController
class ServerCertController(
    private val rootCaService: RootCaService
) : ServerCertApi {

    override fun issueServerCertificate(
        request: ServerCertRequest
    ): Mono<ResponseEntity<ServerCertResponse>> {
        return Mono.fromCallable {
            val keyBytes = Base64.getDecoder().decode(request.publicKeyBase64)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)

            val dn = "CN=${request.commonName}, O=ASOP"
            val certificate = rootCaService.signCertificate(
                publicKey = publicKey,
                dn = dn,
                validityYears = 1,
                dnsNames = request.dnsNames
            )

            val certBase64 = Base64.getEncoder().encodeToString(certificate.encoded)

            ServerCertResponse(
                certificateBase64 = certBase64,
                serialNumber = certificate.serialNumber.toString(16),
                validFrom = certificate.notBefore.toInstant(),
                validUntil = certificate.notAfter.toInstant()
            )
        }.map { response ->
            ResponseEntity.ok(response)
        }
    }

    @GetMapping("ca-chain", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getCaChain(): Mono<ResponseEntity<String>> {
        return Mono.fromCallable {
            val rootCa = rootCaService.getRootCaCertificate()
            val intermediateCa = rootCaService.getIntermediateCaCertificate()

            """
-----BEGIN CERTIFICATE-----
${pemEncode(intermediateCa)}
-----END CERTIFICATE-----
-----BEGIN CERTIFICATE-----
${pemEncode(rootCa)}
-----END CERTIFICATE-----
""".trimIndent()
        }.map { ResponseEntity.ok(it) }
    }

    private fun pemEncode(cert: X509Certificate): String {
        return Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(cert.encoded)
    }
}
