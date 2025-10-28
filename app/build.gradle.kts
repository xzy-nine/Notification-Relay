import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("kotlin-kapt")
}
// 使用 buildSrc 的 JGit 实现计算版本信息（避免启动外部进程，兼容 configuration-cache）
// （注意：版本信息在下面会被再次计算；避免重复定义同名 top-level 属性以消除编译歧义）



// 自动生成版本号：遵循仓库约定
// 规则摘要（来自项目说明）：
// version = major.minor.patch
// - major: 可通过 gradle.properties 的 versionMajor 覆盖，默认 0
// - minor: main 分支的提交数（主线提交计数）
// - patch: 如果当前分支为 main，则使用当前日期的 MMdd（例如 1027 -> Oct 27）；否则使用当前 HEAD 的提交数（dev 分支下）
// 生成的 versionCode 采用如下编码：major*10_000_000 + minor*1000 + patch
// 这个值应在 32-bit int 范围内（对于常见 repo 提交量是安全的）。

// 主版本号（major）
// - 直接在此处设置主版本号；不再从 gradle.properties 读取。
// - 在发布重大版本时请在这里更新此值（并可同时调整下方的 versionMajorSubtract）。
// 例如：val versionMajor: Int = 1
val versionMajor: Int = 0 // <-- 在此处直接修改主版本号

fun gitOutput(vararg args: String): String {
    val stdout = ByteArrayOutputStream()
    try {
        exec {
            commandLine = listOf("git", *args)
            isIgnoreExitValue = true
            standardOutput = stdout
        }
    } catch (e: Exception) {
        // 如果没有 git 或执行失败，返回空字符串
    }
    return stdout.toString().trim()
}
// 使用 buildSrc 中的 Versioning 实现来计算版本信息（包含对非 main 分支仅统计独有提交的修订数）
// 支持在此文件内直接设置次版本（minor）减量（不使用 gradle.properties）：
// - 当主版本号（versionMajor）升级后，可以在下面直接把 `versionMajorSubtract` 改为期望的值，
//   这样 main 的提交计数会在计算中减去该值（下限为 0），防止次版本无限递增。
// - 示例：如果希望在 major 升级后把 main 的计数回退 340，则设置为 340。
val versionMajorSubtract: Int = 0 // <-- 在此处直接修改以手动应用减量
val versionInfo = Versioning.compute(rootProject.projectDir, versionMajor, versionMajorSubtract)
val computedVersionName = versionInfo.versionName
val computedVersionCode = versionInfo.versionCode


android {
    namespace = "com.xzyht.notifyrelay"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xzyht.notifyrelay"
        minSdk = 26
        targetSdk = 35
        // 使用自动计算的版本号
        versionCode = computedVersionCode
        versionName = computedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("debug") {
        }
        getByName("release") {
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
        (this as org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions).jvmTarget = "11"
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
