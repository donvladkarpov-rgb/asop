package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class UserCarrierService(private val db: DatabaseClient) {

    fun list(userId: String?, carrierId: String?): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (userId != null) { conditions.add("uc.user_id = :userId"); params["userId"] = UUID.fromString(userId) }
        if (carrierId != null) { conditions.add("uc.carrier_id = :carrierId"); params["carrierId"] = UUID.fromString(carrierId) }
        val where = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = """
            SELECT uc.user_id, uc.carrier_id, c.carrier_name, u.first_name, u.last_name_initial
            FROM ASOP_USER_CARRIERS uc
            LEFT JOIN ASOP_CARRIERS c ON c.carrier_id = uc.carrier_id
            LEFT JOIN ASOP_USERS u ON u.user_id = uc.user_id
            $where ORDER BY uc.user_id, uc.carrier_id
        """.trimIndent()
        var spec = db.sql(sql)
        for ((k, v) in params) spec = spec.bind(k, v)
        return spec.fetch().all()
    }

    fun create(userId: String, carrierId: String): Mono<Map<String, Any?>> {
        val uId = UUID.fromString(userId); val cId = UUID.fromString(carrierId)
        return db.sql("INSERT INTO ASOP_USER_CARRIERS (user_id, carrier_id) VALUES (:userId, :carrierId)")
            .bind("userId", uId).bind("carrierId", cId).fetch().rowsUpdated()
            .then(list(userId, carrierId).next())
    }

    fun delete(userId: String, carrierId: String): Mono<Long> =
        db.sql("DELETE FROM ASOP_USER_CARRIERS WHERE user_id = :userId AND carrier_id = :carrierId")
            .bind("userId", UUID.fromString(userId)).bind("carrierId", UUID.fromString(carrierId)).fetch().rowsUpdated()
}
