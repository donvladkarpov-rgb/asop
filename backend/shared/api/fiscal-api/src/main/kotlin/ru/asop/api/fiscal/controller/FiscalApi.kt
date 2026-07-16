package ru.asop.api.fiscal.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.fiscal.dto.request.FiscalReceiptRequest
import ru.asop.api.fiscal.dto.response.FiscalReceiptResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/fiscal")
interface FiscalApi {

    @PostMapping("/receipts")
    fun requestReceipt(
        @Valid @RequestBody request: FiscalReceiptRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<FiscalReceiptResponse>>

    @GetMapping("/receipts/{id}")
    fun getReceipt(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<FiscalReceiptResponse>>
}
