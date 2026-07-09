package ru.asop.api.crypto.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.crypto.dto.request.TerminalCertRequest
import ru.asop.api.crypto.dto.response.TerminalCertResponse

@RequestMapping("/api/v1/terminals")
interface TerminalCertApi {

    @PostMapping("/register")
    fun registerTerminal(
        @RequestBody request: TerminalCertRequest
    ): Mono<ResponseEntity<TerminalCertResponse>>

    @GetMapping("/root-ca", produces = ["application/x-pem-file"])
    fun getRootCaCertificate(): Mono<ResponseEntity<String>>

    @GetMapping("/root-ca/der", produces = ["application/octet-stream"])
    fun getRootCaCertificateDer(): Mono<ResponseEntity<ByteArray>>

    @GetMapping("/root-ca/public-key", produces = ["application/json"])
    fun getRootCaPublicKey(): Mono<ResponseEntity<Map<String, String>>>
}