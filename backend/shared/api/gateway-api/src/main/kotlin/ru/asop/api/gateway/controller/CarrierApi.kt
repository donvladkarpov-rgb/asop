package ru.asop.api.gateway.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.gateway.dto.response.AcceptedResponse
import ru.asop.api.gateway.dto.request.CarrierCreateRequest
import java.security.Principal

@RequestMapping("/api/v1/carriers")
interface CarrierApi {

    @PostMapping
    fun createCarrier(
        @Valid @RequestBody request: CarrierCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<AcceptedResponse>>
}