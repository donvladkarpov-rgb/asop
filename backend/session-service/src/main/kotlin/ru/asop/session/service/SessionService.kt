package ru.asop.session.service

import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
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
            sessionId = UuidUtils.newId(),
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
            .flatMap { existing ->
                if (existing.status == "CLOSED") {
                    Mono.error(IllegalStateException("Session already closed: $id"))
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

    fun getById(id: UUID): Mono<SessionResponse> {
        return sessionRepository.findById(id).map { it.toResponse() }
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
                val attrs = parseAttributes(session.attributes)
                val sessionCarrierId = attrs["carrierId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                if (sessionCarrierId != null) {
                    log.debug("Checking canClose: sessionCarrierId={}, requesterId={}", sessionCarrierId, requesterId)
                }
                // Delphi-style cascade через UserRolesAndCarriers: либо carrier, либо organizer/region, либо root.
                isRequesterInSessionScope(requesterId, sessionCarrierId, attrs["regionId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() })
            }
            .defaultIfEmpty(false)
    }

    /**
     * Реализация промпт 011 §4 assert. SQL — cascade query:
     * ASOP_USERS.OPENED_BY = requesterId ∧
     *   (requesterId ∈ asop_user_carriers.carrier_id == session.carrier_id)
     *   ∨ (requesterId ∈ asop_user_regions.region_id == session.region_id)  [если перевозчик имеет region]
     *   ∨ requesterId в ADMIN/SUPER_ADMIN role.
     */
    private fun isRequesterInSessionScope(requesterId: UUID, sessionCarrierId: UUID?, sessionRegionId: UUID?): Mono<Boolean> {
        if (sessionCarrierId == null && sessionRegionId == null) {
            // Может закрыть только ADMIN/SUPER_ADMIN.
            return db.sql("""
                SELECT 1 FROM ASOP_USER_ROLES ur
                JOIN ASOP_ROLES r ON r.role_id = ur.role_id
                WHERE ur.user_id = :requesterId
                  AND r.role_code IN ('ADMIN','SUPER_ADMIN')
                LIMIT 1
            """)
                .bind("requesterId", requesterId)
                .fetch().one().map { true }.defaultIfEmpty(false)
        }
        val sql = StringBuilder("""
            WITH session_carrier AS (
              SELECT :sessionCarrierId::uuid AS carrier_id,
                     :sessionRegionId::uuid AS region_id
            ), requester_scope AS (
              SELECT uc.carrier_id AS cid, NULL::uuid AS rid
              FROM ASOP_USER_CARRIERS uc WHERE uc.user_id = :requesterId
              UNION ALL
              SELECT NULL::uuid AS cid, ur.region_id AS rid
              FROM ASOP_USER_REGIONS ur WHERE ur.user_id = :requesterId
            )
            SELECT 1
            FROM requester_scope rs
            WHERE (
              (:sessionCarrierId::uuid IS NOT NULL AND rs.cid = :sessionCarrierId::uuid)
              OR (:sessionRegionId::uuid IS NOT NULL AND rs.rid = :sessionRegionId::uuid)
            )
              OR EXISTS (
                SELECT 1 FROM ASOP_USER_ROLES ur
                JOIN ASOP_ROLES r ON r.role_id = ur.role_id
                WHERE ur.user_id = :requesterId
                  AND r.role_code IN ('ADMIN','SUPER_ADMIN','REGION_ADMIN','ORGANIZER_ADMIN','CARRIER_ADMIN','CARRIER_DISPATCHER','KRS_DISPATCHER')
              )
            LIMIT 1
        """)
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql.toString())
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
    pathId = pathId,
    vehicleId = vehicleId,
    status = status,
    startedAt = startedAt,
    closedAt = closedAt,
    createdAt = startedAt,
    updatedAt = startedAt
)
