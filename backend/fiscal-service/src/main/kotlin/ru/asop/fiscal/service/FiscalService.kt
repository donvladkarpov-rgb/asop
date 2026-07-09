package ru.asop.fiscal.service

import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.fiscal.dto.request.FiscalReceiptRequest
import ru.asop.api.fiscal.dto.response.FiscalReceiptResponse
import ru.asop.fiscal.model.FiscalReceiptEntity
import ru.asop.fiscal.repository.FiscalReceiptRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

@Service
class FiscalService(
    private val fiscalReceiptRepository: FiscalReceiptRepository
) {

    fun requestReceipt(request: FiscalReceiptRequest): Mono<FiscalReceiptResponse> {
        val now = Instant.now()
        val entity = FiscalReceiptEntity(
            receiptId = UuidUtils.newId(),
            transactionId = request.transactionId,
            amount = request.amount,
            status = "PENDING",
            createdAt = now,
            updatedAt = now
        )
        return fiscalReceiptRepository.save(entity).map { it.toResponse() }
    }

    fun getById(id: UUID): Mono<FiscalReceiptResponse> {
        return fiscalReceiptRepository.findById(id).map { it.toResponse() }
    }
}

private fun FiscalReceiptEntity.toResponse() = FiscalReceiptResponse(
    id = receiptId,
    transactionId = transactionId,
    amount = amount,
    status = status,
    fiscalNumber = fiscalNumber,
    createdAt = createdAt,
    updatedAt = updatedAt
)
