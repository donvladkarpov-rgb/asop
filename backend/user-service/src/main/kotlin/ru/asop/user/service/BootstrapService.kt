package ru.asop.user.service

import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import ru.asop.common.util.UuidUtils
import ru.asop.user.config.BootstrapProperties

@Service
class BootstrapService(
    private val bootstrapProperties: BootstrapProperties,
    private val keycloakAdminService: KeycloakAdminService,
    private val databaseClient: DatabaseClient
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun bootstrap() {
        if (!bootstrapProperties.enabled) {
            log.info("Bootstrap disabled")
            return
        }

        databaseClient.sql("SELECT COUNT(*) FROM ASOP_USERS")
            .fetch()
            .one()
            .flatMap { row ->
                val count = (row["count"] as? Number)?.toLong() ?: 0L
                if (count > 0) {
                    log.info("ASOP_USERS already has $count users — bootstrap skipped")
                    return@flatMap reactor.core.publisher.Mono.just(Unit)
                }
                performBootstrap()
            }
            .doOnError { e ->
                log.error("Bootstrap failed: {}", e.message)
            }
            .subscribe()
    }

    private fun performBootstrap(): reactor.core.publisher.Mono<Unit> {
        log.info("Starting bootstrap — no users found in ASOP_USERS")

        return try {
            keycloakAdminService.createRealmIfNotExists()

            keycloakAdminService.createOidcClient(
                clientId = "asop-admin",
                redirectUris = listOf(
                    "http://localhost:3000/*",
                    "http://localhost:*"
                )
            )

            keycloakAdminService.createRole("SUPER_ADMIN")
            keycloakAdminService.createRole("CARRIER_ADMIN")
            keycloakAdminService.createRole("CONTROLLER_ADMIN")
            keycloakAdminService.createRole("DISTRIBUTOR_ADMIN")
            keycloakAdminService.createRole("DISPATCHER")
            keycloakAdminService.createRole("DRIVER")
            keycloakAdminService.createRole("CONTROLLER")
            keycloakAdminService.createRole("PASSENGER")

            val keycloakId = keycloakAdminService.createUser(
                email = bootstrapProperties.adminEmail,
                password = bootstrapProperties.adminPassword,
                temporary = false,
                firstName = bootstrapProperties.adminFirstName,
                lastName = bootstrapProperties.adminFirstName
            )

            keycloakAdminService.assignRole(keycloakId, "SUPER_ADMIN")

            val userId = UuidUtils.newId()
            val roleId = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001")

            databaseClient.sql("""
                INSERT INTO ASOP_USERS (USER_ID, FIRST_NAME, LAST_NAME_INITIAL, KEYCLOAK_ID)
                VALUES (:userId, :firstName, :lastNameInitial, :keycloakId)
            """)
                .bind("userId", userId)
                .bind("firstName", bootstrapProperties.adminFirstName)
                .bind("lastNameInitial", bootstrapProperties.adminLastNameInitial)
                .bind("keycloakId", keycloakId)
                .fetch()
                .rowsUpdated()
                .flatMap {
                    databaseClient.sql("""
                        INSERT INTO ASOP_USER_ROLES (USER_ID, ROLE_ID)
                        VALUES (:userId, :roleId)
                    """)
                        .bind("userId", userId)
                        .bind("roleId", roleId)
                        .fetch()
                        .rowsUpdated()
                }
                .doOnSuccess {
                    log.info("Bootstrap complete. Login: {}", bootstrapProperties.adminEmail)
                }
                .thenReturn(Unit)
        } catch (e: Exception) {
            log.error("Bootstrap failed: {}", e.message, e)
            reactor.core.publisher.Mono.error(e)
        }
    }
}
