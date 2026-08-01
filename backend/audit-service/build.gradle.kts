plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.spring)
}

description = "Audit service (KRS, inspections, brigades)"

dependencies {
    // Internal
    implementation(project(":backend:shared:asop-common"))
    implementation(project(":backend:shared:asop-dto"))
    implementation(project(":backend:shared:asop-kafka-contracts"))
    implementation(project(":backend:shared:api:audit-api"))

    // Spring WebFlux
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.actuator)

    // Kafka
    implementation(libs.spring.kafka)
    implementation(libs.reactor.kafka)

    // Database
    implementation(libs.r2dbc.postgresql)
    runtimeOnly(libs.postgresql.driver)
    implementation(libs.liquibase.core)

    // Coroutines
    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.coroutines.reactive)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
    testImplementation(libs.mockk)
}