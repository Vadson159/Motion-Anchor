// SPDX-FileCopyrightText: 2026 David Ventura
// SPDX-License-Identifier: GPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
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

    buildTypes {
        release {
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

    bundle {
        language { enableSplit = false }
    }
}

dependencies {
    testImplementation(libs.junit)
}
