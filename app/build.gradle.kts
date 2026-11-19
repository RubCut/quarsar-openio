plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "quasar.rubcut.openio"
    compileSdk = 34
    buildToolsVersion = "34.0.0"
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "quasar.rubcut.openio"
        minSdk = 28
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += listOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }

    sourceSets {
        getByName("main") {
            assets.srcDirs("src/main/assets")
        }
    }

    lint {
        // YNDX-0008 ships Android 9 (API 28) and the APK is installed via
        // adb/root, never through Google Play, so raising targetSdk would
        // only add Play-market restrictions that cannot apply here.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":domain"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    // Material Design 3 runtime: AppCompat gives us DayNight theming,
    // MaterialComponents is not available in the offline Gradle cache.
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    // Offline build: appcompat 1.7.0 pins lifecycle 2.6.1 / annotation 1.6.0,
    // which are absent from the local Gradle cache. 2.8.6 / 1.8.1 are cached
    // and binary-compatible, so constrain the graph onto them.
    constraints {
        implementation("androidx.lifecycle:lifecycle-common:2.8.6")
        implementation("androidx.lifecycle:lifecycle-common-java8:2.8.6")
        implementation("androidx.lifecycle:lifecycle-livedata:2.8.6")
        implementation("androidx.lifecycle:lifecycle-livedata-core:2.8.6")
        implementation("androidx.lifecycle:lifecycle-runtime:2.8.6")
        implementation("androidx.lifecycle:lifecycle-viewmodel:2.8.6")
        implementation("androidx.lifecycle:lifecycle-viewmodel-savedstate:2.8.6")
        implementation("androidx.lifecycle:lifecycle-process:2.8.6")
        implementation("androidx.annotation:annotation:1.8.1")
        implementation("androidx.annotation:annotation-experimental:1.4.1")
        implementation("androidx.collection:collection:1.4.4")
        implementation("androidx.core:core:1.13.1")
        implementation("androidx.activity:activity:1.9.2")
        implementation("androidx.emoji2:emoji2-views-helper:1.3.0")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
        implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    }
}