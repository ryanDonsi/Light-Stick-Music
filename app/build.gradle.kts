import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.jetbrainsKotlinAndroid)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlinx.serialization)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
    id("kotlin-parcelize")
}

android {
    namespace = "com.lightstick.music"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lightstick.music"
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // R8 축소/난독화 적용. 규칙은 proguard-rules.pro (releaseTest 도 동일하게 적용됨)
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // 릴리즈 동작 확인용 테스트 빌드 (배포 금지)
        // - release 설정을 그대로 따르므로 BuildConfig.DEBUG=false, src/debug 매니페스트 미적용
        //   (알림 접근 서비스/상태 알림/자동 재연결 제외)
        // - 설치 가능하도록 debug 키로 서명. 배포용 서명은 release + 업로드 키로 별도 수행
        // - applicationId 는 그대로 둠 (앱 간 해제 브로드캐스트가 패키지명으로 대상을 지정하기 때문)
        //   → debug/release 빌드와 동시 설치는 불가, 서로 덮어씀
        // 빌드/설치: ./gradlew installReleaseTest
        create("releaseTest") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.composeCompiler.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17 // Java 17로 업그레이드 (Kotlin 2.0.0+ 호환)
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
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

    // ✅ Splash Screen (추가)
    implementation(libs.androidx.core.splashscreen)

    // Google Material
    implementation(libs.material)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

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

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    // Exo Player
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.dash)

    // Media3
    implementation(libs.media3.common)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)
    implementation(libs.media3.ui.compose)

    implementation(files("libs/lightstick-sdk-2.200.0.aar"))

    // 테스트
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}