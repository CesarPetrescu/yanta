plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.example.phone_livenotes"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.phone_livenotes"
        minSdk = 30 // Wear OS 3 requires API 30+
        targetSdk = 34
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
    
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Standard Android/Kotlin libs
    implementation(libs.androidx.core.ktx)

    // Jetpack Compose for Wear OS
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.wear.compose:compose-material:1.3.0")
    implementation("androidx.wear.compose:compose-foundation:1.3.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    
    // For JSON parsing between phone and watch
    implementation("com.google.code.gson:gson:2.10.1")

    // For background coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Needed for watch-specific features
    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    // Test dependencies (optional but good practice)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}