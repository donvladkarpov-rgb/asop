package ru.asop.api.reference.controller

import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.reference.dto.request.TransactionTypeCreateRequest
import ru.asop.api.reference.dto.request.TransactionTypeUpdateRequest
import ru.asop.api.reference.dto.response.TransactionTypeResponse
import java.util.UUID

@RequestMapping("/api/v1/transaction-types")
interface TransactionTypeApi {

    @GetMapping
    fun listTransactionTypes(): Mono<ResponseEntity<List<TransactionTypeResponse>>>

    @GetMapping("/{id}")
    fun getTransactionType(@PathVariable id: UUID): Mono<ResponseEntity<TransactionTypeResponse>>

    @PostMapping
    fun createTransactionType(@Valid @RequestBody request: TransactionTypeCreateRequest): Mono<ResponseEntity<TransactionTypeResponse>>

    @PutMapping("/{id}")
    fun updateTransactionType(@PathVariable id: UUID, @Valid @RequestBody request: TransactionTypeUpdateRequest): Mono<ResponseEntity<TransactionTypeResponse>>

    @DeleteMapping("/{id}")
    fun deleteTransactionType(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
