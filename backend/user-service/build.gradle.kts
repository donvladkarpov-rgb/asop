plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.spring)
}

description = "User management (ASOP_USERS, Keycloak sync)"

dependencies {
    // Internal
    implementation(project(":backend:shared:asop-common"))
    implementation(project(":backend:shared:asop-dto"))
    implementation(project(":backend:shared:asop-kafka-contracts"))
    implementation(project(":backend:shared:api:user-api"))

    // Spring WebFlux
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)

    // Kafka
    implementation(libs.spring.kafka)

    // Database
    implementation(libs.r2dbc.postgresql)
    runtimeOnly(libs.postgresql.driver)
    implementation(libs.liquibase.core)
    runtimeOnly("org.springframework.boot:spring-boot-starter-jdbc")

    // Keycloak Admin Client
    implementation(libs.keycloak.admin.client)

    // Coroutines
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.coroutines.reactive)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.mockk)
}