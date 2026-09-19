import java.util.Properties
import java.io.FileInputStream

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(FileInputStream(f))
}
val gatewayHost = localProps.getProperty("gateway.host", "10.0.2.2")

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "ru.asop.payment"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.asop.payment"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "GATEWAY_BASE_URL", "\"https://$gatewayHost:8080\"")
        buildConfigField("String", "PAYMENT_HMAC_SECRET", "\"${localProps.getProperty("payment.hmac.secret", "asop-payment-pairing-dev-secret")}\"")
        buildConfigField("boolean", "PO_C_MOCK_EMV", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

val ftsdkApiJar = file("libs/FTSDK_api_V1.0.1.30_20251010.jar")
val ftsdkSysApiJar = file("libs/FTSDK_SysAPI_V1.0.243_20251219.jar")

dependencies {
    // FTSDK (Feitian F20 NFC/EMV). Файловые зависимости — в libs/.
    implementation(files(ftsdkApiJar, ftsdkSysApiJar))

    // Compose BOM (те же версии, что в android-terminal)
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Room (офлайн-очередь отчётов)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // WorkManager (фоновый отчёт о платежах, заглушка до mTLS provisioning)
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // DataStore (pairing-секрет)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // UUIDv7
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
}