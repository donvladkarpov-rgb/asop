package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class UserRegionService(private val db: DatabaseClient) {

    fun list(userId: String?, regionId: String?): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (userId != null) { conditions.add("ur.user_id = :userId"); params["userId"] = UUID.fromString(userId) }
        if (regionId != null) { conditions.add("ur.region_id = :regionId"); params["regionId"] = UUID.fromString(regionId) }
        val where = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = """
            SELECT ur.user_id, ur.region_id, r.municipal_division AS region_name, u.first_name, u.last_name_initial
            FROM ASOP_USER_REGIONS ur
            LEFT JOIN ASOP_REGIONS r ON r.region_id = ur.region_id
            LEFT JOIN ASOP_USERS u ON u.user_id = ur.user_id
            $where ORDER BY ur.user_id, ur.region_id
        """.trimIndent()
        var spec = db.sql(sql)
        for ((k, v) in params) spec = spec.bind(k, v)
        return spec.fetch().all()
    }

    fun create(userId: String, regionId: String): Mono<Map<String, Any?>> {
        val uId = UUID.fromString(userId); val rId = UUID.fromString(regionId)
        return db.sql("INSERT INTO ASOP_USER_REGIONS (user_id, region_id) VALUES (:userId, :regionId)")
            .bind("userId", uId).bind("regionId", rId).fetch().rowsUpdated()
            .then(list(userId, regionId).next())
    }

    fun delete(userId: String, regionId: String): Mono<Long> =
        db.sql("DELETE FROM ASOP_USER_REGIONS WHERE user_id = :userId AND region_id = :regionId")
            .bind("userId", UUID.fromString(userId)).bind("regionId", UUID.fromString(regionId)).fetch().rowsUpdated()
}
