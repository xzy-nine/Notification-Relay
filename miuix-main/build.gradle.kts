// Copyright 2025, miuix-kotlin-multiplatform contributors
// SPDX-License-Identifier: Apache-2.0

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
}

android {
    namespace = "io.github.miuix.compose"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation("androidx.compose.ui:ui:1.8.1")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("androidx.compose.foundation:foundation:1.8.1")
    implementation("androidx.compose.runtime:runtime-livedata:1.8.1")
    // ...如有其他依赖请补充...
}
