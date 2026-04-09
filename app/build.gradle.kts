import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use(::load)
    }
}

fun localProperty(name: String): String? {
    return localProperties.getProperty(name)
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: providers.gradleProperty(name).orNull?.trim()?.takeIf { it.isNotEmpty() }
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
        versionCode = 23
        versionName = "1.3.11"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++23")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            val releaseKeystore = localProperty("releaseKeystore")
            val releaseKeyAlias = localProperty("releaseKeyAlias")
            val releaseStorePassword = localProperty("releaseStorePassword")
            val releaseKeyPassword = localProperty("releaseKeyPassword")
            if (
                !releaseKeystore.isNullOrBlank() &&
                !releaseKeyAlias.isNullOrBlank() &&
                !releaseStorePassword.isNullOrBlank() &&
                !releaseKeyPassword.isNullOrBlank()
            ) {
                storeFile = file(releaseKeystore)
                keyAlias = releaseKeyAlias
                storePassword = releaseStorePassword
                keyPassword = releaseKeyPassword
            }
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
