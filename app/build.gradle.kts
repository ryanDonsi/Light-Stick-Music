plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.hilt)
    id("kotlin-parcelize")
    kotlin("kapt")
}

android {
    namespace = "com.dongsitech.lightstickmusicdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dongsitech.lightstickmusicdemo"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        multiDexEnabled = true
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        compose = true
        viewBinding = true
    }

    kapt {
        correctErrorTypes = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17로 업그레이드 (Kotlin 2.0.0+ 호환)
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17" // JVM 타겟을 Java 17로 설정
    }
}

dependencies {
    // Kotlin
    implementation(libs.kotlin.stdlib) // Kotlin 표준 라이브러리 추가

    // 코루틴
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android) // Android용 코루틴 추가

    //JSON
    implementation(libs.kotlinx.serialization.json)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.multidex)

    // Google Material
    implementation(libs.material)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    // Jetpack Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.runtime.livedata)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // Media
    implementation(libs.androidx.media)
    implementation(libs.vectordrawable)

    // Coil
    implementation(libs.coil.compose)

    // JTransForms
    implementation(libs.jtransforms)

    // TarsosDSP
    implementation(libs.tarsosdsp.core)
    implementation(libs.tarsosdsp.jvm)

    // Hilt
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Exo Player
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)

    // Media3
    implementation(libs.media3.common)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    // Glide
    implementation(libs.glide)
    kapt(libs.glide.compiler)

    // Blur (Glide Transformation)
    implementation(libs.glide.transformations)

    implementation(files("libs/lightstick-sdk-1.3.0.aar"))

    // 테스트
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}