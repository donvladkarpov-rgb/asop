package ru.asop.fiscal.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.fiscal.controller.FiscalApi
import ru.asop.api.fiscal.dto.FiscalReceiptStatusDto
import ru.asop.fiscal.service.FiscalReceiptService
import java.util.UUID

@RestController
class FiscalController(
    private val fiscalService: FiscalReceiptService
) : FiscalApi {

    override fun getReceiptStatus(receiptId: UUID): Mono<FiscalReceiptStatusDto> {
        return fiscalService.getStatus(receiptId).map { r ->
            FiscalReceiptStatusDto(
                receiptId = r.receiptId,
                status = r.status,
                fiscalUrl = r.fiscalUrl,
                receiptNumber = r.receiptNumber,
                confirmedAt = r.confirmedAt
            )
        }
    }
}
