package ru.asop.user.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "asop.keycloak")
data class KeycloakProperties(
    val url: String = "http://localhost:8180",
    val realm: String = "asop",
    val adminUser: String = "admin",
    val adminPassword: String = "admin"
)
