import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}

// 本机专属路径一律从这里读，绝不写进仓库：local.properties 已被 .gitignore 排除。
val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
val spektraEngineDir = (providers.gradleProperty("spektraEngineDir").orNull
    ?: localProperties.getProperty("spektraEngineDir"))?.takeIf { it.isNotBlank() }

android {
    namespace = "com.spektralab.android"
    compileSdk = 36

    defaultConfig {
        // 与上游 Theia 的 com.spektralab.android 区分开，两者可在同一台手机上并存安装。
        applicationId = "com.spektralabcam.android"
        minSdk = 26
        targetSdk = 36
        // versionCode 从 1000 起，每发一个包只加 1，永不回退、永不复用（内部调试号 v87/v88 不进公开版本）。
        versionCode = 1001
        // 对外版本用语义化版本（SemVer 2.0）。versionName 只放短号本身，不带任何后缀
        // （手机“应用信息 / 应用软件详情”里显示的就是这一串）；上游基座版本单独标注在关于页与仓库文档里。
        versionName = "1.0.1"
        testInstrumentationRunner = "com.spektralab.android.FunctionalAuditInstrumentation"

        // Vulkan 与 LibRaw 在 64 位 ARM 上最稳；加 x86_64 方便在模拟器上跑。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                // 引擎是 C++20。真实引擎移植（M0）时可能需要补充它自己的编译标志。
                cppFlags += "-std=c++20"
                // 默认编译 APK 内置的上游官方预编译内核（src/main/jniLibs/arm64-v8a/libspektralab.so）。
                // 想从源码自编译引擎时，在 local.properties 里加一行 spektraEngineDir=你的引擎目录，
                // 并恢复 android.externalNativeBuild.cmake.path 那一行即可。
                arguments += buildList {
                    add("-DANDROID_STL=c++_shared")
                    add("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON")
                    spektraEngineDir?.let { add("-DSPEKTRA_ENGINE_DIR=$it") }
                }
            }
        }
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(releaseSigningProperties.getProperty("storeFile", "app/release-signing/spektralab-release.jks"))
            storePassword = releaseSigningProperties.getProperty("storePassword")
            keyAlias = releaseSigningProperties.getProperty("keyAlias", "spektralab-release")
            keyPassword = releaseSigningProperties.getProperty("keyPassword")
            // Some vendor installers inspect the JAR certificate before handing an APK to
            // PackageManager. Keep it alongside the modern APK signing blocks.
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
    }

    externalNativeBuild {
        cmake {
            // 改用上游 v0.1.8 官方预编译内核（src/main/jniLibs/arm64-v8a/libspektralab.so）。
            // 需要回到自编译内核时，把下面这行恢复即可。
            // path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    ndkVersion = "27.2.12479018"
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.heifwriter:heifwriter:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// ---------------------------------------------------------------------------
// 引擎资源 / 着色器同步（也可直接 `python tools/sync_resources.py`）。
// 手动运行：./gradlew syncEngineResources 或 ./gradlew compileEngineShaders
// ---------------------------------------------------------------------------
val python = "python" // Windows 下用 python；如不在 PATH，改成绝对路径

val materialCatalogAssets = layout.buildDirectory.dir("generated/materialCatalog/assets")
val generateMaterialCatalog = tasks.register<Exec>("generateMaterialCatalog") {
    val profiles = file("src/main/assets/engine/resources/profiles")
    val script = rootProject.file("tools/build_material_catalog.py")
    val output = materialCatalogAssets.get().file("engine/material-catalog.json").asFile
    inputs.dir(profiles)
    inputs.file(script)
    outputs.file(output)
    workingDir = rootProject.projectDir
    commandLine(python, script.absolutePath, "--profiles", profiles.absolutePath, "--output", output.absolutePath)
}
android.sourceSets.getByName("main").assets.srcDir(materialCatalogAssets)
tasks.named("preBuild").configure { dependsOn(generateMaterialCatalog) }

tasks.register<Exec>("syncEngineResources") {
    group = "spektralab"
    description = "把引擎资源同步到 assets/engine/resources/"
    workingDir = rootProject.projectDir
    commandLine(python, "tools/sync_resources.py")
}

tasks.register<Exec>("compileEngineShaders") {
    group = "spektralab"
    description = "用 glslang 编译 Vulkan SPIR-V（需要安装 glslangValidator）"
    workingDir = rootProject.projectDir
    commandLine(python, "tools/compile_shaders.py")
}

