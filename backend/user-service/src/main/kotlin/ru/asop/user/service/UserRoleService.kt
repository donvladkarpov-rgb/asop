package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.util.UUID

@Service
class UserRoleService(private val db: DatabaseClient) {

    /**
     * Список ведётся ОТ ASOP_USERS (LEFT JOIN user_roles/roles): пользователь без
     * ролей всё равно виден в web-admin («Роли пользователей») — с пустой ролью,
     * чтобы её можно было назначить. Роль «водитель» с карты активации живёт в
     * bitmask карты (ASOP_CARD_MIFARES), НЕ в user_roles.
     *  • userId — конкретный пользователь;
     *  • roleId — только пользователи, ИМЕЮЩИЕ эту роль;
     *  • regionId — region-каскад (user_regions ∪ user_carriers→carriers.region_id).
     */
    fun list(userId: String?, roleId: String?, regionId: UUID? = null): Flux<Map<String, Any?>> {
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any>()
        conditions += "u.deleted_at IS NULL"
        conditions += "(ur.user_id IS NULL OR ur.deleted_at IS NULL)"
        if (userId != null) { conditions.add("u.user_id = :userId"); params["userId"] = UUID.fromString(userId) }
        if (roleId != null) { conditions.add("ur.role_id = :roleId"); params["roleId"] = UUID.fromString(roleId) }
        if (regionId != null) {
            conditions.add(
                """
                (
                    u.user_id IN (SELECT user_id FROM ASOP_USER_REGIONS WHERE region_id = :regionId AND deleted_at IS NULL)
                    OR u.user_id IN (
                        SELECT uc.user_id FROM ASOP_USER_CARRIERS uc
                        JOIN ASOP_CARRIERS c ON c.carrier_id = uc.carrier_id
                        WHERE c.region_id = :regionId AND c.deleted_at IS NULL AND uc.deleted_at IS NULL
                    )
                )
                """.trimIndent()
            )
            params["regionId"] = regionId
        }
        val where = "WHERE ${conditions.joinToString(" AND ")}"
        val sql = """
            SELECT u.user_id, ur.role_id, r.role_name, u.first_name, u.last_name_initial
            FROM ASOP_USERS u
            LEFT JOIN ASOP_USER_ROLES ur ON ur.user_id = u.user_id
            LEFT JOIN ASOP_ROLES r ON r.role_id = ur.role_id
            $where ORDER BY u.first_name, u.last_name_initial, r.role_name
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
