plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.firefrontend"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.firefrontend"
        minSdk = 22 // Fire OS 5
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    // Release signing comes from ~/.gradle/gradle.properties (never committed). Without it, release builds are unsigned.
    val storeFile = providers.gradleProperty("FIREFRONTEND_STORE_FILE").orNull
    signingConfigs {
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = providers.gradleProperty("FIREFRONTEND_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("FIREFRONTEND_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("FIREFRONTEND_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            if (storeFile != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
