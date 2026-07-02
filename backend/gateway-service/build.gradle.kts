plugins {
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.spring)
}

description = "Ingestion API Gateway (REST, JWT, Kafka)"

dependencies {

    // Internal
    implementation(project(":backend:shared:asop-common"))
    implementation(project(":backend:shared:asop-kafka-contracts"))
    implementation(project(":backend:shared:api:gateway-api"))

    // Spring WebFlux (reactive REST)
    implementation(libs.spring.boot.starter.webflux)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.boot.starter.oauth2.resource.server)
    implementation(libs.spring.boot.starter.validation)
    implementation(libs.spring.boot.starter.actuator)

    // Spring Kafka (реактивный producer через ReactiveKafkaProducerTemplate)
    implementation(libs.spring.kafka)
    implementation(libs.reactor.kafka)

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