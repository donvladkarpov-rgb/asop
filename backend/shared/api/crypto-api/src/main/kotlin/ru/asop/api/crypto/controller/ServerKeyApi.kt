package ru.asop.api.crypto.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.crypto.dto.request.DecryptRequest
import ru.asop.api.crypto.dto.response.DecryptResponse
import ru.asop.api.crypto.dto.response.ServerKeyPublicResponse
import ru.asop.api.crypto.dto.response.Generate3desKeyResponse

@RequestMapping("/api/v1/keys")
interface ServerKeyApi {

    @GetMapping("/public", produces = ["application/json"])
    fun getServerPublicKey(): Mono<ResponseEntity<ServerKeyPublicResponse>>

    @PostMapping("/decrypt", produces = ["application/json"])
    fun decrypt(
        @RequestBody request: DecryptRequest
    ): Mono<ResponseEntity<DecryptResponse>>

    @PostMapping("/generate", produces = ["application/json"])
    fun generate3desKey(): Mono<ResponseEntity<Generate3desKeyResponse>>
}