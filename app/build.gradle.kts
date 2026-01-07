
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties

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
val versionMajor: Int = 1 // <-- 在此处直接修改主版本号

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
val versionMajorSubtract: Int = 238 // <-- 在此处直接修改以手动应用减量，当前main分支提交数为238
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

    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH") ?: project.findProperty("KEYSTORE_PATH") as? String
        val signingStorePassword = System.getenv("STORE_PASSWORD") ?: project.findProperty("STORE_PASSWORD") as? String
        val signingKeyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD") as? String
        val signingKeyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS") as? String

        // Local-only fallback (not committed): read optional properties from two locations, otherwise pick first .jks in PublicHub
        val publicHubDir = file("D:/xzy/nas-Sync/androidKey/PublicHub")
        val localPropFiles = listOf(
            File("D:/xzy/nas-Sync/androidKey/signing.local.properties"),
            File(publicHubDir, "signing.local.properties")
        )
        val localProps = Properties().apply {
            localPropFiles.filter { it.isFile }.forEach { file ->
                file.inputStream().use { load(it) }
            }
        }
        val localKeystore = if (publicHubDir.isDirectory) {
            publicHubDir.listFiles()?.firstOrNull { it.extension == "jks" }
        } else {
            null
        }

        val resolvedKeystore = keystorePath
            ?: localProps.getProperty("KEYSTORE_PATH")
            ?: localKeystore?.absolutePath
        val resolvedStorePassword = signingStorePassword ?: localProps.getProperty("STORE_PASSWORD")
        val resolvedKeyPassword = signingKeyPassword ?: localProps.getProperty("KEY_PASSWORD")
        val resolvedKeyAlias = signingKeyAlias ?: localProps.getProperty("KEY_ALIAS")

        if (!resolvedKeystore.isNullOrBlank() && !resolvedStorePassword.isNullOrBlank() && !resolvedKeyPassword.isNullOrBlank() && !resolvedKeyAlias.isNullOrBlank()) {
            create("release") {
                storeFile = file(resolvedKeystore)
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    val releaseSigning = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")

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
            signingConfig = releaseSigning
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

    // 配置资源打包选项，解决 META-INF/DEPENDENCIES 冲突问题
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
        }
    }
}

dependencies {

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.ui.text)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.foundation.layout)
    implementation(libs.androidx.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Jetpack Compose BOM 统一管理版本
    implementation(platform("androidx.compose:compose-bom:2025.01.00"))
    
    // Jetpack Compose 依赖（通过 BOM 统一管理版本）
    implementation("androidx.activity:activity-compose")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.runtime:runtime-livedata")
    // Compose Pager 用于实现滑动切换（直接指定有效版本）
    implementation("com.google.accompanist:accompanist-pager-indicators:0.36.0")

    // AndroidX Lifecycle（提供 ViewTreeLifecycleOwner 等）
    implementation("androidx.lifecycle:lifecycle-runtime:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-android:2.8.7")

    // Room Database
    implementation("androidx.room:room-runtime:2.8.0")
    kapt("androidx.room:room-compiler:2.8.0")
    implementation("androidx.room:room-ktx:2.8.0")
    
    // Miuix Compose 主题库
    implementation(libs.miuix.android)
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
    
    // Coil: image loading (Kotlin + Coroutines friendly)
    implementation("io.coil-kt:coil:2.4.0")
    // Coil Compose: Jetpack Compose integration for image loading
    implementation("io.coil-kt:coil-compose:2.4.0")
    // DiskLruCache: stable disk-based LRU cache for icons
    implementation("com.jakewharton:disklrucache:2.0.2")

    // Apache SSHD for SFTP server
    implementation(libs.apache.sshd)
    implementation(libs.apache.sshd.sftp)
}

// 移除强制使用旧版本stdlib的配置，让项目使用与Kotlin 2.1.21兼容的stdlib版本

tasks.register("printVersionName") {
    doLast {
        println(computedVersionName)
    }
}


