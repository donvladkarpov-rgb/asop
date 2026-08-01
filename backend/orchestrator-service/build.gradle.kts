plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.spring)
}

description = "Delta sync orchestrator (Kafka + Redis + Protobuf + MinIO + purge)"

dependencies {

    // Internal
    implementation(project(":backend:shared:asop-common"))
    implementation(project(":backend:shared:asop-kafka-contracts"))
    implementation(project(":backend:shared:asop-proto"))

    // Spring WebFlux (reactive REST)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.actuator)

    // Redis (reactive) for chunk store + shared EventService
    implementation(libs.spring.boot.starter.data.redis.reactive)

    // Kafka consumers
    implementation(libs.spring.kafka)

    // R2DBC (только для purge-job)
    implementation(libs.spring.boot.starter.data.r2dbc)
    implementation(libs.r2dbc.postgresql)

    // AWS S3 (MinIO) — bucket открыт на anonymous download (minio-init),
    // pre-signed URL не требуется; gateway проксирует download.
    implementation("software.amazon.awssdk:s3:2.29.0")
    implementation("software.amazon.awssdk:url-connection-client:2.29.0")

    // Jackson
    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.jsr310)

    // Logging
    implementation(libs.logback.classic)

    // Testing
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.mockk)
}
