package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserCarrierApi
import ru.asop.api.user.dto.request.UserCarrierCreateRequest
import ru.asop.api.user.dto.response.UserCarrierResponse
import ru.asop.user.service.UserCarrierService
import java.util.UUID

@RestController
class UserCarrierController(
    private val service: UserCarrierService,
    private val db: DatabaseClient
) : UserCarrierApi {

    override fun list(userId: String?, carrierId: String?): Flux<UserCarrierResponse> =
        service.list(userId, carrierId).map { row -> UserCarrierResponse(
            userId = row["user_id"]?.toString() ?: "",
            carrierId = row["carrier_id"]?.toString() ?: "",
            carrierName = row["carrier_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserCarrierCreateRequest): Mono<ResponseEntity<UserCarrierResponse>> =
        service.create(request.userId, request.carrierId).map {
            ResponseEntity.status(201).body(UserCarrierResponse(
                userId = it["user_id"]?.toString() ?: "",
                carrierId = it["carrier_id"]?.toString() ?: "",
                carrierName = it["carrier_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, carrierId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, carrierId).map { rows ->
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
        val conditions = mutableListOf<String>()
        if (versionSince != null) conditions += "version > :since"
        if (includeDeleted != true) conditions += "deleted_at IS NULL"
        if (carrierId != null) conditions += "carrier_id = :carrierId"
        val where = if (conditions.isEmpty()) "" else " WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT user_id AS "userId", carrier_id AS "carrierId",
                   created_at AS "createdAt", updated_at AS "updatedAt", deleted_at AS "deletedAt",
                   version AS "version"
            FROM ASOP_USER_CARRIERS$where
            ORDER BY version ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("since", versionSince)
        if (carrierId != null) spec = spec.bind("carrierId", carrierId)
        return spec.bind("limit", limit).fetch().all()
    }
}
