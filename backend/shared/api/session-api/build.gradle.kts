plugins {
    `java-library`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.spring.dependency.management)
}

description = "API contracts for session-service"

dependencies {
    // Spring Boot BOM (для управления версиями)
    api(platform(libs.spring.boot.dependencies))
    
    // API dependencies (transitive)
    api(libs.spring.boot.starter.webflux)
    api(libs.spring.boot.starter.validation)
    api(libs.jakarta.validation.api)
    api(project(":backend:shared:asop-common"))
    
    // Implementation dependencies
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlin.reflect)
}
