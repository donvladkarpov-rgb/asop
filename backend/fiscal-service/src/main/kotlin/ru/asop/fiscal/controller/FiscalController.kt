package ru.asop.fiscal.controller

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import ru.asop.api.fiscal.controller.FiscalApi
import ru.asop.api.fiscal.dto.request.FiscalReceiptRequest
import ru.asop.api.fiscal.dto.response.FiscalReceiptResponse
import ru.asop.fiscal.service.FiscalService
import java.security.Principal
import java.util.UUID

@RestController
class FiscalController(
    private val fiscalService: FiscalService
) : FiscalApi {

    override fun requestReceipt(
        request: FiscalReceiptRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<FiscalReceiptResponse>> {
        return fiscalService.requestReceipt(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun getReceipt(id: UUID): Mono<ResponseEntity<FiscalReceiptResponse>> {
        return fiscalService.getById(id)
            .map { ResponseEntity.ok(it) }
    }
}
