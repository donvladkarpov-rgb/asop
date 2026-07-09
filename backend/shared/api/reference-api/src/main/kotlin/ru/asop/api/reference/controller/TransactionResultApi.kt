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
import ru.asop.api.reference.dto.request.TransactionResultCreateRequest
import ru.asop.api.reference.dto.request.TransactionResultUpdateRequest
import ru.asop.api.reference.dto.response.TransactionResultResponse
import java.util.UUID

@RequestMapping("/api/v1/transaction-results")
interface TransactionResultApi {

    @GetMapping
    fun listTransactionResults(): Mono<ResponseEntity<List<TransactionResultResponse>>>

    @GetMapping("/{id}")
    fun getTransactionResult(@PathVariable id: UUID): Mono<ResponseEntity<TransactionResultResponse>>

    @PostMapping
    fun createTransactionResult(@Valid @RequestBody request: TransactionResultCreateRequest): Mono<ResponseEntity<TransactionResultResponse>>

    @PutMapping("/{id}")
    fun updateTransactionResult(@PathVariable id: UUID, @Valid @RequestBody request: TransactionResultUpdateRequest): Mono<ResponseEntity<TransactionResultResponse>>

    @DeleteMapping("/{id}")
    fun deleteTransactionResult(@PathVariable id: UUID): Mono<ResponseEntity<Void>>
}
