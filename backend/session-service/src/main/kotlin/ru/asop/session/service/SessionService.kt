package ru.asop.session.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.session.dto.request.SessionOpenRequest
import ru.asop.api.session.dto.request.SessionCloseRequest
import ru.asop.api.session.dto.response.SessionResponse
import ru.asop.session.model.SessionEntity
import ru.asop.session.repository.SessionRepository
import ru.asop.common.util.UuidUtils
import java.time.Instant
import java.util.UUID

/**
 * Сессионный сервис. Промпт 011:
 * - expanded attributes carrierId/regionId JSONB
 * - 409 server-side guard TRIP conflict (через SessionCommandConsumer)
 * - canClose() матрица: driver than opened, OR driver/dispatcher/admin of same carrier
 *   OR admin выше по role hierarchy (ORGANIZER_ADMIN/REGION_ADMIN/ADMIN/SUPER_ADMIN)
 *
 * Auth source-of-truth: ASOP_USER_ROLES + ASOP_USER_CARRIERS + ASOP_USER_REGIONS + ASOP_USERS.
 */
@Service
class SessionService(
    private val sessionRepository: SessionRepository,
    private val db: DatabaseClient,
    private val objectMapper: ObjectMapper
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun open(request: SessionOpenRequest): Mono<SessionResponse> {
        val now = Instant.now()
        val attrs = mergeAttributes(request.attributes, request.carrierId, request.regionId, request.timezone)
        val entity = SessionEntity(
            sessionId = request.sessionId ?: UuidUtils.newId(),
            sessionTypeId = request.sessionTypeId,
            parentSessionId = request.parentSessionId,
            terminalId = request.terminalId,
            tidId = request.tidId,
            cardId = request.cardId,
            openedByUserId = request.openedByUserId,
            pathId = request.pathId,
            vehicleId = request.vehicleId,
            status = "IN_PROGRESS",
            startedAt = now,
            attributes = attrs
        )
        return sessionRepository.save(entity).map { it.toResponse() }
    }

    fun close(id: UUID, request: SessionCloseRequest): Mono<SessionResponse> {
        val now = Instant.now()
        return sessionRepository.findById(id)
            .switchIfEmpty(Mono.error(IllegalStateException("Session not found: $id")))
            .flatMap { existing ->
                if (existing.status == "CLOSED") {
                    Mono.error(IllegalStateException("Session already closed: $id"))
                } else {
                    val requester = request.closedByUserId
                    val auth = if (requester != null) canClose(id, requester) else Mono.just(false)
                    auth.flatMap { allowed ->
                        if (!allowed) {
                            Mono.error(IllegalStateException(
                                "Requester $requester is not authorized to close session $id"
                            ))
                        } else {
                            val updated = existing.copy(
                                status = "CLOSED",
                                closedAt = now,
                                closedAtLocal = now
                            )
                            sessionRepository.save(updated).map { it.toResponse() }
                        }
                    }
                }
            }
    }

    fun getById(id: UUID): Mono<SessionResponse> {
        return sessionRepository.findById(id).map { it.toResponse() }
    }

    /** Список смен (web-admin): по умолчанию последние 200, фильтр по терминалу. */
    fun list(terminalId: UUID?): Flux<SessionResponse> {
        val sql = StringBuilder(
            "SELECT * FROM ASOP_SESSIONS WHERE 1=1"
        )
        if (terminalId != null) sql.append(" AND TERMINAL_ID = :terminalId")
        sql.append(" ORDER BY STARTED_AT DESC LIMIT 200")
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql.toString())
        if (terminalId != null) spec = spec.bind("terminalId", terminalId)
        return spec.map { row, _ ->
            SessionResponse(
                id = row.get("SESSION_ID", UUID::class.java)!!,
                sessionTypeId = row.get("SESSION_TYPE_ID", UUID::class.java)!!,
                parentSessionId = row.get("PARENT_SESSION_ID", UUID::class.java),
                terminalId = row.get("TERMINAL_ID", UUID::class.java),
                pathId = row.get("PATH_ID", UUID::class.java),
                tidId = row.get("TID_ID", UUID::class.java),
                openedByUserId = row.get("OPENED_BY_USER_ID", UUID::class.java),
                closedByUserId = row.get("CLOSED_BY_USER_ID", UUID::class.java),
                cardId = row.get("CARD_ID", UUID::class.java),
                vehicleId = row.get("VEHICLE_ID", UUID::class.java),
                status = row.get("STATUS", String::class.java)!!,
                startedAt = row.get("STARTED_AT", Instant::class.java)!!,
                closedAt = row.get("CLOSED_AT", Instant::class.java),
                createdAt = row.get("STARTED_AT", Instant::class.java)!!,
                updatedAt = row.get("STARTED_AT", Instant::class.java)!!
            )
        }.all()
    }

    /**
     * Промпт 011 §4: матрица авторизации для ЗАКРЫТИЯ чужой смены.
     * - DRIVER (открыватель — он же): всегда может закрыть свою смену.
     * - DRIVER_B (другой водитель того же carrier_id): может, если его carrier_id = session.carrier_id.
     * - CARRIER_DISPATCHER/KRS_DISPATCHER: того же carrier_id.
     * - CARRIER_ADMIN: того же carrier_id (если роль существует).
     * - ORGANIZER_ADMIN: организатора → cascade на carrier линке.
     * - KRS_ADMIN: auditServiceId → cascade.
     * - REGION_ADMIN/ADMIN/SUPER_ADMIN: waterfall на организаторов/перевозчиков.
     *
     * Returns Boolean — true если авторизован.
     */
    fun canClose(sessionId: UUID, requesterId: UUID): Mono<Boolean> {
        return sessionRepository.findById(sessionId)
            .flatMap { session ->
                // Промпт 011 §4: открыватель (DRIVER) всегда может закрыть свою смену.
                if (session.openedByUserId == requesterId) {
                    Mono.just(true)
                } else {
                    val attrs = parseAttributes(session.attributes)
                    val sessionCarrierId = attrs["carrierId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    val sessionRegionId = attrs["regionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    if (sessionCarrierId != null) {
                        log.debug("Checking canClose: sessionCarrierId={}, requesterId={}", sessionCarrierId, requesterId)
                    }
                    // Delphi-style cascade через UserRolesAndCarriers: либо carrier, либо organizer/region, либо root.
                    isRequesterInSessionScope(requesterId, sessionCarrierId, sessionRegionId)
                }
            }
            .defaultIfEmpty(false)
    }

    /**
     * Реализация промпт 011 §4 assert без scope-escape.
     * Root (ADMIN/SUPER_ADMIN) — глобальный доступ (задумано), НЕ зависит от линков
     * (root-админ может не иметь asop_user_carriers/asop_user_regions — иначе
     * `requester_scope` пуст и запрос ниже вернул бы 0 строк).
     * Для всех остальных ролей requester обязан быть привязан к scope сессии:
     *   - carrier-scope роли (DRIVER, CARRIER_DISPATCHER, KRS_DISPATCHER, CARRIER_ADMIN, ORGANIZER_ADMIN):
     *     requester ∈ asop_user_carriers.carrier_id == session.carrier_id
     *   - region-scope роли (REGION_ADMIN, ORGANIZER_ADMIN, KRS_ADMIN):
     *     requester ∈ asop_user_regions.region_id == session.region_id
     * Раньше `OR EXISTS (role IN ...)` был глобальным — любой REGION_ADMIN любого региона
     * мог закрыть чужую смену (scope-escape). Теперь роль проверяется ТОЛЬКО внутри линка.
     */
    private fun isRequesterInSessionScope(requesterId: UUID, sessionCarrierId: UUID?, sessionRegionId: UUID?): Mono<Boolean> {
        // Root first: глобальный доступ независимо от линков/scope сессии.
        val rootSql = """
            SELECT 1 FROM ASOP_USER_ROLES ur
            JOIN ASOP_ROLES r ON r.role_id = ur.role_id
            WHERE ur.user_id = :requesterId AND r.role_name IN ('ADMIN','SUPER_ADMIN')
            LIMIT 1
        """
        return db.sql(rootSql).bind("requesterId", requesterId).fetch().one().map { true }
            .switchIfEmpty(scopeCheck(requesterId, sessionCarrierId, sessionRegionId))
            .defaultIfEmpty(false)
    }

    private fun scopeCheck(requesterId: UUID, sessionCarrierId: UUID?, sessionRegionId: UUID?): Mono<Boolean> {
        if (sessionCarrierId == null && sessionRegionId == null) {
            return Mono.just(false)
        }
        val sql = """
            WITH requester_scope AS (
              SELECT uc.carrier_id AS cid, NULL::uuid AS rid
              FROM ASOP_USER_CARRIERS uc WHERE uc.user_id = :requesterId
              UNION ALL
              SELECT NULL::uuid AS cid, ur.region_id AS rid
              FROM ASOP_USER_REGIONS ur WHERE ur.user_id = :requesterId
            )
            SELECT 1
            FROM requester_scope rs
            WHERE
              -- Carrier-scope: линк на перевозчика сессии + роль из carrier-множества.
              (
                :sessionCarrierId IS NOT NULL AND rs.cid = :sessionCarrierId
                AND EXISTS (
                  SELECT 1 FROM ASOP_USER_ROLES ur
                  JOIN ASOP_ROLES r ON r.role_id = ur.role_id
                  WHERE ur.user_id = :requesterId
                    AND r.role_name IN ('DRIVER','CARRIER_DISPATCHER','KRS_DISPATCHER','CARRIER_ADMIN','ORGANIZER_ADMIN')
                )
              )
              OR
              -- Region-scope: линк на регион сессии + роль из region-множества.
              (
                :sessionRegionId IS NOT NULL AND rs.rid = :sessionRegionId
                AND EXISTS (
                  SELECT 1 FROM ASOP_USER_ROLES ur
                  JOIN ASOP_ROLES r ON r.role_id = ur.role_id
                  WHERE ur.user_id = :requesterId
                    AND r.role_name IN ('REGION_ADMIN','ORGANIZER_ADMIN','KRS_ADMIN')
                )
              )
            LIMIT 1
        """
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
            .bind("requesterId", requesterId)
        spec = if (sessionCarrierId != null) spec.bind("sessionCarrierId", sessionCarrierId)
        else spec.bindNull("sessionCarrierId", UUID::class.java)
        spec = if (sessionRegionId != null) spec.bind("sessionRegionId", sessionRegionId)
        else spec.bindNull("sessionRegionId", UUID::class.java)
        return spec.fetch().one().map { true }.defaultIfEmpty(false)
    }

    private fun mergeAttributes(existing: String?, carrierId: UUID?, regionId: UUID?, timezone: String?): String? {
        val attrs = HashMap<String, Any?>()
        if (existing != null) {
            try { attrs.putAll(objectMapper.readValue<Map<String, Any?>>(existing)) } catch (_: Exception) {}
        }
        if (carrierId != null) attrs["carrierId"] = carrierId.toString()
        if (regionId != null) attrs["regionId"] = regionId.toString()
        if (timezone != null) attrs["timezone"] = timezone
        if (attrs.isEmpty()) return null
        return objectMapper.writeValueAsString(attrs)
    }

    private fun parseAttributes(json: String?): Map<String, String> {
        if (json.isNullOrBlank()) return emptyMap()
        return try {
            objectMapper.readValue<Map<String, String>>(json)
        } catch (_: Exception) {
            emptyMap()
        }
    }
}

private fun SessionEntity.toResponse() = SessionResponse(
    id = sessionId,
    sessionTypeId = sessionTypeId,
    parentSessionId = parentSessionId,
    terminalId = terminalId,
    tidId = tidId,
    openedByUserId = openedByUserId,
    closedByUserId = closedByUserId,
    cardId = cardId,
    pathId = pathId,
    vehicleId = vehicleId,
    status = status,
    startedAt = startedAt,
    closedAt = closedAt,
    createdAt = startedAt,
    updatedAt = startedAt
)
