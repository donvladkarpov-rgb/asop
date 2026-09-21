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