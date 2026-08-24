package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserKrsApi
import ru.asop.api.user.dto.request.UserKrsCreateRequest
import ru.asop.api.user.dto.response.UserKrsResponse
import ru.asop.user.service.UserKrsService
import java.util.UUID

@RestController
class UserKrsController(
    private val service: UserKrsService,
    private val db: DatabaseClient
) : UserKrsApi {

    override fun list(userId: String?, auditServiceId: String?): Flux<UserKrsResponse> =
        service.list(userId, auditServiceId).map { row -> UserKrsResponse(
            userId = row["user_id"]?.toString() ?: "",
            auditServiceId = row["audit_service_id"]?.toString() ?: "",
            serviceName = row["service_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserKrsCreateRequest): Mono<ResponseEntity<UserKrsResponse>> =
        service.create(request.userId, request.auditServiceId).map {
            ResponseEntity.status(201).body(UserKrsResponse(
                userId = it["user_id"]?.toString() ?: "",
                auditServiceId = it["audit_service_id"]?.toString() ?: "",
                serviceName = it["service_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, auditServiceId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, auditServiceId).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        if (versionSince != null) conditions += "version > :since"
        if (includeDeleted != true) conditions += "deleted_at IS NULL"
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT user_id AS "userId", audit_service_id AS "auditServiceId",
                   created_at AS "createdAt", updated_at AS "updatedAt", deleted_at AS "deletedAt",
                   version AS "version"
            FROM ASOP_USER_KRS$where
            ORDER BY version ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("since", versionSince)
        return spec.bind("limit", limit).fetch().all()
    }
}
