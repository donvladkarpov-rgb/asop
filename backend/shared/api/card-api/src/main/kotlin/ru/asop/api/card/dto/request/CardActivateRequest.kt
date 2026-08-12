package ru.asop.api.card.dto.request

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import java.util.UUID

data class CardIdentityDto(
    @field:NotNull val cardId: UUID,
    @field:NotEmpty val uid: String,
    val regionId: UUID? = null,
    val organizerId: UUID? = null,
    val carrierId: UUID? = null,
    val cardsDistributorId: UUID? = null,
    val auditServiceId: UUID? = null,
    val userId: UUID? = null,
    @field:NotEmpty val roles: List<String>
)

/**
 * VCM1-формат для MIFARE Classic карт (промпт 008).
 * Терминал шлёт client-generated cardId (UUID v7, по UTC дате-времени).
 * Сервер является master-cardId authority: если карта уже активирована по UID,
 * сервер возвращает СВОЙ serverCardId вместо echo. Терминал обязан перезаписать block 1
 * на карте, если serverCardId != clientCardId.
 *
 * `entity` — single-slot UUID для highest-bit role (см. card-api/VCM1 contract).
 * Дополнительные роли из bitmask хранятся на сервере через ASOP_USER_ROLES /
 * ASOP_USER_CARRIERS / ASOP_USER_REGIONS (связи восстанавливаются по user_id).
 */
data class CardActivateRequestClassic(
    @field:NotBlank val technology: String,     // "CLASSIC"
    @field:NotNull val cardId: UUID,             // client-generated UUID v7
    @field:NotBlank val uid: String,             // MIFARE UID, hex
    @field:NotNull val bitmask: Int,            // UInt16 (0..0x3FFF)
    @field:NotBlank val entityType: String,     // "userId"|"regionId"|"organizerId"|...
    val entityId: UUID? = null,                  // null только для PASSENGER_ANONYMOUS
)

data class CardActivateRequest(
    /**
     * Обязателен для DESFire-flow (legacy, подпись + identity JSON).
     * Для VCM1-flow (`vcm1 != null`) — null допустим.
     */
    @field:Valid val cardIdentity: CardIdentityDto? = null,
    /**
     * Обязателен для DESFire-flow. Для VCM1-flow — может быть пустым
     * (сервер сгенерирует VCM1 JSON из `vcm1.entityType`/`vcm1.entityId`).
     */
    val identityJson: String? = null,
    /**
     * Обязателен для DESFire-flow (RSA-PSS-SHA256, base64). Для VCM1-flow — null.
     */
    val identitySignature: String? = null,
    val operatorRoles: List<String> = emptyList(),
    val authorizedByRoot: Boolean = false,
    val rootUserId: UUID? = null,
    /**
     * Технология NFC-карты: "DESFIRE" (по умолчанию) или "CLASSIC" (MIFARE Classic 1K/4K).
     * Терминал шлёт "CLASSIC" для MIFARE Classic-активаций; сервер пишет CARD_TECH в ASOP_CARD_MIFARES.
     */
    val technology: String? = null,
    /**
     * VCM1-payload для Classic-карт (промпт 008). Если null — DESFire-flow (signature + identity JSON).
     * Когда present — `identityJson`/`identitySignature`/`cardIdentity` игнорируются для Classic-карт.
     */
    val vcm1: CardActivateRequestClassic? = null
)

