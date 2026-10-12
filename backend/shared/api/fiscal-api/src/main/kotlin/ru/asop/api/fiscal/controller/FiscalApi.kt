package ru.asop.api.fiscal.controller

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import reactor.core.publisher.Mono
import ru.asop.api.fiscal.dto.FiscalReceiptStatusDto
import java.util.UUID

@RequestMapping("/api/v1/fiscal")
interface FiscalApi {
    @GetMapping("/receipts/{receiptId}/status")
    fun getReceiptStatus(@PathVariable receiptId: UUID): Mono<FiscalReceiptStatusDto>
}
