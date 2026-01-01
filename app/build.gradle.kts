plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.ghostyapps.heycam"
    compileSdk = 36 // (veya release(36) ise kalsın)

    defaultConfig {
        applicationId = "com.ghostyapps.heycam"
        minSdk = 28 // <-- DÜŞÜRÜLDÜ: Artık 28 yapıyoruz ki eski telefonlarda da çalışsın
        targetSdk = 36 // (veya 36)
        versionCode = 1
        versionName = "0.2.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // --- EKLENMESİ GEREKEN KRİTİK KISIM BAŞLANGIÇ ---
    buildFeatures {
        compose = true
        buildConfig = true // <-- BU SATIR KIRMIZILIĞI ÇÖZER
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

    buildTypes {
        release {
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
    buildFeatures {
        compose = true
    }

    applicationVariants.all {
        val variant = this
        variant.outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            val version = variant.versionName
            val name = "HeyCam_v${version}.apk"
            output.outputFileName = name
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(files("libs/glyph-matrix-sdk-1.0.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.9.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
// Cetvel (Ruler) için RecyclerView
    implementation("androidx.recyclerview:recyclerview:1.3.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.cardview:cardview:1.0.0")
    // Nothing Matrix SDK (Dosya ismini indirdiğinle aynı yap)
    // VEYA libs klasöründeki hepsini al:
    // implementation fileTree(dir: 'libs', include: ['*.aar'])
    // Not: Versiyon değişmiş olabilir, çalışmazsa Nothing Developer sitesinden son sürüme bakarız.
}