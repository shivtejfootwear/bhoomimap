plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun env(name: String): String = (System.getenv(name) ?: "").trim()
val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.bhoomimap.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bhoomimap.app"
        minSdk = 26
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        buildConfigField("String", "SUPABASE_URL", "\"${env("SUPABASE_URL")}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${env("SUPABASE_ANON_KEY")}\"")
        buildConfigField("String", "WEB_CLIENT_ID", "\"${env("WEB_CLIENT_ID")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = env("MAPS_API_KEY")
    }

    signingConfigs {
        create("release") {
            storeFile = file("release.jks")
            storePassword = env("KEYSTORE_PASSWORD")
            keyAlias = "bhoomimap"
            keyPassword = env("KEYSTORE_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.10" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.maps.android:maps-compose:4.3.3")
    implementation("com.google.android.gms:play-services-maps:18.2.0")
    implementation("io.coil-kt:coil-compose:2.5.0")
    implementation("androidx.credentials:credentials:1.2.2")
    implementation("androidx.credentials:credentials-play-services-auth:1.2.2")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.0")
}
