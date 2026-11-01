// Standard Android Studio / Android Gradle Plugin build.
// For a build without AGP and Google's Maven repository see sdkbuild/.
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
        mavenCentral()
    }
}

rootProject.name = "ssh-android"
include(":app")
