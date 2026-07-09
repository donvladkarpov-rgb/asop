package ru.asop.api.crypto.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.crypto.dto.request.SmartCardCertRequest
import ru.asop.api.crypto.dto.response.SmartCardCertResponse

@RequestMapping("/api/v1/smart-cards")
interface SmartCardApi {

    @PostMapping("/issue")
    fun issueSmartCard(
        @Valid @RequestBody request: SmartCardCertRequest
    ): Mono<ResponseEntity<SmartCardCertResponse>>
}