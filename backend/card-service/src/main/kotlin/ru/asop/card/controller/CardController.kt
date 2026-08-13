package ru.asop.card.controller

import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.data.relational.core.query.Criteria
import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.card.controller.CardApi
import ru.asop.api.card.dto.request.CardActivateRequest
import ru.asop.api.card.dto.request.CardRegisterRequest
import ru.asop.api.card.dto.request.CardBlockRequest
import ru.asop.api.card.dto.response.CardActivateResponse
import ru.asop.api.card.dto.response.CardResponse
import ru.asop.card.config.DeltaSupport
import ru.asop.card.model.CardEntity
import ru.asop.card.model.CardMifareEntity
import ru.asop.card.repository.CardMifareRepository
import ru.asop.card.service.CardActivationService
import ru.asop.card.service.CardService
import ru.asop.card.service.parseVcm1Bitmask
import java.security.Principal
import java.time.Instant
import java.util.UUID

@RestController
class CardController(
    private val cardService: CardService,
    private val cardActivationService: CardActivationService,
    private val template: R2dbcEntityTemplate,
    private val cardMifareRepository: CardMifareRepository,
    private val databaseClient: DatabaseClient
) : CardApi {

    override fun registerCard(
        request: CardRegisterRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return cardService.register(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun listCards(
        regionId: UUID?,
        carrierId: UUID?,
        userId: UUID?,
        includeDeleted: Boolean,
        limit: Int
    ): Flux<CardResponse> {
        return databaseClient.sql("""
            SELECT c.card_id             AS "card_id",
                   c.card_type_id        AS "card_type_id",
                   c.user_id             AS "user_id",
                   c.carrier_id          AS "carrier_id",
                   c.is_primary          AS "is_primary",
                   c.registered_at       AS "registered_at",
                   c.created_at          AS "created_at",
                   c.updated_at          AS "updated_at",
                   c.deleted_at          AS "deleted_at",
                   m.uid                 AS "uid",
                   m.card_tech           AS "card_tech",
                   m.card_role           AS "card_role",
                   m.identity_json       AS "identity_json",
                   m.valid_until         AS "valid_until",
                   m.revoked_at          AS "revoked_at",
                   t.card_type_name      AS "card_type_name",
                   u.first_name          AS "first_name",
                   u.last_name_initial   AS "last_name_initial",
                   u.patronymic_initial  AS "patronymic_initial"
            FROM ASOP_CARDS c
            LEFT JOIN ASOP_CARD_MIFARES m ON m.card_id = c.card_id
            LEFT JOIN ASOP_CARD_TYPES  t ON t.card_type_id = c.card_type_id
            LEFT JOIN ASOP_USERS       u ON u.user_id = c.user_id
            WHERE (:includeDeleted = TRUE OR c.deleted_at IS NULL)
              AND (:regionId::uuid  IS NULL OR c.region_id  = :regionId::uuid)
              AND (:carrierId::uuid IS NULL OR c.carrier_id = :carrierId::uuid)
              AND (:userId::uuid    IS NULL OR c.user_id    = :userId::uuid)
            ORDER BY c.created_at DESC
            LIMIT :limit
        """.trimIndent())
            .bind("includeDeleted", includeDeleted)
            .bindConditional("regionId", regionId)
            .bindConditional("carrierId", carrierId)
            .bindConditional("userId", userId)
            .bind("limit", limit)
            .fetch()
            .all()
            .map { row -> rowToCardResponse(row) }
    }

    override fun activateCard(
        request: CardActivateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardActivateResponse>> {
        return cardActivationService.activate(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun activateCardVcm1(
        request: CardActivateRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardActivateResponse>> {
        // VCM1-flow использует тот же CardActivateRequest DTO с заполненным vcm1-полем.
        // CardActivationService.activate() проверяет request.vcm1 != null и переключается на VCM1.
        // Здесь — alias для отдельного URL (clearer для API consumers).
        return cardActivationService.activate(request)
            .map { ResponseEntity.ok(it) }
    }

    override fun blockCard(
        id: UUID,
        request: CardBlockRequest,
        principal: Mono<Principal>
    ): Mono<ResponseEntity<CardResponse>> {
        return Mono.empty()
    }

    override fun getCard(id: UUID): Mono<ResponseEntity<CardResponse>> {
        return cardService.getById(id)
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/by-uid/{uid}")
    fun getCardByUid(@PathVariable uid: String): Mono<ResponseEntity<CardByUidResponse>> {
        val uidBytes = try {
            val hex = uid.filter { it !in setOf(' ', '-') }
            if (hex.length % 2 != 0) return Mono.just(ResponseEntity.badRequest().build())
            hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) {
            return Mono.just(ResponseEntity.badRequest().build())
        }
        return cardMifareRepository.findByUid(uidBytes)
            .map { mifare -> ResponseEntity.ok(CardByUidResponse(mifare.cardId.toString(), uid)) }
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
    }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean,
        @RequestParam(required = false) userIdsIn: String?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<CardEntity> {
        var extra: Criteria? = null
        if (!userIdsIn.isNullOrBlank()) {
            val ids = userIdsIn.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { UUID.fromString(it) }
            if (ids.isNotEmpty()) extra = Criteria.where("user_id").`in`(ids)
        }
        return template.select(CardEntity::class.java)
            .matching(DeltaSupport.query(versionSince, includeDeleted, limit, extra))
            .all()
    }
}

data class CardByUidResponse(
    val cardId: String,
    val uid: String
)

private fun rowToCardResponse(row: Map<String, Any?>): CardResponse {
    val deletedAt = row["deleted_at"] as? Instant
    val revokedAt = row["revoked_at"] as? Instant
    val validUntil = row["valid_until"] as? Instant
    val status = when {
        deletedAt != null -> "deleted"
        revokedAt != null -> "blocked"
        validUntil != null && validUntil.isBefore(Instant.now()) -> "expired"
        else -> "active"
    }
    val firstName = row["first_name"] as? String
    val lastNameInitial = row["last_name_initial"] as? String
    val patronymicInitial = row["patronymic_initial"] as? String
    val holderName = when {
        firstName.isNullOrBlank() -> null
        lastNameInitial.isNullOrBlank() -> firstName
        else -> "$firstName $lastNameInitial" + (if (patronymicInitial.isNullOrBlank()) "" else ". $patronymicInitial.")
    }
    val cardTech = row["card_tech"] as? String
    val cardId = asUuid(row["card_id"])
    return CardResponse(
        id = cardId,
        cardTypeId = asUuid(row["card_type_id"]),
        userId = row["user_id"]?.let { asUuid(it) },
        carrierId = row["carrier_id"]?.let { asUuid(it) },
        isClassic = cardTech == "CLASSIC",
        isPrimary = (row["is_primary"] as? Boolean) ?: false,
        bitmask = parseVcm1Bitmask(row["identity_json"] as? String),
        registeredAt = row["registered_at"] as? Instant,
        createdAt = row["created_at"] as? Instant ?: Instant.now(),
        updatedAt = row["updated_at"] as? Instant ?: Instant.now(),
        uid = (row["uid"] as? ByteArray)?.joinToString("") { "%02X".format(it) },
        cardTech = cardTech,
        cardRole = row["card_role"] as? String,
        cardTypeName = row["card_type_name"] as? String,
        holderName = holderName,
        validUntil = validUntil,
        revokedAt = revokedAt,
        status = status
    )
}

private fun asUuid(v: Any?): UUID = when (v) {
    is UUID -> v
    is String -> UUID.fromString(v)
    else -> throw IllegalStateException("Cannot convert ${v?.javaClass} to UUID")
}

private fun DatabaseClient.GenericExecuteSpec.bindConditional(name: String, value: UUID?): DatabaseClient.GenericExecuteSpec =
    if (value != null) bind(name, value.toString()) else bindNull(name, String::class.javaObjectType)
