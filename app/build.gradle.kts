import java.time.LocalDate
import java.time.ZoneId

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.jespersv.fat32app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.jespersv.fat32app"
        minSdk = 33
        targetSdk = 35
        versionCode = 5
        versionName = "0.4.1"

        // Shown at the bottom of the app: build date and the GitHub Actions run number
        // ("local" when built elsewhere), e.g. 2026-11-24.42.
        val buildDate = LocalDate.now(ZoneId.of("Europe/Stockholm"))
        val buildRun = System.getenv("GITHUB_RUN_NUMBER") ?: "local"
        buildConfigField("String", "BUILD_ID", "\"$buildDate.$buildRun\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // A fixed debug key kept in the repo, so builds from any machine (or from
        // GitHub Actions) can be installed over each other without uninstalling.
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        // The USB library logs through android.util.Log, which is a stub in unit tests.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // USB mass storage access (SCSI over USB, partition tables).
    implementation("me.jahnen.libaums:core:0.10.0")
    // FAT12/FAT16/FAT32 file system.
    implementation(project(":fat32-lib"))

    testImplementation("junit:junit:4.13.2")
}

// The disk image tests keep whole FAT volumes in memory.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
