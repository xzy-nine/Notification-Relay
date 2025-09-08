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
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.activity:activity-compose")
    implementation("androidx.window:window")
    implementation("androidx.window:window-java")
    implementation("androidx.window:window-core")
    implementation("androidx.window:window-rxjava2")
    implementation("androidx.window:window-rxjava3")
    implementation("androidx.graphics:graphics-path")
    implementation("androidx.graphics:graphics-shapes")
    implementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.foundation:foundation-layout")
    implementation("androidx.compose.ui:ui-graphics")
    // ...如有其他依赖请补充...
}
