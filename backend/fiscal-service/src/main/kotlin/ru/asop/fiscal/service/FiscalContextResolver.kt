package ru.asop.fiscal.service

import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import java.util.UUID

/**
 * Резолвер фискального контекста по правилу промпт 016 §3.1.7:
 * «все платежи/льготы вешаются на TID, который вводит водитель при открытии рейса».
 *
 * Цепочка (один SQL):
 *   payment.sessionId → ASOP_SESSIONS.TID_ID (TID из рейса водителя)
 *                     → ASOP_TIDS → ASOP_CONTRACTS (актуальный BANK-договор) → CARRIER_ID
 *                     → ASOP_CARRIER_FISCALIZERS (primary/active) → CARRIER_FISCALIZER_ID
 */
@Service
class FiscalContextResolver(
    private val db: DatabaseClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class FiscalContext(
        val carrierId: UUID,
        val carrierFiscalizerId: UUID
    )

    fun resolve(sessionId: UUID): Mono<FiscalContext> =
        db.sql(
            """
            SELECT c.carrier_id, cf.carrier_fiscalizer_id
            FROM asop_sessions s
            JOIN asop_tids t        ON t.tid_id = s.tid_id AND t.deleted_at IS NULL
            JOIN asop_contracts c   ON c.contract_id = t.contract_id
                 AND c.contractor_type = 'BANK'
                 AND c.status = 'ACTIVE'
                 AND c.start_date <= NOW()
                 AND COALESCE(c.end_date, NOW()) >= NOW()
            JOIN asop_carrier_fiscalizers cf ON cf.carrier_id = c.carrier_id
                 AND cf.is_active = true
            WHERE s.session_id = :sessionId
            ORDER BY cf.is_primary DESC
            LIMIT 1
            """.trimIndent()
        )
            .bind("sessionId", sessionId)
            .map { row, _ ->
                FiscalContext(
                    carrierId = requireNotNull(row.get("carrier_id", UUID::class.java)),
                    carrierFiscalizerId = requireNotNull(row.get("carrier_fiscalizer_id", UUID::class.java))
                )
            }
            .one()
            .doOnSuccess { ctx ->
                if (ctx == null) {
                    log.debug("No fiscal context for sessionId={} (нет TID/договора/фискализатора)", sessionId)
                } else {
                    log.info("Resolved fiscal context: sessionId={}, carrierId={}, carrierFiscalizerId={}",
                        sessionId, ctx.carrierId, ctx.carrierFiscalizerId)
                }
            }
}