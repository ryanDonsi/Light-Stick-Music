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

// SDK 소스를 composite build로 연결 (AAR 대신 로컬 소스 사용)
includeBuild("/home/user/Light-Stick-SDK") {
    dependencySubstitution {
        substitute(module("com.lightstick:lightstick")).using(project(":lightstick"))
        substitute(module("com.lightstick:lightstick-internal-core")).using(project(":lightstick-internal-core"))
    }
}
 