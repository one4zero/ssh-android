// Standalone build that produces the APK directly with the Android SDK build-tools
// (aapt2, R8/D8, zipalign, apksigner). It does not need the Android Gradle Plugin or
// Google's Maven repository: all libraries come from Maven Central.
//
//   ANDROID_HOME=/path/to/sdk ../gradlew -p sdkbuild assembleApk
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ssh-term-sdkbuild"
