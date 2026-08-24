package ru.asop.user.controller

import org.springframework.http.ResponseEntity
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import ru.asop.api.user.controller.UserDistributorApi
import ru.asop.api.user.dto.request.UserDistributorCreateRequest
import ru.asop.api.user.dto.response.UserDistributorResponse
import ru.asop.user.service.UserDistributorService
import java.util.UUID

@RestController
class UserDistributorController(
    private val service: UserDistributorService,
    private val db: DatabaseClient
) : UserDistributorApi {

    override fun list(userId: String?, cardsDistributorId: String?): Flux<UserDistributorResponse> =
        service.list(userId, cardsDistributorId).map { row -> UserDistributorResponse(
            userId = row["user_id"]?.toString() ?: "",
            cardsDistributorId = row["cards_distributor_id"]?.toString() ?: "",
            distributorName = row["distributor_name"]?.toString(),
            firstName = row["first_name"]?.toString(),
            lastNameInitial = row["last_name_initial"]?.toString()
        )}

    override fun create(request: UserDistributorCreateRequest): Mono<ResponseEntity<UserDistributorResponse>> =
        service.create(request.userId, request.cardsDistributorId).map {
            ResponseEntity.status(201).body(UserDistributorResponse(
                userId = it["user_id"]?.toString() ?: "",
                cardsDistributorId = it["cards_distributor_id"]?.toString() ?: "",
                distributorName = it["distributor_name"]?.toString(),
                firstName = it["first_name"]?.toString(),
                lastNameInitial = it["last_name_initial"]?.toString()
            ))
        }

    override fun delete(userId: String, cardsDistributorId: String): Mono<ResponseEntity<Void>> =
        service.delete(userId, cardsDistributorId).map { rows ->
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
            SELECT user_id AS "userId", cards_distributor_id AS "cardsDistributorId",
                   created_at AS "createdAt", updated_at AS "updatedAt", deleted_at AS "deletedAt",
                   version AS "version"
            FROM ASOP_USER_CARDS_DISTRIBUTORS$where
            ORDER BY version ASC
            LIMIT :limit
        """.trimIndent()
        var spec = db.sql(sql)
        if (versionSince != null) spec = spec.bind("since", versionSince)
        return spec.bind("limit", limit).fetch().all()
    }
}
