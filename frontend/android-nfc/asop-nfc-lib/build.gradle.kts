plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

group = "ru.asop.nfc"
version = "0.1.0"

android {
    namespace = "ru.asop.nfc"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api("com.github.f4b6a3:uuid-creator:6.0.0")
}