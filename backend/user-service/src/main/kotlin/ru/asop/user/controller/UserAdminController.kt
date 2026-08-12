package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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

    @GetMapping("/by-keycloak/{keycloakId}")
    fun getByKeycloak(@PathVariable keycloakId: String): Mono<ResponseEntity<UserResponse>> =
        service.getByKeycloakId(keycloakId).flatMap { row ->
            if (row.isEmpty()) Mono.just(ResponseEntity.notFound().build())
            else Mono.just(ResponseEntity.ok(rowToResponse(row)))
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
        if (versionSince != null) conditions += "u.version > :since"
        if (includeDeleted != true) conditions += "u.deleted_at IS NULL"
        // Фильтр-конструктор: пользователь попадает в дельту, если
        //  • привязан к выбранному carrier/region (если указан), ИЛИ
        //  • имеет глобальную роль SUPER_ADMIN/ADMIN/ORG_ADMIN (всегда доступен для root-активаций).
        // Гарантирует, что root-администратор (admin@asop.local, BootstrapService) виден терминалу
        // даже если он не привязан ни к одному региону/перевозчику.
        val filterParts = mutableListOf<String>()
        if (carrierId != null) filterParts += "u.user_id IN (SELECT user_id FROM ASOP_USER_CARRIERS WHERE carrier_id = :carrierId)"
        if (regionId != null) filterParts += "u.user_id IN (SELECT user_id FROM ASOP_USER_REGIONS WHERE region_id = :regionId)"
        filterParts += """
            u.user_id IN (
                SELECT ur.user_id FROM ASOP_USER_ROLES ur
                JOIN ASOP_ROLES r ON r.role_id = ur.role_id
                WHERE r.role_name IN ('SUPER_ADMIN','ADMIN','REGION_ADMIN','ORGANIZER_ADMIN')
                  AND (ur.deleted_at IS NULL)
            )
        """.trimIndent()
        conditions += "(${filterParts.joinToString(" OR ")})"
        val where = " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT u.user_id AS "userId", u.first_name AS "firstName", u.last_name_initial AS "lastNameInitial",
                   u.patronymic_initial AS "patronymicInitial", u.phone, u.keycloak_id AS "keycloakId",
                   u.created_at AS "createdAt", u.updated_at AS "updatedAt", u.deleted_at AS "deletedAt",
                   u.version AS "version"
            FROM ASOP_USERS u$where
            ORDER BY u.version ASC
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
