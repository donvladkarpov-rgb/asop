package ru.asop.user.service

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import reactor.core.publisher.Mono
import ru.asop.api.user.dto.request.UserCreateRequest
import ru.asop.api.user.dto.response.UserResponse
import ru.asop.common.util.UuidUtils
import java.util.UUID

@Service
class UserAdminService(
    private val db: DatabaseClient,
    private val keycloakAdminService: KeycloakAdminService
) {

    fun list(): Mono<List<Map<String, Any?>>> =
        db.sql("SELECT user_id, first_name, last_name_initial, patronymic_initial, phone, keycloak_id FROM ASOP_USERS ORDER BY first_name")
            .fetch().all().collectList()

    fun getById(id: String): Mono<Map<String, Any?>> =
        db.sql("SELECT user_id, first_name, last_name_initial, patronymic_initial, phone, keycloak_id FROM ASOP_USERS WHERE user_id = :id LIMIT 1")
            .bind("id", parseId(id))
            .fetch().one().defaultIfEmpty(emptyMap())

    fun create(request: UserCreateRequest): Mono<UserResponse> {
        val userId = UuidUtils.newId()
        val email = request.email ?: "${userId}@asop.local"
        val password = request.password ?: "changeit"

        val dbOps = writeUserToDb(userId, request, email)
            .then(writeAssociations(userId, request.roleIds, request.carrierIds, request.regionIds))

        return Mono.fromCallable {
                keycloakAdminService.createUser(
                    email = email,
                    password = password,
                    temporary = true,
                    firstName = request.firstName,
                    lastName = request.lastName
                )
            }
            .flatMap { keycloakId ->
                dbOps
                    .then(assignKeycloakRoles(keycloakId, request.roleIds))
                    .thenReturn(
                        UserResponse(
                            id = userId.toString(),
                            firstName = request.firstName,
                            lastName = request.lastName,
                            lastNameInitial = request.lastNameInitial.take(1),
                            patronymicInitial = request.patronymicInitial,
                            phone = request.phone,
                            email = email,
                            keycloakId = keycloakId
                        )
                    )
                    .onErrorResume { dbError ->
                        log.error("DB write failed after Keycloak user created (keycloakId={}), compensating by deleting Keycloak user: {}", keycloakId, dbError.message)
                        Mono.fromCallable { keycloakAdminService.deleteUser(keycloakId) }
                            .then(Mono.error(dbError))
                    }
            }
    }

    fun update(id: String, request: UserCreateRequest): Mono<UserResponse> {
        val uuid = parseId(id)
        return getById(id).flatMap { existing ->
            if (existing.isEmpty()) return@flatMap Mono.just(UserResponse(id = id, firstName = "", lastName = "", lastNameInitial = ""))
            val keycloakId = existing["keycloak_id"]?.toString()

            val dbOps = db.sql("""
                UPDATE ASOP_USERS SET first_name = :firstName, last_name_initial = :lastNameInitial,
                patronymic_initial = :patronymicInitial, phone = :phone WHERE user_id = :id
            """.trimIndent())
                .bind("firstName", request.firstName)
                .bind("lastNameInitial", request.lastNameInitial.take(1))
                .bind("patronymicInitial", request.patronymicInitial ?: "")
                .bind("phone", request.phone ?: "")
                .bind("id", uuid)
                .fetch().rowsUpdated()
                .then(clearAssociations(uuid))
                .then(writeAssociations(uuid, request.roleIds, request.carrierIds, request.regionIds))

            val keycloakOps = if (keycloakId != null) {
                Mono.fromCallable {
                    val email = request.email ?: "${keycloakId}@asop.local"
                    keycloakAdminService.updateUser(keycloakId, request.firstName, request.lastName, email)
                    keycloakAdminService.removeAllRoles(keycloakId)
                    request.roleIds.forEach { roleId ->
                        val roleName = keycloakAdminService.getRoleName(roleId)
                        if (roleName != null) keycloakAdminService.assignRole(keycloakId, roleName)
                    }
                    keycloakId
                }
            } else Mono.empty()

            Mono.`when`(dbOps, keycloakOps).then(
                Mono.fromCallable {
                    UserResponse(
                        id = id, firstName = request.firstName, lastName = request.lastName,
                        lastNameInitial = request.lastNameInitial.take(1),
                        patronymicInitial = request.patronymicInitial, phone = request.phone,
                        email = request.email, keycloakId = keycloakId
                    )
                }
            )
        }
    }

    fun delete(id: String): Mono<Long> {
        val uuid = parseId(id)
        return getById(id).flatMap { existing ->
            val keycloakId = existing["keycloak_id"]?.toString()
            clearAssociations(uuid)
                .then(db.sql("DELETE FROM ASOP_USERS WHERE user_id = :id").bind("id", uuid).fetch().rowsUpdated())
                .then(
                    if (keycloakId != null) {
                        Mono.fromCallable { keycloakAdminService.deleteUser(keycloakId) }.then(Mono.just(1L))
                    } else Mono.just(1L)
                )
        }
    }

    private fun writeUserToDb(userId: UUID, request: UserCreateRequest, keycloakId: String): Mono<Long> =
        db.sql("""
            INSERT INTO ASOP_USERS (user_id, first_name, last_name_initial, patronymic_initial, phone, keycloak_id)
            VALUES (:userId, :firstName, :lastNameInitial, :patronymicInitial, :phone, :keycloakId)
        """.trimIndent())
            .bind("userId", userId)
            .bind("firstName", request.firstName)
            .bind("lastNameInitial", request.lastNameInitial.take(1))
            .bind("patronymicInitial", request.patronymicInitial ?: "")
            .bind("phone", request.phone ?: "")
            .bind("keycloakId", keycloakId)
            .fetch().rowsUpdated()

    private fun writeAssociations(userId: UUID, roleIds: List<String>, carrierIds: List<String>, regionIds: List<String>): Mono<Void> {
        val roles = insertBatch("ASOP_USER_ROLES", "user_id", "role_id", userId, roleIds)
        val carriers = insertBatch("ASOP_USER_CARRIERS", "user_id", "carrier_id", userId, carrierIds)
        val regions = insertBatch("ASOP_USER_REGIONS", "user_id", "region_id", userId, regionIds)
        return Mono.`when`(roles, carriers, regions)
    }

    private fun clearAssociations(userId: UUID): Mono<Void> {
        val r1 = db.sql("DELETE FROM ASOP_USER_ROLES WHERE user_id = :id").bind("id", userId).fetch().rowsUpdated().then()
        val r2 = db.sql("DELETE FROM ASOP_USER_CARRIERS WHERE user_id = :id").bind("id", userId).fetch().rowsUpdated().then()
        val r3 = db.sql("DELETE FROM ASOP_USER_REGIONS WHERE user_id = :id").bind("id", userId).fetch().rowsUpdated().then()
        return Mono.`when`(r1, r2, r3)
    }

    private fun insertBatch(table: String, col1: String, col2: String, val1: UUID, ids: List<String>): Mono<Void> {
        if (ids.isEmpty()) return Mono.empty()
        val placeholders = ids.mapIndexed { i, _ -> "(:v1, :v2_$i)" }.joinToString(", ")
        val spec = db.sql("INSERT INTO $table ($col1, $col2) VALUES $placeholders").bind("v1", val1)
        ids.forEachIndexed { i, id -> spec.bind("v2_$i", parseId(id)) }
        return spec.fetch().rowsUpdated().then()
    }

    private fun assignKeycloakRoles(keycloakId: String, roleIds: List<String>): Mono<Void> {
        if (roleIds.isEmpty()) return Mono.empty()
        return Mono.fromCallable {
            roleIds.forEach { roleId ->
                val roleName = keycloakAdminService.getRoleName(roleId)
                if (roleName != null) keycloakAdminService.assignRole(keycloakId, roleName)
            }
        }.then().onErrorResume { e ->
            log.warn("Failed to assign some Keycloak roles to '{}': {}", keycloakId, e.message)
            Mono.empty()
        }
    }

    private fun parseId(id: String): UUID =
        try { UUID.fromString(id) }
        catch (e: IllegalArgumentException) { throw IllegalArgumentException("Invalid UUID: '$id'") }

    companion object {
        private val log = org.slf4j.LoggerFactory.getLogger(UserAdminService::class.java)
    }
}
