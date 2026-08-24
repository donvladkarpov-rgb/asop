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

    fun list(regionId: UUID? = null, carrierId: UUID? = null, cardsDistributorId: UUID? = null): Mono<List<Map<String, Any?>>> {
        val baseSql = "SELECT user_id, first_name, last_name, last_name_initial, patronymic_initial, phone, keycloak_id FROM ASOP_USERS"
        // Без scope-фильтров — все живые пользователи (как раньше, + soft-delete).
        if (regionId == null && carrierId == null && cardsDistributorId == null) {
            return db.sql("$baseSql WHERE deleted_at IS NULL ORDER BY first_name")
                .fetch().all().collectList()
        }
        // Глобальный фильтр web-admin — СТРОГАЯ AND-семантика (фильтр-панель сужает список,
        // а не расширяет). Это отличается от /delta (там OR + админ-роли — терминал региона
        // должен видеть пользователей всех его перевозчиков и root-админа).
        //  • carrierId: user в ASOP_USER_CARRIERS с этим carrier;
        //  • cardsDistributorId: user в ASOP_USER_CARDS_DISTRIBUTORS с этим дистрибьютором
        //    (админ дистрибьютора «админ везде где торчит» — фильтр по любому из его дистрибьюторов);
        //  • regionId: user в user_regions региона ИЛИ привязан к любому перевозчику
        //    региона (user_carriers → carriers.region_id) — region-каскад.
        val conditions = mutableListOf<String>()
        if (regionId != null) {
            conditions += """
                (
                    user_id IN (SELECT user_id FROM ASOP_USER_REGIONS WHERE region_id = :regionId AND deleted_at IS NULL)
                    OR user_id IN (
                        SELECT uc.user_id FROM ASOP_USER_CARRIERS uc
                        JOIN ASOP_CARRIERS c ON c.carrier_id = uc.carrier_id
                        WHERE c.region_id = :regionId AND c.deleted_at IS NULL AND uc.deleted_at IS NULL
                    )
                )
            """.trimIndent()
        }
        if (carrierId != null) {
            conditions += "user_id IN (SELECT user_id FROM ASOP_USER_CARRIERS WHERE carrier_id = :carrierId AND deleted_at IS NULL)"
        }
        if (cardsDistributorId != null) {
            conditions += "user_id IN (SELECT user_id FROM ASOP_USER_CARDS_DISTRIBUTORS WHERE cards_distributor_id = :cardsDistributorId AND deleted_at IS NULL)"
        }
        val where = "WHERE (deleted_at IS NULL) AND (${conditions.joinToString(" AND ")})"
        var spec = db.sql("$baseSql $where ORDER BY first_name")
        if (regionId != null) spec = spec.bind("regionId", regionId)
        if (carrierId != null) spec = spec.bind("carrierId", carrierId)
        if (cardsDistributorId != null) spec = spec.bind("cardsDistributorId", cardsDistributorId)
        return spec.fetch().all().collectList()
    }

    fun getById(id: String): Mono<Map<String, Any?>> =
        db.sql("SELECT user_id, first_name, last_name, last_name_initial, patronymic_initial, phone, keycloak_id FROM ASOP_USERS WHERE user_id = :id LIMIT 1")
            .bind("id", parseId(id))
            .fetch().one().defaultIfEmpty(emptyMap())

    fun getByKeycloakId(keycloakId: String): Mono<Map<String, Any?>> =
        db.sql("SELECT user_id, first_name, last_name, last_name_initial, patronymic_initial, phone, keycloak_id FROM ASOP_USERS WHERE keycloak_id = :keycloakId LIMIT 1")
            .bind("keycloakId", keycloakId)
            .fetch().one().defaultIfEmpty(emptyMap())

    fun create(request: UserCreateRequest): Mono<UserResponse> {
        val userId = UuidUtils.newId()
        val email = request.email ?: "${userId}@asop.local"
        val password = request.password ?: "changeit"

        // Промпт 005: UI отправляет только инициал (lastNameInitial). Полная фамилия
        // опциональна; если пусто — деривируем из initial (uppercase первая буква).
        val effectiveLastName = request.lastName?.takeIf { it.isNotBlank() }
            ?: request.lastNameInitial.uppercase()

        // Сначала создаём Keycloak-пользователя (реальный keycloakId), затем пишем
        // в БД — раньше в keycloak_id попадал email ("{userId}@asop.local"), а не
        // UUID из Keycloak → update/delete по keycloak_id падали 404.
        return Mono.fromCallable {
                keycloakAdminService.createUser(
                    email = email,
                    password = password,
                    temporary = true,
                    firstName = request.firstName,
                    lastName = effectiveLastName
                )
            }
            .flatMap { keycloakId ->
                writeUserToDb(userId, request, keycloakId, effectiveLastName)
                    .then(writeAssociations(userId, request.roleIds, request.carrierIds, request.regionIds, request.cardsDistributorIds))
                    .then(assignKeycloakRoles(keycloakId, request.roleIds))
                    .thenReturn(
                        UserResponse(
                            id = userId.toString(),
                            firstName = request.firstName,
                            lastName = effectiveLastName,
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
            val effectiveLastName = request.lastName?.takeIf { it.isNotBlank() }
                ?: request.lastNameInitial.uppercase()

            // Update трогает ТОЛЬКО профиль (имя/инициалы/телефон). Привязки
            // (роли/перевозчики/регионы/дистрибьюторы) и Keycloak-роли НЕ перезаписываются:
            // ими управляют отдельные страницы («Роли пользователей», «Перевозчики
            // пользователей» и т.д.), а форма профиля этих полей не содержит — раньше
            // update молча сносил все привязки (clearAssociations + removeAllRoles).
            val dbOps = db.sql("""
                UPDATE ASOP_USERS SET first_name = :firstName, last_name = :lastName,
                last_name_initial = :lastNameInitial,
                patronymic_initial = :patronymicInitial, phone = :phone WHERE user_id = :id
            """.trimIndent())
                .bind("firstName", request.firstName)
                .bind("lastName", effectiveLastName)
                .bind("lastNameInitial", request.lastNameInitial.take(1))
                .bind("patronymicInitial", request.patronymicInitial ?: "")
                .bind("phone", request.phone ?: "")
                .bind("id", uuid)
                .fetch().rowsUpdated()

            val keycloakOps = if (keycloakId != null) {
                Mono.fromCallable {
                    // email не передан формой профиля — Keycloak username/email не трогаем.
                    keycloakAdminService.updateUser(keycloakId, request.firstName, effectiveLastName, request.email)
                    keycloakId
                }
            } else Mono.empty()

            Mono.`when`(dbOps, keycloakOps).then(
                Mono.fromCallable {
                    UserResponse(
                        id = id, firstName = request.firstName, lastName = effectiveLastName,
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

    private fun writeUserToDb(userId: UUID, request: UserCreateRequest, keycloakId: String, effectiveLastName: String): Mono<Long> =
        db.sql("""
            INSERT INTO ASOP_USERS (user_id, first_name, last_name, last_name_initial, patronymic_initial, phone, keycloak_id)
            VALUES (:userId, :firstName, :lastName, :lastNameInitial, :patronymicInitial, :phone, :keycloakId)
        """.trimIndent())
            .bind("userId", userId)
            .bind("firstName", request.firstName)
            .bind("lastName", effectiveLastName)
            .bind("lastNameInitial", request.lastNameInitial.take(1))
            .bind("patronymicInitial", request.patronymicInitial ?: "")
            .bind("phone", request.phone ?: "")
            .bind("keycloakId", keycloakId)
            .fetch().rowsUpdated()

    private fun writeAssociations(
        userId: UUID,
        roleIds: List<String>,
        carrierIds: List<String>,
        regionIds: List<String>,
        cardsDistributorIds: List<String> = emptyList()
    ): Mono<Void> {
        val roles = insertBatch("ASOP_USER_ROLES", "user_id", "role_id", userId, roleIds)
        val carriers = insertBatch("ASOP_USER_CARRIERS", "user_id", "carrier_id", userId, carrierIds)
        val regions = insertBatch("ASOP_USER_REGIONS", "user_id", "region_id", userId, regionIds)
        val distributors = insertBatch("ASOP_USER_CARDS_DISTRIBUTORS", "user_id", "cards_distributor_id", userId, cardsDistributorIds)
        return Mono.`when`(roles, carriers, regions, distributors)
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
        // DatabaseClient.bind() возвращает НОВЫЙ immutable spec — результат каждого
        // bind обязателен к сохранению (промпт 009: «No parameter specified for [v2_0]»).
        var spec = db.sql("INSERT INTO $table ($col1, $col2) VALUES $placeholders").bind("v1", val1)
        ids.forEachIndexed { i, id -> spec = spec.bind("v2_$i", parseId(id)) }
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
