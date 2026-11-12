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
        versionCode = 3
        versionName = "0.3.0"
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
    implementation("de.waldheinz:fat32-lib:0.6.5")

    testImplementation("junit:junit:4.13.2")
}

// The disk image tests keep whole FAT volumes in memory.
tasks.withType<Test>().configureEach {
    maxHeapSize = "1g"
}
