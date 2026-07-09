package ru.asop.admin.controller

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import reactor.core.publisher.Mono
import ru.asop.admin.model.TransactionResultEntity
import ru.asop.admin.repository.TransactionResultRepository
import ru.asop.api.reference.controller.TransactionResultApi
import ru.asop.api.reference.dto.request.TransactionResultCreateRequest
import ru.asop.api.reference.dto.request.TransactionResultUpdateRequest
import ru.asop.api.reference.dto.response.TransactionResultResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@RestController
class TransactionResultController(
    private val repository: TransactionResultRepository,
    private val template: R2dbcEntityTemplate
) : TransactionResultApi {

    override fun listTransactionResults(): Mono<ResponseEntity<List<TransactionResultResponse>>> {
        return repository.findAll()
            .map { it.toResponse() }
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    override fun getTransactionResult(id: UUID): Mono<ResponseEntity<TransactionResultResponse>> {
        return repository.findById(id)
            .map { ResponseEntity.ok(it.toResponse()) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun createTransactionResult(request: TransactionResultCreateRequest): Mono<ResponseEntity<TransactionResultResponse>> {
        val entity = TransactionResultEntity(
            transactionResultId = UuidUtils.newId(),
            transactionResultName = request.transactionResultName
        )
        return template.insert(entity)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) }
    }

    override fun updateTransactionResult(id: UUID, request: TransactionResultUpdateRequest): Mono<ResponseEntity<TransactionResultResponse>> {
        return repository.findById(id)
            .flatMap { existing ->
                val updated = existing.copy(
                    transactionResultName = request.transactionResultName ?: existing.transactionResultName
                )
                repository.save(updated).map { ResponseEntity.ok(it.toResponse()) }
            }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    override fun deleteTransactionResult(id: UUID): Mono<ResponseEntity<Void>> {
        return repository.deleteById(id).thenReturn(ResponseEntity.noContent().build())
    }

    private fun TransactionResultEntity.toResponse() = TransactionResultResponse(
        id = transactionResultId,
        transactionResultName = transactionResultName
    )
}
