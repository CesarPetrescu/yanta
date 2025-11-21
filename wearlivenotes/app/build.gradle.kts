plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.wear_livenotes"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.example.wear_livenotes"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    implementation("androidx.activity:activity-compose:1.8.0")
    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Json Parsing
    implementation("com.google.code.gson:gson:2.10.1")
    // Coroutines for background threads
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // UI (Compose) - These are usually default, ensure they exist
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3") // For Phone
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    // Wearable Data Layer
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
}
