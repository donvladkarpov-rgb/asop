package ru.asop.crypto.controller

import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import ru.asop.api.crypto.controller.TerminalCertApi
import ru.asop.api.crypto.dto.request.TerminalCertRequest
import ru.asop.api.crypto.dto.response.TerminalCertResponse
import ru.asop.crypto.service.RootCaService
import ru.asop.crypto.service.TerminalCertService
import java.security.KeyFactory
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

@RestController
@RequestMapping("/api/v1/terminals")
class TerminalCertController(
    private val terminalCertService: TerminalCertService,
    private val rootCaService: RootCaService
) : TerminalCertApi {

    override fun registerTerminal(
        @RequestBody request: TerminalCertRequest
    ): Mono<ResponseEntity<TerminalCertResponse>> {
        return Mono.fromCallable {
            val keyBytes = Base64.getDecoder().decode(request.publicKeyBase64)
            val keySpec = X509EncodedKeySpec(keyBytes)
            val keyFactory = KeyFactory.getInstance("EC")
            val publicKey: PublicKey = keyFactory.generatePublic(keySpec)

            val certificate = terminalCertService.issueTerminalCertificate(
                terminalSerial = request.terminalSerial,
                carrierId = request.carrierId,
                publicKey = publicKey
            )

            val certBase64 = Base64.getEncoder().encodeToString(certificate.encoded)

            TerminalCertResponse(
                certificateBase64 = certBase64,
                serialNumber = certificate.serialNumber.toString(16),
                validFrom = certificate.notBefore.toInstant(),
                validUntil = certificate.notAfter.toInstant()
            )
        }.map { response ->
            ResponseEntity.ok(response)
        }
    }

    override fun getRootCaCertificate(): Mono<ResponseEntity<String>> {
        return Mono.fromCallable {
            val certificate = rootCaService.getRootCaCertificate()
            val pem = certificateToPem(certificate)

            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"asop-root-ca.pem\"")
                .body(pem)
        }
    }

    override fun getRootCaCertificateDer(): Mono<ResponseEntity<ByteArray>> {
        return Mono.fromCallable {
            val certificate = rootCaService.getRootCaCertificate()

            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"asop-root-ca.der\"")
                .body(certificate.encoded)
        }
    }

    override fun getRootCaPublicKey(): Mono<ResponseEntity<Map<String, String>>> {
        return Mono.fromCallable {
            val certificate = rootCaService.getRootCaCertificate()
            val publicKey = certificate.publicKey

            mapOf(
                "algorithm" to publicKey.algorithm,
                "format" to publicKey.format,
                "publicKeyBase64" to Base64.getEncoder().encodeToString(publicKey.encoded)
            )
        }.map { response ->
            ResponseEntity.ok(response)
        }
    }

    private fun certificateToPem(certificate: X509Certificate): String {
        val base64Cert = Base64.getMimeEncoder(64, "\n".toByteArray())
            .encodeToString(certificate.encoded)

        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64Cert)
            append("\n-----END CERTIFICATE-----\n")
        }
    }
}