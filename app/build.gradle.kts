
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

// 版本号由 version.properties 提供（update.yml 在 push 到 main 时按 commit 语义回写），
// 注入优先级：-PversionName/-PversionCode（gradle property）→ VERSION_NAME/VERSION_CODE（环境变量）
// → version.properties 固定值（本地构建使用）。
fun loadFixedVersion(): Pair<String, Int> {
    val props = Properties()
    val file = rootProject.file("version.properties")
    require(file.exists()) {
        "Missing version.properties. Provide -PversionName/-PversionCode (or env VERSION_NAME/VERSION_CODE) when building in CI."
    }
    file.inputStream().use { props.load(it) }

    val name =
        props.getProperty("versionName")
            ?: error("versionName is missing in version.properties")
    val code =
        props.getProperty("versionCode")?.toIntOrNull()
            ?: error("versionCode is missing or not an Int in version.properties")
    return name to code
}

val (fixedVersionName, fixedVersionCode) = loadFixedVersion()
val injectedVersionName =
    providers.gradleProperty("versionName").orNull
        ?: System.getenv("VERSION_NAME")
        ?: fixedVersionName
val injectedVersionCode =
    (
        providers.gradleProperty("versionCode").orNull
            ?: System.getenv("VERSION_CODE")
            ?: fixedVersionCode.toString()
    ).toIntOrNull() ?: fixedVersionCode

