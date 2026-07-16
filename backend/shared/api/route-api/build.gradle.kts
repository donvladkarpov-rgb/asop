plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dependency.management)
}

description = "API contracts for Маршруты и Пути (fare zones, routes, paths, schedule, ...)"

dependencies {
    api(platform(libs.spring.boot.dependencies))
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.validation)
    api(libs.jakarta.validation.api)
    api(project(":backend:shared:asop-common"))
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
}
