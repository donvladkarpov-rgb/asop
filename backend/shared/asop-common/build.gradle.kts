plugins {
    `java-library`
}

description = "Common utilities, base classes, exceptions for all ASOP services"

dependencies {
    api(libs.uuid.creator)
    api(libs.jakarta.validation.api)
    api(libs.jackson.kotlin)
    api(libs.jackson.jsr310)
    api("org.springframework:spring-web:6.1.14")
    api("org.springframework:spring-context:6.1.14")
    api(libs.slf4j.api)
}