plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

description = "Protobuf schema (schema.proto) for ASOP delta sync"

dependencies {
    api("com.google.protobuf:protobuf-java:3.25.5")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
}

// Generated Java sources must be visible to javac of java-library plugin
sourceSets {
    main {
        java {
            srcDir("build/generated/source/proto/main/java")
        }
    }
}
