plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.spring)
}

description = "Root CA и управление сертификатами"

dependencies {
    // Internal
    implementation(project(":backend:shared:asop-common"))

    // Spring Boot
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.actuator)

    // Bouncy Castle для криптографии
    implementation(libs.bouncy.castle)
    implementation(libs.bouncy.castle.pkix)

    // Jackson
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.jsr310)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.mockk)
}