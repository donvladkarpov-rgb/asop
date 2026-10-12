package ru.asop.fiscal.mock

import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono
import java.util.UUID

@RestController
class MockFiscalController {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/processing-api/protected/documents/registration/receipts/token")
    fun registerReceipt(
        @RequestHeader("Idempotency-Key") idempotencyKey: String,
        @RequestBody body: JsonNode
    ): Mono<Map<String, Any>> {
        log.info("MOCK BIFIT: received receipt {}", idempotencyKey)
        val receiptNumber = UUID.randomUUID().toString().substring(0, 8).uppercase()
        return Mono.just(
            mapOf(
                "status" to "OK",
                "receiptNumber" to receiptNumber,
                "fn" to "fn-$receiptNumber",
                "fs" to "fs-$receiptNumber"
            )
        )
    }
}
