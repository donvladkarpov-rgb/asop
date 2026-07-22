package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class UserRoleService(private val db: DatabaseClient) {

    fun list(userId: String?, roleId: String?): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        if (userId != null) { conditions.add("ur.user_id = :userId"); params["userId"] = UUID.fromString(userId) }
        if (roleId != null) { conditions.add("ur.role_id = :roleId"); params["roleId"] = UUID.fromString(roleId) }
        val where = if (conditions.isNotEmpty()) "WHERE ${conditions.joinToString(" AND ")}" else ""
        val sql = """
            SELECT ur.user_id, ur.role_id, r.role_name, u.first_name, u.last_name_initial
            FROM ASOP_USER_ROLES ur
            LEFT JOIN ASOP_ROLES r ON r.role_id = ur.role_id
            LEFT JOIN ASOP_USERS u ON u.user_id = ur.user_id
            $where ORDER BY ur.user_id, ur.role_id
        """.trimIndent()
        var spec = db.sql(sql)
        for ((k, v) in params) spec = spec.bind(k, v)
        return spec.fetch().all()
    }

    fun create(userId: String, roleId: String): Mono<Map<String, Any?>> {
        val uId = UUID.fromString(userId); val rId = UUID.fromString(roleId)
        return db.sql("INSERT INTO ASOP_USER_ROLES (user_id, role_id) VALUES (:userId, :roleId)")
            .bind("userId", uId).bind("roleId", rId).fetch().rowsUpdated()
            .then(list(userId, roleId).next())
    }

    fun delete(userId: String, roleId: String): Mono<Long> =
        db.sql("DELETE FROM ASOP_USER_ROLES WHERE user_id = :userId AND role_id = :roleId")
            .bind("userId", UUID.fromString(userId)).bind("roleId", UUID.fromString(roleId)).fetch().rowsUpdated()
}
