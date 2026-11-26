// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

// Release signing key, created by build-apk.bat on first run. Never commit this file or the keystore.
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.isFile) file.inputStream().use { load(it) }
}

android {
    namespace = "app.motionanchor"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.motionanchor"
        minSdk = 16
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    // "standard" has no accessibility service, so Play Protect and OEM scanners accept the
    // sideloaded APK. "full" adds the service that draws over the system bars.
    flavorDimensions += "overlay"
    productFlavors {
        create("standard") {
            dimension = "overlay"
            isDefault = true
            buildConfigField("boolean", "ACCESSIBILITY_OVERLAY", "false")
        }
        create("full") {
            dimension = "overlay"
            versionNameSuffix = "-full"
            buildConfigField("boolean", "ACCESSIBILITY_OVERLAY", "true")
        }
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            vcsInfo { include = false }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // Metadata for kotlin-reflect, which the app does not use.
            excludes += "/kotlin/**"
        }
    }

    // No encrypted dependency report in the signing block: smaller APK, cleaner F-Droid builds.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    bundle {
        language { enableSplit = false }
    }
}

dependencies {
    testImplementation(libs.junit)
}
