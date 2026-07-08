package ru.asop.api.debt.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.debt.dto.request.DebtCreateRequest
import ru.asop.api.debt.dto.response.DebtResponse
import java.security.Principal
import java.util.UUID

@RequestMapping("/api/v1/debts")
interface DebtApi {

    @PostMapping
    fun createDebt(
        @Valid @RequestBody request: DebtCreateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<DebtResponse>>

    @GetMapping("/{id}")
    fun getDebt(
        @PathVariable id: UUID
    ): Mono<ResponseEntity<DebtResponse>>
}
