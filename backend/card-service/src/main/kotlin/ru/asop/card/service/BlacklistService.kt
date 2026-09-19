package ru.asop.card.service

import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.card.dto.BlacklistBlockRequest
import ru.asop.card.model.BlacklistEntity
import java.time.Instant
import java.util.UUID

/**
 * Стоп-лист карт (промпт 016 §3.1.6).
 *
 * Авто-политика по долгам реализована продуктивными PL/pgSQL-функциями
 * [fn_create_card_debt]/[fn_recover_card_debt] (см. v001-init.sql) — суммарный открытый
 * долг карты >= порога из base-строки ASOP_CONFIG_PARAMS (`blacklist.debtThreshold`) →
 * upsert `NEGATIVE_BALANCE` с `AUTO_UNBLOCK_ON_RECOVERY`, при погашении — авто-unblock.
 * Здесь — только ручной CRUD (`POST`/`DELETE`) поверх тех же таблиц.
 */
@Service
class BlacklistService(
    private val db: DatabaseClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Все записи стоп-листа (по умолчанию активные). */
    fun list(blockType: String?, includeDeleted: Boolean, limit: Int): Flux<BlacklistEntity> {
        var spec = db.sql("""
            SELECT CARD_ID AS card_id, BLOCK_TYPE AS block_type, BLOCKED_AT AS blocked_at,
                   RELATED_DEBT_ID AS related_debt_id, AUTO_UNBLOCK_ON_RECOVERY AS auto_unblock_desc,
                   CREATED_AT AS created_at, UPDATED_AT AS updated_at, DELETED_AT AS deleted_at, VERSION AS version
            FROM ASOP_BLACKLISTS
            WHERE (:includeDeleted = TRUE OR DELETED_AT IS NULL)
              AND (:blockType IS NULL OR BLOCK_TYPE = :blockType)
            ORDER BY BLOCKED_AT DESC
            LIMIT :limit
        """.trimIndent())
            .bind("includeDeleted", includeDeleted)
            .bind("limit", limit)
        spec = if (blockType != null) spec.bind("blockType", blockType)
        else spec.bindNull("blockType", String::class.java)
        return spec.fetch().all().map { row -> toEntity(row) }
    }

    /** Ручная блокировка (upsert по CARD_ID: повторный POST переактивирует/обновляет). */
    fun block(request: BlacklistBlockRequest): Mono<BlacklistEntity> {
        val type = request.blockType.uppercase()
        require(type == "PERMANENT" || type == "NEGATIVE_BALANCE") {
            "BLOCK_TYPE должен быть PERMANENT или NEGATIVE_BALANCE, получено: ${request.blockType}"
        }
        var spec = db.sql("""
            INSERT INTO ASOP_BLACKLISTS (CARD_ID, BLOCK_TYPE, BLOCKED_AT, RELATED_DEBT_ID, AUTO_UNBLOCK_ON_RECOVERY)
            VALUES (:cardId, :blockType, NOW(), :relatedDebtId, :autoUnblock)
            ON CONFLICT (CARD_ID) DO UPDATE
                SET BLOCK_TYPE = EXCLUDED.BLOCK_TYPE,
                    BLOCKED_AT = NOW(),
                    RELATED_DEBT_ID = COALESCE(EXCLUDED.RELATED_DEBT_ID, ASOP_BLACKLISTS.RELATED_DEBT_ID),
                    AUTO_UNBLOCK_ON_RECOVERY = EXCLUDED.AUTO_UNBLOCK_ON_RECOVERY,
                    DELETED_AT = NULL
        """.trimIndent())
            .bind("cardId", request.cardId)
            .bind("blockType", type)
            .bind("autoUnblock", request.autoUnblockOnRecovery)
        spec = if (request.relatedDebtId != null) spec.bind("relatedDebtId", request.relatedDebtId)
        else spec.bindNull("relatedDebtId", UUID::class.java)

        return spec.fetch().rowsUpdated()
            .flatMap { findByCardId(request.cardId) }
            .doOnNext { e -> log.info("Card {} blocked ({})", e.cardId, e.blockType) }
    }

    /** Ручная разблокировка: soft-delete (DELETED_AT + VERSION через триггер → tombstone в дельту). */
    fun unblock(cardId: UUID): Mono<Void> {
        return db.sql("""
            UPDATE ASOP_BLACKLISTS
            SET DELETED_AT = NOW()
            WHERE CARD_ID = :cardId AND DELETED_AT IS NULL
        """.trimIndent())
            .bind("cardId", cardId)
            .fetch()
            .rowsUpdated()
            .flatMap { updated: Long ->
                if (updated <= 0) {
                    Mono.error(IllegalStateException("Запись стоп-листа не найдена или уже снята: $cardId"))
                } else {
                    Mono.just(updated)
                }
            }
            .then()
            .doOnSuccess { log.info("Card {} unblocked", cardId) }
    }

    private fun findByCardId(cardId: UUID): Mono<BlacklistEntity> = db.sql("""
        SELECT CARD_ID AS card_id, BLOCK_TYPE AS block_type, BLOCKED_AT AS blocked_at,
               RELATED_DEBT_ID AS related_debt_id, AUTO_UNBLOCK_ON_RECOVERY AS auto_unblock_desc,
               CREATED_AT AS created_at, UPDATED_AT AS updated_at, DELETED_AT AS deleted_at, VERSION AS version
        FROM ASOP_BLACKLISTS
        WHERE CARD_ID = :cardId
    """.trimIndent())
        .bind("cardId", cardId)
        .fetch()
        .one()
        .map { row -> toEntity(row) }

    private fun toEntity(row: Map<String, Any?>): BlacklistEntity = BlacklistEntity(
        cardId = asUuid(row["card_id"]),
        blockType = row["block_type"] as String,
        blockedAt = row["blocked_at"] as? Instant ?: Instant.now(),
        relatedDebtId = row["related_debt_id"]?.let { asUuid(it) },
        autoUnblockOnRecovery = (row["auto_unblock_desc"] as? Boolean) ?: false,
        createdAt = row["created_at"] as? Instant ?: Instant.now(),
        updatedAt = row["updated_at"] as? Instant ?: Instant.now(),
        deletedAt = row["deleted_at"] as? Instant,
        version = (row["version"] as? Number)?.toLong()
    )

    private fun asUuid(v: Any?): UUID = when (v) {
        is UUID -> v
        is String -> UUID.fromString(v)
        else -> throw IllegalStateException("Cannot convert ${v?.javaClass} to UUID")
    }
}