plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.brosco.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.brosco.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        val apiKey = System.getenv("GROQ_API_KEY") ?: ""
        buildConfigField("String", "GROQ_API_KEY", "\"$apiKey\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    // Overnight briefing mode ("work brosco goodnight") - WorkManager is
    // what actually survives the app process being killed overnight, unlike
    // a plain Handler/coroutine timer which dies the moment Android kills
    // the process to reclaim memory while the phone is idle.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
}
