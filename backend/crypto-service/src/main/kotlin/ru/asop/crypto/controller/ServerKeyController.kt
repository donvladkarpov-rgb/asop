package ru.asop.crypto.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.crypto.controller.ServerKeyApi
import ru.asop.api.crypto.dto.request.DecryptRequest
import ru.asop.api.crypto.dto.response.DecryptResponse
import ru.asop.api.crypto.dto.response.Generate3desKeyResponse
import ru.asop.api.crypto.dto.response.ServerKeyPublicResponse
import ru.asop.crypto.service.ServerKeyService
import java.util.Base64

@RestController
class ServerKeyController(
    private val serverKeyService: ServerKeyService
) : ServerKeyApi {

    override fun getServerPublicKey(): Mono<ResponseEntity<ServerKeyPublicResponse>> {
        return Mono.fromCallable {
            val publicKey = serverKeyService.getPublicKey()
            ServerKeyPublicResponse(
                algorithm = publicKey.algorithm,
                format = publicKey.format,
                publicKeyBase64 = Base64.getEncoder().encodeToString(publicKey.encoded)
            )
        }.map { ResponseEntity.ok(it) }
    }

    override fun decrypt(@RequestBody request: DecryptRequest): Mono<ResponseEntity<DecryptResponse>> {
        return Mono.fromCallable {
            DecryptResponse(serverKeyService.decrypt3desKey(request.cipherBase64))
        }.map { ResponseEntity.ok(it) }
            .onErrorResume {
                Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST).build())
            }
    }

    override fun generate3desKey(): Mono<ResponseEntity<Generate3desKeyResponse>> {
        return Mono.fromCallable {
            val (keyId, cipherBase64) = serverKeyService.generate3desKey()
            Generate3desKeyResponse(keyId = keyId, cipherBase64 = cipherBase64)
        }.map { ResponseEntity.ok(it) }
    }
}