android {
    namespace = "com.xzyht.notifyrelay"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        applicationId = "com.xzyht.notifyrelay"
        minSdk = 31
        targetSdk =
            libs.versions.targetSdk
                .get()
                .toInt()
        // 使用 version.properties 提供的版本号（CI 语义化回写）
        versionCode = injectedVersionCode
        versionName = injectedVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        val keystorePath = System.getenv("KEYSTORE_PATH") ?: project.findProperty("KEYSTORE_PATH") as? String
        val signingStorePassword = System.getenv("STORE_PASSWORD") ?: project.findProperty("STORE_PASSWORD") as? String
        val signingKeyPassword = System.getenv("KEY_PASSWORD") ?: project.findProperty("KEY_PASSWORD") as? String
        val signingKeyAlias = System.getenv("KEY_ALIAS") ?: project.findProperty("KEY_ALIAS") as? String

        val localProps =
            Properties().apply {
                rootProject
                    .file("local.properties")
                    .takeIf { it.isFile }
                    ?.inputStream()
                    ?.use { load(it) }
            }
        val keyBaseDir = file(localProps.getProperty("KEYSTORE_PATH") ?: "PublicHub")
        val localPropFiles =
            listOf(
                rootProject.file("signing.local.properties"),
                File(keyBaseDir, "signing.local.properties"),
            )
        localPropFiles.filter { it.isFile }.forEach { file ->
            file.inputStream().use { localProps.load(it) }
        }
        val localKeystore =
            if (keyBaseDir.isFile) {
                keyBaseDir
            } else if (keyBaseDir.isDirectory) {
                keyBaseDir.listFiles()?.firstOrNull { it.isFile && it.name == "PublicHub.jks" }
                    ?: keyBaseDir.listFiles()?.firstOrNull { it.isFile && it.name == "PublicHub" }
            } else {
                null
            }

        val resolvedKeystore =
            keystorePath
                ?: localKeystore?.absolutePath
        val resolvedStorePassword = signingStorePassword ?: localProps.getProperty("STORE_PASSWORD")
        val resolvedKeyPassword = signingKeyPassword ?: localProps.getProperty("KEY_PASSWORD")
        val resolvedKeyAlias = signingKeyAlias ?: localProps.getProperty("KEY_ALIAS")
        val hasSigningCredentials = !resolvedKeystore.isNullOrBlank() && !resolvedStorePassword.isNullOrBlank() && !resolvedKeyPassword.isNullOrBlank() && !resolvedKeyAlias.isNullOrBlank()

        create("release") {
            if (hasSigningCredentials) {
                storeFile = rootProject.file(resolvedKeystore)
                storePassword = resolvedStorePassword
                keyAlias = resolvedKeyAlias
                keyPassword = resolvedKeyPassword
            }
        }
    }

    val releaseSigning = signingConfigs.getByName("release")

    buildTypes {
        getByName("debug") {
            signingConfig = releaseSigning
        }
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = releaseSigning
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    // 只在 release 构建时启用 ABI splits，debug 只生成 universal APK
    splits {
        abi {
            // 只在包含 Release 任务时启用分包，否则只 universal
            isEnable = gradle.startParameter.taskNames.any { it.contains("Release") }
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    // 配置资源打包选项，解决 META-INF/DEPENDENCIES 冲突问题
    packaging {
        resources {
            excludes += "META-INF/DEPENDENCIES"
            // LSPosed 模块入口声明：合并 library AAR 中的 META-INF/xposed/java_init.list 到 APK 根目录
            merges += "META-INF/xposed/*"
        }
        jniLibs {
            // 16KB 页面大小支持：使用未压缩的共享库
            useLegacyPackaging = false
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
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
    implementation(libs.androidx.remote.creation.compose)
    implementation(libs.runtime)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)

    // Jetpack Compose BOM 统一管理版本
    implementation(platform(libs.androidx.compose.bom))

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
    implementation(libs.accompanist.pager.indicators)

    // AndroidX Lifecycle（提供 ViewTreeLifecycleOwner 等）
    implementation(libs.bundles.lifecycle)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Room Database
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    annotationProcessor(libs.androidx.room.compiler)

    // Paging 3
    implementation(libs.bundles.paging)

    // Miuix风格ui库
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    implementation(libs.miuix.icons)
    implementation(libs.miuix.navigation3.ui)
    // Navigation3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigationevent.compose)
    // DataStore 持久化（设备名、规则设置）
    implementation(libs.bundles.datastore)
    // Gson 用于通知历史 JSON 文件读写
    implementation(libs.gson)
    // OkHttp & Okio 用于 WebSocket 和 IO
    implementation(libs.okhttp)
    implementation(libs.okio)
    // 局域网设备发现 jmdns
    implementation(libs.jmdns)

    // Coil: image loading (Kotlin + Coroutines friendly)
    implementation(libs.bundles.coil)
    // DiskLruCache: stable disk-based LRU cache for icons
    implementation(libs.disklrucache)
    // 添加Apache FtpServer依赖用于FTP服务器实现
    implementation(libs.apache.ftpserver)

    // 依赖数据模块
    implementation(project(":data"))
    // 依赖core模块
    implementation(project(":core"))
    // 依赖base模块
    implementation(project(":base"))
    // 依赖checkupdata模块
    implementation(project(":checkupdata"))
    // 依赖superislandui模块
    implementation(project(":superislandui"))
    // 依赖lsp模块（LSPosed 超级岛鉴权绕过）
    implementation(project(":lsp"))
    // 依赖scrcpy模块
    implementation(project(":scrcpy"))
    // 依赖nativecore模块（Rust FFI 绑定 + JNA）
    implementation(project(":nativecore"))
}

tasks.register("printVersionName") {
    doLast {
        println(injectedVersionName)
    }
}

// 编译期解析 AndroidManifest.xml，提取每个 <uses-permission> 紧邻上方的注释（含同行注释）作为
// 用途说明，生成 res/raw/permission_notes.txt（格式：权限名||用途，逐行）。协议页（GuideAgreementPage）
// 通过 R.raw.permission_notes 读取，维护点唯一为 Manifest 注释，不在 Kotlin 中手抄清单。
// 采用 res/raw 资源而非 BuildConfig，以避免 Java 11 不支持文本块导致的编译失败。
val generatePermissionNotes by tasks.registering {
    val manifestFile = file("src/main/AndroidManifest.xml")
    val outputFile = file("src/main/res/raw/permission_notes.txt")
    inputs.file(manifestFile)
    outputs.file(outputFile)
    doLast {
        val text = manifestFile.readText()
        val notes = mutableListOf<String>()
        // 1) 同行注释：<!-- 注释 --> <uses-permission .../>
        val inlineRegex =
            """<!--\s*(.*?)\s*-->\s*<uses-permission\s+android:name="([^"]+)""""
                .toRegex()
        for (m in inlineRegex.findAll(text)) {
            notes.add("${m.groupValues[2]}||${m.groupValues[1]}")
        }
        // 2) 多行注释：上一非空前导注释行 + 下一行 <uses-permission>
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
        var pendingComment: String? = null
        for (line in lines) {
            val comment = """<!--\s*(.*?)\s*-->""".toRegex().find(line)?.groupValues?.get(1)
            if (comment != null && inlineRegex.containsMatchIn(line)) continue
            if (comment != null) {
                pendingComment = comment
                continue
            }
            val perm =
                """<uses-permission\s+android:name="([^"]+)""""
                    .toRegex()
                    .find(line)
                    ?.groupValues
                    ?.get(1)
            if (perm != null) {
                if (pendingComment != null) notes.add("$perm||$pendingComment")
                pendingComment = null
            } else {
                pendingComment = null
            }
        }
        outputFile.parentFile.mkdirs()
        outputFile.writeText(notes.distinct().joinToString("\n"))
    }
}

tasks.named("preBuild") {
    dependsOn(generatePermissionNotes)
}
