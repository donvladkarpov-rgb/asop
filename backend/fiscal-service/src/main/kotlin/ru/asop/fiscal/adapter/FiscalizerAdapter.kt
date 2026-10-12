package ru.asop.fiscal.adapter

import java.util.UUID

data class FiscalizerResult(
    val success: Boolean,
    val receiptNumber: String? = null,
    val fiscalSign: String? = null,
    val fiscalUrl: String? = null,
    val responseData: String? = null,
    val errorMessage: String? = null,
    val httpStatus: Int? = null
)

interface FiscalizerAdapter {
    fun providerCode(): String
    fun buildRequest(ctx: FiscalContext): Map<String, Any?>
    fun parseResponse(status: Int, body: String?): FiscalizerResult
    fun receiptUrl(receiptId: UUID, baseUrl: String?, customUrl: String?): String

    /** Путь регистрации чека относительно base URL фискализатора. */
    fun registrationPath(): String = "/processing-api/protected/documents/registration/receipts/token"
}

data class FiscalContext(
    val receiptId: UUID,
    val transactionId: UUID? = null,
    val total: java.math.BigDecimal? = null,
    val fiscalContext: Map<String, Any?> = emptyMap()
)
