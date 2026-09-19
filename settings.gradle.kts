plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "asop-platform"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

// ============ Shared modules ============
include(
    ":backend:shared:asop-common",
    ":backend:shared:asop-dto",
    ":backend:shared:asop-kafka-contracts",
    ":backend:shared:asop-proto",
    ":backend:shared:watermark-processor"
)

// ============ Shared API modules ============
include(
    ":backend:shared:api:gateway-api",
    ":backend:shared:api:crypto-api",
    ":backend:shared:api:carrier-api",
    ":backend:shared:api:session-api",
    ":backend:shared:api:terminal-api",
    ":backend:shared:api:card-api",
    ":backend:shared:api:user-api",
    ":backend:shared:api:debt-api",
    ":backend:shared:api:fiscal-api",
    ":backend:shared:api:audit-api",
    ":backend:shared:api:reference-api",
    ":backend:shared:api:route-api",
    ":backend:shared:api:tid-api",
    ":backend:shared:api:payment-api"
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
    ":backend:audit-service",
    ":backend:crypto-service",
    ":backend:admin-service",
    ":backend:route-service",
    ":backend:orchestrator-service",
    ":backend:payment-service"
)

// ============ Android apps + shared NFC/lib ============
// android-nfc включается на уровне корня (sibling composite), а не из terminal/distributor:
// вложенный includeBuild внутри включённого билда ломает типобезопасные accessors
// (RootProjectAccessor.getAsopNfcLib() defined twice).
includeBuild("frontend/android-terminal")
includeBuild("frontend/android-nfc")
