import com.android.build.api.dsl.ManagedVirtualDevice

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.paparazzi)
}

android {
    compileSdk = libs.versions.compileSdk.get().toInt()
    namespace = "com.druk.lmplayground"

    defaultConfig {
        applicationId = "com.druk.lmplayground"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        // Base version × 1000 + CI run number. Local builds use 0.
        // Keeps ~1000 CI builds per patch before needing a versionName bump.
        versionCode = 1_05_00 * 1000 + (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toInt()
        versionName = "1.5.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DLLAMA_CURL=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_LLAMAFILE=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }

        ndk {
            abiFilters += setOf("arm64-v8a", "x86_64")
        }
    }

    ndkVersion = "27.2.12479018"

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    signingConfigs {
        // We use a bundled debug keystore, to allow debug builds from CI to be upgradable
        val userKeystore = File(System.getProperty("user.home"), ".android/keystore.jks")
        val localKeystore = rootProject.file("debug.keystore")
        val hasKeyInfo = userKeystore.exists()
        named("debug") {
            storeFile = if (hasKeyInfo) userKeystore else localKeystore
            storePassword = if (hasKeyInfo) System.getenv("STORE_PASSWORD") else "android"
            keyAlias = if (hasKeyInfo) System.getenv("LM_PLAYGROUND_KEY_ALIAS") else "androiddebugkey"
            keyPassword = if (hasKeyInfo) System.getenv("LM_PLAYGROUND_KEY_PASSWORD") else "android"
        }
    }

    buildTypes {
        getByName("debug") {
            isJniDebuggable = false
            // Use a distinct applicationId so debug + androidTest installs
            // coexist with any Play Store / release-signed build on the device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        getByName("release") {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    testOptions {
        managedDevices {
            allDevices {
                maybeCreate<ManagedVirtualDevice>("mvdApi35").apply {
                    device = "Pixel"
                    apiLevel = 35
                    systemImageSource = "google"
                    require64Bit = true
                }
            }
        }
    }

    packaging.resources {
        // Multiple dependency bring these files in. Exclude them to enable
        // our test APK to build (has no effect on our AARs)
        excludes += "/META-INF/AL2.0"
        excludes += "/META-INF/LGPL2.1"
    }
}

// ── Play Store screenshot organization ───────────────────────────────
// Paparazzi writes files like "pkg_StoreScreenshots_scene0_hero[French].png"
// Play Store expects fastlane/metadata/android/{locale}/images/phoneScreenshots/{n}_{name}.png
val paparazziLocaleToPlayStore = mapOf(
    "English" to "en-US", "Spanish" to "es-ES", "Portuguese" to "pt-BR",
    "French" to "fr-FR", "German" to "de-DE", "Italian" to "it-IT",
    "Polish" to "pl-PL", "Ukrainian" to "uk", "Romanian" to "ro",
    "Turkish" to "tr-TR", "Arabic" to "ar", "Chinese" to "zh-CN",
    "Japanese" to "ja-JP", "Korean" to "ko-KR", "Indonesian" to "id",
    "Hindi" to "hi-IN", "Vietnamese" to "vi",
    "Thai" to "th", "Dutch" to "nl-NL", "Hebrew" to "iw-IL",
    "Czech" to "cs-CZ", "Swedish" to "sv-SE", "Bengali" to "bn-BD",
    "Malay" to "ms", "Filipino" to "fil-PH", "Norwegian" to "nb-NO",
    "Danish" to "da-DK", "Finnish" to "fi-FI"
)

val sceneRenames = mapOf(
    "scene0_hero" to "0_hero",
    "scene1_chooseModel" to "1_choose_model",
    "scene2_chat" to "2_chat",
    "scene3_generationParams" to "3_generation_params",
    "scene4_modelsDownload" to "4_models_download"
)

tasks.register("organizeScreenshotsForPlayStore") {
    group = "play store"
    description = "Renames Paparazzi snapshots into fastlane/metadata phoneScreenshots layout."

    val snapshotsDir = file("src/test/snapshots/images")
    val fastlaneDir = rootProject.file("fastlane/metadata/android")
    val regex = Regex("""_StoreScreenshots_(scene\d_\w+)\[(\w+)]\.png""")

    inputs.dir(snapshotsDir)
    outputs.dir(fastlaneDir)

    doLast {
        if (!snapshotsDir.exists()) {
            logger.warn("No Paparazzi snapshots found at $snapshotsDir — run recordPaparazziDebug first.")
            return@doLast
        }
        var copied = 0
        snapshotsDir.listFiles()?.forEach { src ->
            val match = regex.find(src.name) ?: return@forEach
            val scene = sceneRenames[match.groupValues[1]] ?: return@forEach
            val locale = paparazziLocaleToPlayStore[match.groupValues[2]] ?: return@forEach
            val destDir = File(fastlaneDir, "$locale/images/phoneScreenshots").apply { mkdirs() }
            src.copyTo(File(destDir, "$scene.png"), overwrite = true)
            copied++
        }
        logger.lifecycle("Organized $copied screenshots into $fastlaneDir")
    }
}

tasks.matching { it.name == "recordPaparazziDebug" }.configureEach {
    finalizedBy("organizeScreenshotsForPlayStore")
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.lifecycle.viewModelCompose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.google.android.material)

    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.iconsExtended)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.util)
    implementation(libs.androidx.compose.ui.viewbinding)
    implementation(libs.androidx.compose.ui.googlefonts)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp3)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
