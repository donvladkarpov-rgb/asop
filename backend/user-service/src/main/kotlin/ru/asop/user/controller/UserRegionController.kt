package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserRegionApi
import ru.asop.api.user.dto.request.UserRegionCreateRequest
import ru.asop.api.user.dto.response.UserRegionResponse
import ru.asop.user.service.UserRegionService
import java.time.Instant
import java.util.UUID

@RestController
class UserRegionController(
    private val service: UserRegionService,
    private val db: DatabaseClient
) : UserRegionApi {

    override fun list(userId: String?, regionId: String?): Flux<UserRegionResponse> =
        service.list(userId, regionId).map { row -> UserRegionResponse(
            userId = row["user_id"]?.toString() ?: "",
            regionId = row["region_id"]?.toString() ?: "",
            regionName = row["region_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserRegionCreateRequest): Mono<ResponseEntity<UserRegionResponse>> =
        service.create(request.userId, request.regionId).map {
            ResponseEntity.status(201).body(UserRegionResponse(
                userId = it["user_id"]?.toString() ?: "",
                regionId = it["region_id"]?.toString() ?: "",
                regionName = it["region_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, regionId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, regionId).map { rows ->
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
        if (regionId != null) conditions += "region_id = :regionId"
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT user_id AS "userId", region_id AS "regionId",
                   created_at AS "createdAt", updated_at AS "updatedAt", deleted_at AS "deletedAt"
            FROM ASOP_USER_REGIONS$where
            ORDER BY updated_at ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (updatedAtSince != null) spec = spec.bind("since", updatedAtSince)
        if (regionId != null) spec = spec.bind("regionId", regionId)
        return spec.bind("limit", limit).fetch().all()
    }
}
