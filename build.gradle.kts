buildscript {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.spring) apply false
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.spring.dependency.management) apply false
}

allprojects {
    group = "ru.asop"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    if (project.path.startsWith(":backend")) {
        apply(plugin = "org.jetbrains.kotlin.jvm")

        configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                freeCompilerArgs.addAll(
                    "-Xjsr305=strict",
                    "-opt-in=kotlin.RequiresOptIn",
                    "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
                )
            }
        }

        dependencies {
            "implementation"(rootProject.libs.kotlin.stdlib)
            "implementation"(rootProject.libs.kotlin.reflect)
            "implementation"(rootProject.libs.kotlinx.coroutines.core)
            "implementation"(rootProject.libs.slf4j.api)

            // Тесты — через BOM
            "testImplementation"(platform(rootProject.libs.junit.bom))
            "testImplementation"(rootProject.libs.junit.jupiter)
            "testImplementation"(rootProject.libs.mockk)
            "testRuntimeOnly"(rootProject.libs.junit.platform.launcher)
        }

        tasks.withType<Test> {
            useJUnitPlatform()
        }
    }
}