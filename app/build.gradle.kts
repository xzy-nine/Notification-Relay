plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

android {
    namespace = "com.xzyht.notifyrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xzyht.notifyrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.00.001"

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

    // Jetpack Compose 依赖（升级至 1.8.x 以适配 Miuix 0.4.7）
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.8.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.1")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.8.1")

    // Miuix Compose 主题库
    implementation(project(":miuix-main:miuix"))
}

// 强制所有 kotlin-stdlib 依赖使用 1.9.23，避免版本冲突
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin" && requested.name.startsWith("kotlin-stdlib")) {
            useVersion("1.9.23")
        }
    }
}