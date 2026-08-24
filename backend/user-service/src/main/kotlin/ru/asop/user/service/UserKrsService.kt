package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class UserKrsService(private val db: DatabaseClient) {

    fun list(userId: String?, auditServiceId: String?): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (userId != null) { conditions.add("uk.user_id = :userId"); params["userId"] = UUID.fromString(userId) }
        if (auditServiceId != null) { conditions.add("uk.audit_service_id = :auditServiceId"); params["auditServiceId"] = UUID.fromString(auditServiceId) }
        conditions.add("uk.deleted_at IS NULL")
        val where = "WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT uk.user_id, uk.audit_service_id, s.service_name, u.first_name, u.last_name_initial
            FROM ASOP_USER_KRS uk
            LEFT JOIN ASOP_AUDIT_SERVICES s ON s.audit_service_id = uk.audit_service_id
            LEFT JOIN ASOP_USERS u ON u.user_id = uk.user_id
            $where ORDER BY u.first_name, s.service_name
        """.trimIndent()
        var spec = db.sql(sql)
        for ((k, v) in params) spec = spec.bind(k, v)
        return spec.fetch().all()
    }

    fun create(userId: String, auditServiceId: String): Mono<Map<String, Any?>> {
        val uId = UUID.fromString(userId); val kId = UUID.fromString(auditServiceId)
        // Idempotent: ON CONFLICT оживляет soft-deleted связь (PK user+krs).
        return db.sql(
            """
            INSERT INTO ASOP_USER_KRS (user_id, audit_service_id)
            VALUES (:userId, :auditServiceId)
            ON CONFLICT (user_id, audit_service_id) DO UPDATE SET deleted_at = NULL
            """.trimIndent()
        )
            .bind("userId", uId).bind("auditServiceId", kId).fetch().rowsUpdated()
            .then(list(userId, auditServiceId).next())
    }

    fun delete(userId: String, auditServiceId: String): Mono<Long> =
        db.sql("DELETE FROM ASOP_USER_KRS WHERE user_id = :userId AND audit_service_id = :auditServiceId")
            .bind("userId", UUID.fromString(userId)).bind("auditServiceId", UUID.fromString(auditServiceId)).fetch().rowsUpdated()
}
