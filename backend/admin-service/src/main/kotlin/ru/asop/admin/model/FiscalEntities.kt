package ru.asop.admin.model

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant
import java.util.UUID

/**
 * Справочник фискализаторов (ASOP_FISCALIZERS) — участвует в дельта-синхронизации
 * (глобальный справочник, доставляется терминалам для построения URL чека).
 */
@Table("ASOP_FISCALIZERS")
data class FiscalizerEntity(
    @Id
    val fiscalizerId: UUID,
    val fiscalizerCode: String,
    val fiscalizerName: String,
    val baseApiUrl: String? = null,
    val description: String? = null,
    val isActive: Boolean = true,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)

/**
 * Настройки фискализации перевозчика (ASOP_CARRIER_FISCALIZERS) — дельта-синхронизация
 * с фильтром по carrierId/regionId (только для терминалов перевозчика).
 * SETTINGS — JSONB в виде JSON-строки (сериализуется ObjectMapper).
 */
@Table("ASOP_CARRIER_FISCALIZERS")
data class CarrierFiscalizerEntity(
    @Id
    val carrierFiscalizerId: UUID,
    val carrierId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val customApiUrl: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val settings: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)

/**
 * Настройки фискализации дистрибьютора (ASOP_DISTRIBUTOR_FISCALIZERS).
 * В delta-flow оркестратора не участвует (дистрибьютор тянет JSON-pull'ом
 * через gateway `/api/v1/sync/distributor/...`), но имеет VERSION/DELETED_AT
 * для полноценного keyset-delta на своём эндпоинте.
 */
@Table("ASOP_DISTRIBUTOR_FISCALIZERS")
data class DistributorFiscalizerEntity(
    @Id
    val distributorFiscalizerId: UUID,
    val cardsDistributorId: UUID,
    val fiscalizerId: UUID,
    val contractId: UUID,
    val customApiUrl: String? = null,
    val isPrimary: Boolean = false,
    val isActive: Boolean = true,
    val settings: String? = null,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),
    val deletedAt: Instant? = null,
    val version: Long? = null
)

/**
 * Токен доступа к API фискализатора (ASOP_FISCALIZER_TOKENS).
 * Секрет — НЕ синхронизируется на терминалы/дистрибьюторов (решение 2026-10-08),
 * только web-admin CRUD. Токен зашифрован AES-GCM ([ru.asop.admin.util.TokenCipher]),
 * в ответах отдаётся только tokenHint. Ровно одна ссылка (CARRIER_/DISTRIBUTOR_)
 * обязательна — chk_ft_link; физическое удаление (таблица без VERSION/DELETED_AT).
 */
@Table("ASOP_FISCALIZER_TOKENS")
data class FiscalizerTokenEntity(
    @Id
    val tokenId: UUID,
    val carrierFiscalizerId: UUID? = null,
    val distributorFiscalizerId: UUID? = null,
    val tokenEncrypted: ByteArray = ByteArray(0),
    val tokenHint: String? = null,
    val validFrom: Instant = Instant.now(),
    val validUntil: Instant = Instant.now().plusSeconds(365L * 24 * 3600),
    val status: String = "ACTIVE",
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now()
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is FiscalizerTokenEntity) return false
        return tokenId == other.tokenId
    }

    override fun hashCode(): Int = tokenId.hashCode()
}
