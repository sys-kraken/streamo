plugins {
    id("org.jetbrains.kotlin.multiplatform")
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Code shared by the Android app and the iOS app. The iOS targets only compile on a Mac (Codemagic builds them).
kotlin {
    android {
        namespace = "com.streamo.shared"
        compileSdk = 37
        minSdk = 26
        // Shared images (the logo) are packed into the Android app too.
        androidResources.enable = true
    }

    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    jvmToolchain(17)

    sourceSets {
        commonMain.dependencies {
            implementation(libs.cmp.runtime)
            implementation(libs.cmp.foundation)
            implementation(libs.cmp.ui)
            implementation(libs.cmp.material3)
            implementation(libs.cmp.icons)
            implementation(libs.cmp.resources)
            implementation(libs.cmp.navigation)
            implementation(libs.coil.compose)

            api(libs.coroutines.core)
            api(libs.serialization.json)
            api(libs.ktor.client.core)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            api(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.coil.ktor)
        }
    }
}

compose.resources {
    packageOfResClass = "com.streamo.app.resources"
}
