plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.druk.lmplayground.api"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        aidl = true
        buildConfig = false
        // No resources: this module is a pure contract + SDK, and keeping the
        // AAR resource-free avoids dragging an R class into every consumer.
        androidResources = false
    }

    // Export the .aidl SOURCE into the AAR's aidl/ folder.
    //
    // Strictly unnecessary for our own consumers: AidlCompile also feeds
    // AIDL_SOURCE_OUTPUT_DIR into the library's Java sources, so IChatService,
    // IChatService.Stub and IChatService.Stub.Proxy ship as ordinary compiled
    // classes in classes.jar. Both :app and any client link against those same
    // classes, hence the same DESCRIPTOR and the same transaction codes — no
    // consumer compiles AIDL of its own.
    //
    // We set it anyway so the AAR is self-describing for third parties who want
    // to read (or re-generate) the contract. Note the entries are paths relative
    // to the aidl source root, including the extension — not FQCNs, despite the
    // legacy `aidlFqcns` parameter name.
    aidlPackagedList(
        "com/druk/lmplayground/api/IChatService.aidl",
        "com/druk/lmplayground/api/IChatCompletionCallback.aidl",
    )

    lint {
        // New module — no baseline. It starts clean and stays clean.
        abortOnError = true
    }
}

dependencies {
    implementation(libs.kotlin.stdlib)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.coroutines.test)
}
