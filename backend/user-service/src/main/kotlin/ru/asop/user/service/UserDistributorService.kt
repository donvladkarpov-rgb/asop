package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class UserDistributorService(private val db: DatabaseClient) {

    fun list(userId: String?, cardsDistributorId: String?): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (userId != null) { conditions.add("ud.user_id = :userId"); params["userId"] = UUID.fromString(userId) }
        if (cardsDistributorId != null) { conditions.add("ud.cards_distributor_id = :cardsDistributorId"); params["cardsDistributorId"] = UUID.fromString(cardsDistributorId) }
        conditions.add("ud.deleted_at IS NULL")
        val where = "WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT ud.user_id, ud.cards_distributor_id, d.distributor_name, u.first_name, u.last_name_initial
            FROM ASOP_USER_CARDS_DISTRIBUTORS ud
            LEFT JOIN ASOP_CARDS_DISTRIBUTORS d ON d.cards_distributor_id = ud.cards_distributor_id
            LEFT JOIN ASOP_USERS u ON u.user_id = ud.user_id
            $where ORDER BY u.first_name, d.distributor_name
        """.trimIndent()
        var spec = db.sql(sql)
        for ((k, v) in params) spec = spec.bind(k, v)
        return spec.fetch().all()
    }

    fun create(userId: String, cardsDistributorId: String): Mono<Map<String, Any?>> {
        val uId = UUID.fromString(userId); val dId = UUID.fromString(cardsDistributorId)
        // Idempotent: ON CONFLICT оживляет soft-deleted связь (PK user+distributor).
        return db.sql(
            """
            INSERT INTO ASOP_USER_CARDS_DISTRIBUTORS (user_id, cards_distributor_id)
            VALUES (:userId, :cardsDistributorId)
            ON CONFLICT (user_id, cards_distributor_id) DO UPDATE SET deleted_at = NULL
            """.trimIndent()
        )
            .bind("userId", uId).bind("cardsDistributorId", dId).fetch().rowsUpdated()
            .then(list(userId, cardsDistributorId).next())
    }

    fun delete(userId: String, cardsDistributorId: String): Mono<Long> =
        db.sql("DELETE FROM ASOP_USER_CARDS_DISTRIBUTORS WHERE user_id = :userId AND cards_distributor_id = :cardsDistributorId")
            .bind("userId", UUID.fromString(userId)).bind("cardsDistributorId", UUID.fromString(cardsDistributorId)).fetch().rowsUpdated()
}
