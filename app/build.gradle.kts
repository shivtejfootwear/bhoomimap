plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

fun env(name: String): String = (System.getenv(name) ?: "").trim()
fun envOr(name: String, fallback: String): String = env(name).ifBlank { fallback }

// Public values (safe inside the app; data is protected by Supabase RLS rules)
val publicSupabaseUrl = "https://zhjpkttaxnprprwmibwj.supabase.co"
val publicWebClientId = "346965726862-ld60902ihdh0h3nhdluuamtac24gfc53.apps.googleusercontent.com"
val publicMapsKey = "AIzaSyBmuHvOIhdQKn_qFTt1UFnNLWV9927CTNE" // restricted to com.bhoomimap.app + release SHA-1
val publicSupabaseAnonKey = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InpoanBrdHRheG5wcnByd21pYndqIiwicm9sZSI6ImFub24iLCJpYXQiOjE3OTQ2NzUzOTYsImV4cCI6MjExMDI1MTM5Nn0.r9BZVj87KtVVA42XqTInlSCFjFJsu8P7JU5MWtVdVOw"
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
        buildConfigField("String", "SUPABASE_URL", "\"${envOr("SUPABASE_URL", publicSupabaseUrl)}\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"${envOr("SUPABASE_ANON_KEY", publicSupabaseAnonKey)}\"")
        buildConfigField("String", "WEB_CLIENT_ID", "\"${envOr("WEB_CLIENT_ID", publicWebClientId)}\"")
        manifestPlaceholders["MAPS_API_KEY"] = envOr("MAPS_API_KEY", publicMapsKey)
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
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
