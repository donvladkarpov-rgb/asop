package ru.asop.crypto.controller

import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono
import ru.asop.crypto.dto.TerminalCertRequest
import ru.asop.crypto.dto.TerminalCertResponse
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
) {

    /**
     * POST /api/v1/terminals/register
     * Регистрация терминала и выпуск сертификата
     */
    @PostMapping("/register")
    fun registerTerminal(
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

    /**
     * GET /api/v1/terminals/root-ca
     * Получить публичный сертификат Root CA в формате PEM.
     * Используется терминалами для проверки цепочки доверия.
     *
     * ВАЖНО: MediaType.APPLICATION_PEM_CERTIFICATE_VALUE отсутствует в Spring 6.1.x,
     * поэтому используем строковый литерал "application/x-pem-file".
     */
    @GetMapping("/root-ca", produces = ["application/x-pem-file"])
    fun getRootCaCertificate(): Mono<ResponseEntity<String>> {
        return Mono.fromCallable {
            val certificate = rootCaService.getRootCaCertificate()
            val pem = certificateToPem(certificate)

            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"asop-root-ca.pem\"")
                .body(pem)
        }
    }

    /**
     * GET /api/v1/terminals/root-ca/der
     * Получить Root CA в бинарном формате DER (для программной обработки)
     */
    @GetMapping("/root-ca/der", produces = [MediaType.APPLICATION_OCTET_STREAM_VALUE])
    fun getRootCaCertificateDer(): Mono<ResponseEntity<ByteArray>> {
        return Mono.fromCallable {
            val certificate = rootCaService.getRootCaCertificate()

            ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"asop-root-ca.der\"")
                .body(certificate.encoded)
        }
    }

    /**
     * GET /api/v1/terminals/root-ca/public-key
     * Получить только публичный ключ Root CA (если кому-то нужен только ключ)
     */
    @GetMapping("/root-ca/public-key", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun getRootCaPublicKey(): Mono<ResponseEntity<Map<String, String>>> {
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

    /**
     * Конвертация X.509 сертификата в PEM формат
     */
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