package ru.asop.fiscal.adapter

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class BifitAdapter(private val objectMapper: ObjectMapper) : FiscalizerAdapter {
    override fun providerCode() = "BIFIT"

    override fun buildRequest(ctx: FiscalContext): Map<String, Any?> {
        return ctx.fiscalContext
    }

    override fun parseResponse(status: Int, body: String?): FiscalizerResult {
        if (body.isNullOrBlank()) return FiscalizerResult(success = status in 200..299, httpStatus = status)
        return try {
            val node = objectMapper.readTree(body)
            val receiptNumber = node.path("receiptNumber").asText(null) ?: node.path("number").asText(null)
            val fiscalSign = node.path("fiscalSign").asText(null) ?: node.path("fs").asText(null) ?: node.path("fn").asText(null)
            FiscalizerResult(
                success = status in 200..299,
                receiptNumber = receiptNumber,
                fiscalSign = fiscalSign,
                responseData = body,
                httpStatus = status
            )
        } catch (e: Exception) {
            FiscalizerResult(success = status in 200..299, responseData = body, httpStatus = status)
        }
    }

    override fun receiptUrl(receiptId: UUID, baseUrl: String?, customUrl: String?): String {
        // Промпт 017: страница чека = baseApiUrl (хост ОФД) + /processing-api/receipts/{id}.
        // В dev seed baseApiUrl=http://mock-fiscal:8089 → URL указывает на mock-ОФД;
        // в prod host-root Bifit даёт https://fp.bifit.com/processing-api/receipts/{id}.
        val base = (customUrl ?: baseUrl)?.trimEnd('/')
        return if (!base.isNullOrBlank()) {
            "$base/processing-api/receipts/$receiptId"
        } else {
            "https://fp.bifit.com/processing-api/receipts/$receiptId"
        }
    }
}
