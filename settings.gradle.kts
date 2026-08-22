val snapshotVersion : String? = System.getenv("COMPOSE_SNAPSHOT_ID")

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        snapshotVersion?.let {
            println("https://androidx.dev/snapshots/builds/$it/artifacts/repository/") 
            maven { url = uri("https://androidx.dev/snapshots/builds/$it/artifacts/repository/") }
        }

        google()
        mavenCentral()
    }
}
rootProject.name = "LMPlayground"
include(":app")
// Public IPC API: AIDL contract + JSON codecs + client SDK. Consumed by :app
// (the service side) and by any third-party app (the client side).
include(":playground-api")
// Proof-of-concept third-party client. Depends ONLY on :playground-api —
// that constraint is what proves the API is genuinely public.
include(":samples:chat-client")

