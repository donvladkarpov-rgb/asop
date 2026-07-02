plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "asop-platform"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ============ Shared modules ============
include(
    ":backend:shared:asop-common",
    ":backend:shared:asop-dto",
    ":backend:shared:asop-kafka-contracts"
)


// ============ Backend services ============
include(
    ":backend:gateway-service",
    ":backend:carrier-service",
    ":backend:terminal-service",
    ":backend:user-service",
    ":backend:card-service",
    ":backend:fiscal-service",
    ":backend:debt-service",
    ":backend:session-service",
    ":backend:audit-service"
)
include("backend:asop-crypto-service")
findProject(":backend:asop-crypto-service")?.name = "asop-crypto-service"
