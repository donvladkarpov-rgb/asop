package ru.asop.user.service

import jakarta.ws.rs.core.Response
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.KeycloakBuilder
import org.keycloak.representations.idm.CredentialRepresentation
import org.keycloak.representations.idm.RoleRepresentation
import org.keycloak.representations.idm.UserRepresentation
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import ru.asop.user.config.KeycloakProperties

@Service
class KeycloakAdminService(
    private val properties: KeycloakProperties
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun createRealmIfNotExists(): Boolean {
        val keycloak = adminClient()
        return try {
            val realms = keycloak.realms().findAll()
            if (realms.any { it.realm == properties.realm }) {
                log.info("Keycloak realm '{}' already exists", properties.realm)
                false
            } else {
                val realmRep = org.keycloak.representations.idm.RealmRepresentation()
                realmRep.realm = properties.realm
                realmRep.isEnabled = true
                keycloak.realms().create(realmRep)
                log.info("Created Keycloak realm '{}'", properties.realm)
                true
            }
        } catch (e: Exception) {
            log.error("Failed to create Keycloak realm '{}': {}", properties.realm, e.message)
            throw e
        } finally {
            keycloak.close()
        }
    }

    fun createRole(roleName: String) {
        val keycloak = adminClient()
        try {
            val realmResource = keycloak.realm(properties.realm)
            val existing = realmResource.roles().list()
            if (existing.any { it.name == roleName }) {
                log.info("Role '{}' already exists in Keycloak", roleName)
                return
            }
            realmResource.roles().create(
                RoleRepresentation().apply {
                    name = roleName
                }
            )
            log.info("Created Keycloak role '{}'", roleName)
        } catch (e: Exception) {
            log.error("Failed to create Keycloak role '{}': {}", roleName, e.message)
            throw e
        } finally {
            keycloak.close()
        }
    }

    fun createUser(
        email: String,
        password: String,
        temporary: Boolean,
        firstName: String = ""
    ): String {
        val keycloak = adminClient()
        try {
            val realmResource = keycloak.realm(properties.realm)
            val userRep = UserRepresentation().apply {
                this.email = email
                this.username = email
                this.firstName = firstName
                isEnabled = true
                credentials = listOf(
                    CredentialRepresentation().apply {
                        type = CredentialRepresentation.PASSWORD
                        value = password
                        isTemporary = temporary
                    }
                )
            }
            val response = realmResource.users().create(userRep)
            val status = response.statusInfo
            if (status.family != Response.Status.Family.SUCCESSFUL) {
                val errorBody = response.readEntity(String::class.java)
                throw RuntimeException("Keycloak create user failed: $status — $errorBody")
            }
            val userId = response.location.path.takeLastWhile { it != '/' }
            log.info("Created Keycloak user '{}' with id={}", email, userId)
            return userId
        } catch (e: Exception) {
            log.error("Failed to create Keycloak user '{}': {}", email, e.message)
            throw e
        } finally {
            keycloak.close()
        }
    }

    fun assignRole(userId: String, roleName: String) {
        val keycloak = adminClient()
        try {
            val realmResource = keycloak.realm(properties.realm)
            val role = realmResource.roles().get(roleName).toRepresentation()
            val user = realmResource.users().get(userId)
            user.roles().realmLevel().add(listOf(role))
            log.info("Assigned role '{}' to Keycloak user '{}'", roleName, userId)
        } catch (e: Exception) {
            log.error("Failed to assign role '{}' to user '{}': {}", roleName, userId, e.message)
            throw e
        } finally {
            keycloak.close()
        }
    }

    fun updatePassword(userId: String, newPassword: String) {
        val keycloak = adminClient()
        try {
            val userResource = keycloak.realm(properties.realm).users().get(userId)
            userResource.resetPassword(
                CredentialRepresentation().apply {
                    type = CredentialRepresentation.PASSWORD
                    value = newPassword
                    isTemporary = false
                }
            )
            log.info("Password updated for Keycloak user '{}'", userId)
        } catch (e: Exception) {
            log.error("Failed to update password for user '{}': {}", userId, e.message)
            throw e
        } finally {
            keycloak.close()
        }
    }

    private fun adminClient(): Keycloak = KeycloakBuilder.builder()
        .serverUrl("${properties.url}/")
        .realm("master")
        .username(properties.adminUser)
        .password(properties.adminPassword)
        .clientId("admin-cli")
        .build()
}
