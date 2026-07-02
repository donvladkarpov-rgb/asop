plugins {
    `java-library`
}

description = "Data Transfer Objects for REST API and inter-service communication"

dependencies {
    api(project(":backend:shared:asop-common"))
    api(libs.jackson.kotlin)
    api(libs.jakarta.validation.api)
}