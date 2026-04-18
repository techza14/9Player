import org.gradle.internal.os.OperatingSystem
import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "moe.tekuza.m9player"
    ndkVersion = "28.2.13676358"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "moe.tekuza.m9player"
        minSdk = 29
        targetSdk = 36
        versionCode = 24
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++23")
                val rustLibDir = layout.buildDirectory.dir("rustLibs").get().asFile.absolutePath
                arguments += listOf("-DRUST_LIB_DIR=${rustLibDir.replace("\\", "/")}")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
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
    buildFeatures {
        compose = true
    }

    lint {
        disable += setOf(
            "DirectSystemCurrentTimeMillisUsage",
            "DuplicateCrowdInStrings",
        )
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("androidx.media3:media3-transformer:1.3.1")
    implementation("androidx.media:media:1.7.0")
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    implementation("com.github.ankidroid:Anki-Android:api-v1.1.0")
    implementation("com.github.renezuidhof:AudioConverter:1.0.0")
    implementation("io.github.kyant0:taglib:1.0.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation("androidx.compose.material:material-icons-extended")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

val rustProjectDir = layout.projectDirectory.dir("src/main/rust/mdict_native")
val rustOutputDir = layout.buildDirectory.dir("rustLibs")

fun registerRustBuildTask(name: String, targetAbi: String) =
    tasks.register<Exec>(name) {
        val isWindows = OperatingSystem.current().isWindows
        val cargoBinDir = if (isWindows) {
            File(System.getProperty("user.home"), ".cargo\\bin")
        } else {
            File(System.getProperty("user.home"), ".cargo/bin")
        }
        val cargoExe = cargoBinDir.resolve(if (isWindows) "cargo.exe" else "cargo")
        val ndkVersion = android.ndkVersion
        val ndkHomePath = System.getenv("ANDROID_NDK_HOME")
            ?: File(
                File(System.getenv("LOCALAPPDATA") ?: File(System.getProperty("user.home"), "AppData\\Local").absolutePath, "Android\\Sdk"),
                "ndk\\$ndkVersion"
            ).absolutePath

        group = "build"
        description = "Build Rust mdict static library for $targetAbi"
        workingDir(rustProjectDir.asFile)
        environment("PATH", "${cargoBinDir.absolutePath}${File.pathSeparator}${System.getenv("PATH")}")
        environment("ANDROID_NDK_HOME", ndkHomePath)
        commandLine(
            cargoExe.absolutePath,
            "ndk",
            "-t",
            targetAbi,
            "build",
            "--release"
        )
        doLast {
            val targetTriple = when (targetAbi) {
                "arm64-v8a" -> "aarch64-linux-android"
                "x86_64" -> "x86_64-linux-android"
                else -> error("Unsupported ABI: $targetAbi")
            }
            val builtStatic = rustProjectDir.asFile
                .resolve("target")
                .resolve(targetTriple)
                .resolve("release")
                .resolve("libmdict_native.a")
            if (!builtStatic.isFile) {
                throw GradleException("Rust static library not found for $targetAbi at ${builtStatic.absolutePath}")
            }
            val abiOutDir = rustOutputDir.get().asFile.resolve(targetAbi)
            abiOutDir.mkdirs()
            builtStatic.copyTo(abiOutDir.resolve("libmdict_native.a"), overwrite = true)
        }
    }

val buildRustArm64 by registerRustBuildTask("buildMdictRustArm64", "arm64-v8a")
val buildRustX8664 by registerRustBuildTask("buildMdictRustX8664", "x86_64")

tasks.register("buildMdictRust") {
    group = "build"
    description = "Build Rust mdict static libraries for all Android ABIs"
    dependsOn(buildRustArm64, buildRustX8664)
}

tasks.named("preBuild") {
    dependsOn("buildMdictRust")
}
