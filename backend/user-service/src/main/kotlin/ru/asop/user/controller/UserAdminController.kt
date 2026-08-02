package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserAdminApi
import ru.asop.api.user.dto.request.UserCreateRequest
import ru.asop.api.user.dto.response.UserResponse
import ru.asop.user.service.UserAdminService
import java.util.UUID

@RestController
class UserAdminController(
    private val service: UserAdminService,
    private val db: DatabaseClient
) : UserAdminApi {

    override fun list(): Flux<UserResponse> =
        service.list().flatMapMany { Flux.fromIterable(it) }.map { rowToResponse(it) }

    override fun get(id: String): Mono<ResponseEntity<UserResponse>> =
        service.getById(id).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
        }

    override fun create(request: UserCreateRequest): Mono<ResponseEntity<UserResponse>> =
        service.create(request).map { ResponseEntity.status(201).body(it) }

    override fun update(id: String, request: UserCreateRequest): Mono<ResponseEntity<UserResponse>> =
        service.update(id, request).flatMap { resp ->
            if (resp.id.isNotEmpty() && resp.firstName.isNotEmpty()) Mono.just(ResponseEntity.ok(resp))
            else Mono.just(ResponseEntity.notFound().build())
        }

    override fun delete(id: String): Mono<ResponseEntity<Void>> =
        service.delete(id).map { rows ->
            if (rows > 0) ResponseEntity.noContent().build()
            else ResponseEntity.notFound().build()
        }

    @GetMapping("/delta")
    fun listDelta(
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean?,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        if (versionSince != null) conditions += "version > :since"
        if (includeDeleted != true) conditions += "deleted_at IS NULL"
        val unions = mutableListOf<String>()
        if (carrierId != null) unions += "SELECT user_id FROM ASOP_USER_CARRIERS WHERE carrier_id = :carrierId"
        if (regionId != null) unions += "SELECT user_id FROM ASOP_USER_REGIONS WHERE region_id = :regionId"
        if (unions.isNotEmpty()) conditions += "user_id IN (${unions.joinToString(" UNION ")})"
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT user_id AS "userId", first_name AS "firstName", last_name_initial AS "lastNameInitial",
                   patronymic_initial AS "patronymicInitial", phone, keycloak_id AS "keycloakId",
                   created_at AS "createdAt", updated_at AS "updatedAt", deleted_at AS "deletedAt",
                   version AS "version"
            FROM ASOP_USERS$where
            ORDER BY version ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("since", versionSince)
        if (carrierId != null) spec = spec.bind("carrierId", carrierId)
        if (regionId != null) spec = spec.bind("regionId", regionId)
        return spec.bind("limit", limit).fetch().all()
    }

    private fun rowToResponse(row: Map<String, Any?>): UserResponse = UserResponse(
        id = row["user_id"]?.toString() ?: "",
        firstName = row["first_name"]?.toString() ?: "",
        lastNameInitial = row["last_name_initial"]?.toString() ?: "",
        patronymicInitial = row["patronymic_initial"]?.toString(),
        phone = row["phone"]?.toString(),
        keycloakId = row["keycloak_id"]?.toString()
    )
}
