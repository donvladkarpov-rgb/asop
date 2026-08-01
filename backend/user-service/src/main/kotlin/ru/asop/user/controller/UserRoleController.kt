package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserRoleApi
import ru.asop.api.user.dto.request.UserRoleCreateRequest
import ru.asop.api.user.dto.response.UserRoleResponse
import ru.asop.user.service.UserRoleService
import java.time.Instant
import java.util.UUID

@RestController
class UserRoleController(
    private val service: UserRoleService,
    private val db: DatabaseClient
) : UserRoleApi {

    override fun list(userId: String?, roleId: String?): Flux<UserRoleResponse> =
        service.list(userId, roleId).map { row -> UserRoleResponse(
            userId = row["user_id"]?.toString() ?: "",
            roleId = row["role_id"]?.toString() ?: "",
            roleName = row["role_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserRoleCreateRequest): Mono<ResponseEntity<UserRoleResponse>> =
        service.create(request.userId, request.roleId).map {
            ResponseEntity.status(201).body(UserRoleResponse(
                userId = it["user_id"]?.toString() ?: "",
                roleId = it["role_id"]?.toString() ?: "",
                roleName = it["role_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, roleId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, roleId).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()
        }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) updatedAtSince: Instant?,
        @RequestParam(required = false) includeDeleted: Boolean?,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        if (updatedAtSince != null) conditions += "updated_at > :since"
        if (includeDeleted != true) conditions += "deleted_at IS NULL"
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT user_id AS "userId", role_id AS "roleId",
                   created_at AS "createdAt", updated_at AS "updatedAt", deleted_at AS "deletedAt"
            FROM ASOP_USER_ROLES$where
            ORDER BY updated_at ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (updatedAtSince != null) spec = spec.bind("since", updatedAtSince)
        return spec.bind("limit", limit).fetch().all()
    }
}
