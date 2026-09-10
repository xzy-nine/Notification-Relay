plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.xzyht.notifyrelay.lsp"
    compileSdk =
        libs.versions.compileSdk
            .get()
            .toInt()

    defaultConfig {
        minSdk =
            libs.versions.minSdk
                .get()
                .toInt()

        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
                "keepRules/rules.keep",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // libxposed API 102：LSPosed 模块开发框架入口（运行时由 LSPosed 提供，不可打包进 APK）
    compileOnly(libs.libxposed.api)
    // DexKit：DEX 文件分析框架，用于查找方法和字段
    implementation(libs.dexkit)
    // EzHookTool：libxposed API 102 热重载世代快照/状态迁移/旧 hook 原子替换框架
    // hook-xposed-102 的 POM 中 core 为 runtime scope，编译期需显式声明 core
    implementation(libs.ezhooktool.core)
    implementation(libs.ezhooktool.xposed102)
}
