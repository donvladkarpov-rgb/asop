package ru.asop.distributor.network.models

/**
 * Серверный контур дистрибьютора: register + прямые JSON-/delta глобальных справочников.
 */

data class DistributorRegisterRequest(
    val terminalSerial: String,
    val terminalNumber: String? = null,
    val terminalModel: String? = null,
    val cardsDistributorId: String? = null,
    val paymentProviderId: String? = null,
    val status: String? = null
)

data class DistributorTerminalResponse(
    val distributorTerminalId: String,
    val cardsDistributorId: String,
    val terminalNumber: String,
    val terminalSerial: String,
    val paymentProviderId: String,
    val status: String,
    val terminalModel: String? = null,
    val contractId: String? = null,
    val molUserId: String? = null,
    val profileId: String? = null,
    val softwareVersionId: String? = null,
    val createdAt: String,
    val updatedAt: String
)

/** ASOP_KEYS.(KEY_ID, KEY_MATERIAL ciphertext, ..., VERSION). */
data class AsopKeyRow(
    val keyId: String,
    val keyMaterial: String,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val version: Long? = null
)

/** ASOP_TARIFF_RATES row с CamelCase полями. */
data class TariffRateRow(
    val tariffRateId: String,
    val tariffTypeId: String,
    val carrierId: String? = null,
    val zoneId: String? = null,
    val pathId: String? = null,
    val price: Double,
    val description: String? = null,
    val isActive: Boolean = true,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val version: Long? = null
)

/** ASOP_FISCALIZERS row (admin `/fiscalizers/delta`, camelCase). Промпт 017. */
data class FiscalizerRow(
    val fiscalizerId: String,
    val fiscalizerCode: String,
    val fiscalizerName: String? = null,
    val baseApiUrl: String? = null,
    val description: String? = null,
    val isActive: Boolean = true,
    val createdAt: String? = null,
    val updatedAt: String? = null,
    val deletedAt: String? = null,
    val version: Long? = null
)

/** ASOP_DISTRIBUTOR_FISCALIZERS row (admin `/distributor-fiscalizers/delta`, camelCase). Промпт 017. */
data class DistributorFiscalizerRow(
    val distributorFiscalizerId: String,
    val cardsDistributorId: String,
    val fiscalizerId: String,
    val contractId: String? = null,
    val customApiUrl: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val deletedAt: String? = null,
    val version: Long? = null
)

/**
 * Запрос фискализации чека дистрибьютора (`POST /api/v1/sync/fiscal/receipts`).
 * `source=DISTRIBUTOR`, `transactionId` не передаётся (у дистрибьютора нет
 * серверной транзакции), `cardsDistributorId` задаёт владельца и фискализатора.
 */
data class FiscalReceiptRequest(
    val receiptId: String,
    val amount: Double,
    val description: String? = null,
    val regionId: String? = null,
    val carrierId: String? = null,
    val sessionId: String? = null,
    val cardsDistributorId: String? = null,
    val source: String = "DISTRIBUTOR",
    val acqReference: String? = null,
    val rrn: String? = null,
    val timezone: String? = null
)

/** Ответ async-команды gateway (`202 Accepted`) — см. CertModels.AcceptedResponse. */