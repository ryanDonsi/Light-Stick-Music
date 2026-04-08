plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlinx.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Hilt AggregateDepsTask가 noIsolation 워커로 실행될 때
// AGP 8.13.1+ 와의 JavaPoet 버전 충돌 방지
buildscript {
    configurations.all {
        resolutionStrategy.force("com.squareup:javapoet:1.14.0")
    }
}