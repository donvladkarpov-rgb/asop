package ru.asop.distributor.network.models

/**
 * Cert-sign контур (порт из терминала). Поля camelCase соответствуют JSON backend'а.
 */

data class CertSignRequest(
    val terminalSerial: String,
    val terminalNumber: String? = null,
    val terminalModel: String? = null,
    val carrierId: String? = null,
    val terminalId: String? = null,
    val distributor: Boolean = true,
    val cardsDistributorId: String? = null,
    val paymentProviderId: String? = null,
    val publicKeyBase64: String
)

data class AcceptedResponse(
    val eventId: String,
    val topic: String,
    val acceptedAt: String,
    val locationHint: String? = null
)

data class EventStatusResponse(
    val eventId: String,
    val commandTopic: String,
    val state: String,
    val resultData: String? = null,
    val errorMessage: String? = null,
    val createdAt: String,
    val completedAt: String? = null
)

data class CertStoredResult(
    val certId: String,
    val terminalId: String,
    val terminalNumber: String? = null,
    val certSerial: String,
    val certificateBase64: String,
    val validFrom: String,
    val validUntil: String,
    val caChain: String
)