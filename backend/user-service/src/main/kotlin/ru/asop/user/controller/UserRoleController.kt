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
import java.util.UUID

@RestController
class UserRoleController(
    private val service: UserRoleService,
    private val db: DatabaseClient
) : UserRoleApi {

    override fun list(userId: String?, roleId: String?, regionId: java.util.UUID?): Flux<UserRoleResponse> =
        service.list(userId, roleId, regionId).map { row -> UserRoleResponse(
            userId = row["user_id"]?.toString() ?: "",
            roleId = row["role_id"]?.toString(), // null — пользователь без ролей
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
        @RequestParam(required = false) versionSince: Long?,
        @RequestParam(required = false) includeDeleted: Boolean?,
        @RequestParam(required = false) regionId: UUID?,
        @RequestParam(required = false) carrierId: UUID?,
        @RequestParam(required = false, defaultValue = "10000") limit: Int
    ): Flux<Map<String, Any?>> {
        // Промпт 010: region filter через JOIN ASOP_USERS + EXISTS ASOP_USER_REGIONS.
        // Расширено (как в admin-users /delta): пользователь «в регионе» и когда он привязан
        // к любому перевозчику региона (user_carriers → carriers.region_id) — не только
        // напрямую через user_regions.
        val sql = """
            SELECT ur.user_id AS "userId", ur.role_id AS "roleId",
                   ur.created_at AS "createdAt", ur.updated_at AS "updatedAt",
                   ur.deleted_at AS "deletedAt", ur.version AS "version"
            FROM ASOP_USER_ROLES ur
            JOIN ASOP_USERS u ON u.user_id = ur.user_id
            WHERE (:versionSince IS NULL OR ur.version > :versionSince)
              AND (:includeDeleted = TRUE OR ur.deleted_at IS NULL)
              AND (
                :regionId::uuid IS NULL
                OR EXISTS (
                  SELECT 1 FROM ASOP_USER_REGIONS ur2
                  WHERE ur2.user_id = u.user_id
                    AND ur2.region_id = :regionId::uuid
                )
                OR EXISTS (
                  SELECT 1 FROM ASOP_USER_CARRIERS uc2
                  JOIN ASOP_CARRIERS c2 ON c2.carrier_id = uc2.carrier_id
                  WHERE uc2.user_id = u.user_id
                    AND c2.region_id = :regionId::uuid
                    AND c2.deleted_at IS NULL
                )
              )
            ORDER BY ur.version ASC
            LIMIT :limit
        """.trimIndent()
        var spec: DatabaseClient.GenericExecuteSpec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("versionSince", versionSince) else spec = spec.bindNull("versionSince", Long::class.javaObjectType)
        spec = spec.bind("includeDeleted", includeDeleted ?: false)
        if (regionId != null) spec = spec.bind("regionId", regionId.toString()) else spec = spec.bindNull("regionId", String::class.javaObjectType)
        spec = spec.bind("limit", limit)
        return spec.fetch().all()
    }
}
