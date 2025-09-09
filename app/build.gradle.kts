plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("kotlin-kapt")
}

android {
    namespace = "com.xzyht.notifyrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xzyht.notifyrelay"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.00.171"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
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
        buildConfig = true
    }

    // 只在 release 构建时启用 ABI splits，debug 只生成 universal APK
    splits {
        abi {
            // 只在包含 Release 任务时启用分包，否则只 universal
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
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
    implementation("androidx.compose.material:material:1.8.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling:1.8.1")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.compose.runtime:runtime:1.8.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.8.1")

    // Miuix Compose 主题库
    // DataStore 持久化（设备名、规则设置）
    implementation("androidx.datastore:datastore-preferences:1.0.0")
    implementation("androidx.datastore:datastore:1.0.0")
    // Gson 用于通知历史 JSON 文件读写
    implementation("com.google.code.gson:gson:2.10.1")
    // OkHttp & Okio 用于 WebSocket 和 IO
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okio:okio:3.7.0")
    // 局域网设备发现 jmdns
    implementation("org.jmdns:jmdns:3.5.7")
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
