// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "top.yukonga.miuix.kmp"
    compileSdk = 35
    defaultConfig {
        minSdk = 26
    }
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

kotlin {
    androidTarget() // 注册 Android target
    jvm("desktop")
    // 如需支持 iOS/macOS/JS/wasm，可自行补充
    withSourcesJar(true)
    jvmToolchain(21)
}

dependencies {
    implementation("androidx.compose.ui:ui:1.8.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.8.1")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.window:window:1.2.0")
    implementation("androidx.window:window-java:1.2.0")
    implementation("androidx.window:window-core:1.2.0")
    implementation("androidx.window:window-rxjava2:1.2.0")
    implementation("androidx.window:window-rxjava3:1.2.0")
    implementation("androidx.graphics:graphics-path:1.0.0-alpha03")
    implementation("androidx.graphics:graphics-shapes:1.0.0-alpha03")
    implementation("androidx.compose.ui:ui-tooling:1.8.1")
    implementation("androidx.compose.ui:ui-tooling-preview:1.8.1")
    implementation("androidx.compose.material:material:1.8.1")
    implementation("androidx.compose.material:material-icons-extended:1.6.0")
    implementation("androidx.compose.runtime:runtime:1.8.1")
    implementation("androidx.compose.foundation:foundation-layout:1.8.1")
    implementation("androidx.compose.ui:ui-graphics:1.8.1")
    // ...如有其他依赖请补充...
}
