plugins {
    `java-library`
}

description = "Kafka event contracts for all ASOP services"

dependencies {
    api(project(":backend:shared:asop-common"))
    api(project(":backend:shared:asop-dto"))
    api(libs.jackson.kotlin)
    api(libs.jackson.jsr310)
}