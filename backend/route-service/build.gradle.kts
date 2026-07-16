plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.spring)
}

description = "Route & Path admin service (fare zones, routes, paths, schedule, ...)"

dependencies {
    // Internal
    implementation(project(":backend:shared:asop-common"))
    implementation(project(":backend:shared:api:route-api"))

    // Spring Boot
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    implementation(libs.r2dbc.postgresql)
    runtimeOnly(libs.postgresql.driver)

    implementation(libs.kotlinx.coroutines.reactor)
    implementation(libs.kotlinx.coroutines.reactive)

    // OpenAPI / springdoc
    implementation("org.springdoc:springdoc-openapi-starter-webflux-ui:2.6.0")

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.reactor.test)
}
