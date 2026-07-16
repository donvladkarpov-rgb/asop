package ru.asop.user.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "asop.bootstrap")
data class BootstrapProperties(
    val enabled: Boolean = true,
    val adminEmail: String = "admin@asop.local",
    val adminPassword: String = "admin",
    val adminFirstName: String = "Admin",
    val adminLastNameInitial: String = "A"
)
