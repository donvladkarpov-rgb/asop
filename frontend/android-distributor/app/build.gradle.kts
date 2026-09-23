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
}

android {
    namespace = "ru.asop.distributor"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.asop.distributor"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField("String", "PAYMENT_APP_KEY", "\"${localProps.getProperty("payment.app.key", "asop-payment-pairing-dev-secret")}\"")
        buildConfigField("String", "PAYMENT_APP_HOST", "\"http://127.0.0.1:8790\"")
        buildConfigField("String", "GATEWAY_BASE_URL", "\"https://$gatewayHost:8080\"")
        buildConfigField("String", "CERT_SIGN_API_KEY", "\"${localProps.getProperty("cert.sign.api.key", "asop-terminal-cert-key")}\"")
        buildConfigField("String", "CERT_SIGN_HMAC_SECRET", "\"${localProps.getProperty("cert.sign.hmac.secret", "9f8e7d6c5b4a3210fedcba9876543210fedcba9876543210fedcba9876543210")}\"")
        // cardsDistributorId дистрибьютора (пусто = админ назначит в web-admin после регистрации)
        buildConfigField("String", "DISTRIBUTOR_CARDS_DISTRIBUTOR_ID", "\"${localProps.getProperty("distributor.cards.distributor.id", "00000000-0000-0000-0000-112000000300")}\"")
        buildConfigField("String", "PAYMENT_PROVIDER_ID", "\"${localProps.getProperty("distributor.payment.provider", "MOCK")}\"")
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

dependencies {
    // Общая NFC/VCM1-библиотека (composite build, ../asop-nfc-lib)
    implementation("ru.asop.nfc:asop-nfc-lib:0.1.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.github.f4b6a3:uuid-creator:6.0.0")

    // OkHttp + Retrofit + Moshi (sync-контур как в терминале)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-moshi:2.11.0")
    implementation("com.squareup.retrofit2:converter-scalars:2.11.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.1")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
}