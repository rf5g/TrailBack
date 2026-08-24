import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.trailback.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trailback.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Подпись читается из keystore.properties (не хранится в репозитории)
    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val keystoreProperties = Properties()
    val hasKeystoreFile = keystorePropertiesFile.exists()
    if (hasKeystoreFile) {
        keystoreProperties.load(FileInputStream(keystorePropertiesFile))
    }

    signingConfigs {
        create("release") {
            val storeFilePath = keystoreProperties.getProperty("storeFile")
                ?: System.getenv("KEYSTORE_PATH")
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = keystoreProperties.getProperty("storePassword")
                    ?: System.getenv("KEYSTORE_PASSWORD")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                    ?: System.getenv("KEY_ALIAS")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                    ?: System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:${property("versionCoreKtx")}")
    implementation("androidx.appcompat:appcompat:${property("versionAppcompat")}")
    implementation("com.google.android.material:material:${property("versionMaterial")}")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Lifecycle / ViewModel / Coroutines
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:${property("versionLifecycle")}")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:${property("versionLifecycle")}")
    implementation("androidx.lifecycle:lifecycle-service:${property("versionLifecycle")}")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:${property("versionCoroutines")}")

    // Room
    implementation("androidx.room:room-runtime:${property("versionRoom")}")
    implementation("androidx.room:room-ktx:${property("versionRoom")}")
    ksp("androidx.room:room-compiler:${property("versionRoom")}")

    // Геолокация
    implementation("com.google.android.gms:play-services-location:${property("versionPlayServicesLocation")}")

    // Offline-карты
    implementation("org.mapsforge:mapsforge-core:${property("versionMapsforgeCore")}")
    implementation("org.mapsforge:mapsforge-map:${property("versionMapsforgeMap")}")
    implementation("org.mapsforge:mapsforge-map-android:${property("versionMapsforgeMapAndroid")}")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
    testImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
