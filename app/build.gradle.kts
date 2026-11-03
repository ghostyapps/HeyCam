import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.ghostyapps.heycam"
    compileSdk = 36 // (veya release(36) ise kalsın)

    defaultConfig {
        applicationId = "com.ghostyapps.heycam"
        minSdk = 28 // <-- DÜŞÜRÜLDÜ: Artık 28 yapıyoruz ki eski telefonlarda da çalışsın
        targetSdk = 36 // (veya 36)
        versionCode = 4
        versionName = "0.3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    flavorDimensions.add("version")
    productFlavors {
        create("standard") {
            dimension = "version"
            minSdk = 28 // Standart cihazlar (Android 9+)
            versionNameSuffix = "-standard"
        }
        create("nothing") {
            dimension = "version"
            minSdk = 34 // Nothing Phone (Android 14+)
            versionNameSuffix = "-nothing"
        }
    }

    // Release signing reads keystore.properties from the project root (git-ignored):
    //   storeFile=/path/to/key.jks
    //   storePassword=...
    //   keyAlias=...
    //   keyPassword=...
    val keystoreFile = rootProject.file("keystore.properties")
    val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use { load(it) } }
    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // Installs next to the release build instead of replacing it.
            applicationIdSuffix = ".dev"
            manifestPlaceholders["appLabel"] = "Koshi Dev"
        }
        release {
            manifestPlaceholders["appLabel"] = "Koshi"
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
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
    kotlinOptions {
        jvmTarget = "11"
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val version = variant.versionName
            val name = "Koshi_v${version}.apk"
            output.outputFileName = name
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(files("libs/glyph-matrix-sdk-1.0.aar"))
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
