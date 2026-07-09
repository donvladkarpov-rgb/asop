package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.TransactionTypeEntity
import ru.asop.admin.repository.TransactionTypeRepository
import ru.asop.api.reference.controller.TransactionTypeApi
import ru.asop.api.reference.dto.request.TransactionTypeCreateRequest
import ru.asop.api.reference.dto.request.TransactionTypeUpdateRequest
import ru.asop.api.reference.dto.response.TransactionTypeResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class TransactionTypeController(
    private val repository: TransactionTypeRepository,
    private val template: R2dbcEntityTemplate
) : TransactionTypeApi {

    override fun listTransactionTypes(): Mono<ResponseEntity<List<TransactionTypeResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getTransactionType(id: UUID): Mono<ResponseEntity<TransactionTypeResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createTransactionType(request: TransactionTypeCreateRequest): Mono<ResponseEntity<TransactionTypeResponse>> {
        val entity = TransactionTypeEntity(
            transactionTypeId = UuidUtils.newId(),
            transactionTypeName = request.transactionTypeName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateTransactionType(id: UUID, request: TransactionTypeUpdateRequest): Mono<ResponseEntity<TransactionTypeResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    transactionTypeName = request.transactionTypeName ?: existing.transactionTypeName
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteTransactionType(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun TransactionTypeEntity.toResponse() = TransactionTypeResponse(
        id = transactionTypeId,
        transactionTypeName = transactionTypeName
    )
}
