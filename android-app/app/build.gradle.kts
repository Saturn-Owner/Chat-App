plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.tim.chatapp"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tim.chatapp"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.security)
    implementation(libs.androidx.biometric)
    implementation("com.goterl:lazysodium-android:5.1.0@aar")
    implementation("net.java.dev.jna:jna:5.14.0@aar")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
