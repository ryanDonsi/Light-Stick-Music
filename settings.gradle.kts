pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven {
            name = "TarsosDSP repository"
            url = uri("https://mvn.0110.be/releases")
        }
    }
}

rootProject.name = "LightStick Music Demo"
include(":app")

// ─── SDK 소스 연결 (local.properties 우선, 없으면 AAR 폴백) ───────────────────
//
// local.properties 에 아래 한 줄을 추가하면 SDK 소스를 직접 참조합니다:
//
//   lightstick.sdk.path=D\:\\Android_Project\\lightstick-sdk   (Windows)
//   lightstick.sdk.path=/home/user/Light-Stick-SDK             (Linux/Mac)
//
// 해당 키가 없거나 경로가 존재하지 않으면 libs/lightstick-sdk-*.aar 을 사용합니다.
// ─────────────────────────────────────────────────────────────────────────────

val localProps = java.util.Properties().also { props ->
    val f = file("local.properties")
    if (f.exists()) f.inputStream().use(props::load)
}
val sdkSourcePath = localProps.getProperty("lightstick.sdk.path")

if (!sdkSourcePath.isNullOrBlank() && file(sdkSourcePath).exists()) {
    includeBuild(sdkSourcePath) {
        dependencySubstitution {
            substitute(module("com.lightstick:lightstick")).using(project(":lightstick"))
            substitute(module("com.lightstick:lightstick-internal-core")).using(project(":lightstick-internal-core"))
        }
    }
}
 