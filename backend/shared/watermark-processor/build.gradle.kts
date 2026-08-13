plugins {
    `java-library`
}

description = "WatermarkProcessor R2DBC implementation for per-terminal event ordering (prompt 012)"

dependencies {
    api(project(":backend:shared:asop-common"))
    api("org.springframework.data:spring-data-r2dbc:3.3.5")
    api(libs.r2dbc.postgresql)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    api("io.projectreactor:reactor-core:3.6.11")
    api(libs.slf4j.api)
}
