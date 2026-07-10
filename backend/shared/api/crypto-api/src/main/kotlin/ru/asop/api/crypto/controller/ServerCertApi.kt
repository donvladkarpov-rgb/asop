package ru.asop.api.crypto.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.crypto.dto.request.ServerCertRequest
import ru.asop.api.crypto.dto.response.ServerCertResponse

@RequestMapping("/api/v1/certificates")
interface ServerCertApi {

    @PostMapping("/server")
    fun issueServerCertificate(
        @Valid @RequestBody request: ServerCertRequest
    ): Mono<ResponseEntity<ServerCertResponse>>
}
