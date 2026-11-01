import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Shared with sdkbuild/build.gradle.kts
val appProps = Properties().apply { file("version.properties").reader().use { load(it) } }

android {
    namespace = appProps.getProperty("applicationId")
    compileSdk = appProps.getProperty("compileSdk").toInt()

    defaultConfig {
        applicationId = appProps.getProperty("applicationId")
        minSdk = appProps.getProperty("minSdk").toInt()
        targetSdk = appProps.getProperty("targetSdk").toInt()
        versionCode = appProps.getProperty("versionCode").toInt()
        versionName = appProps.getProperty("versionName")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Replace with your own signing config for distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources {
            // multi-release / OSGi metadata of jsch and bouncycastle
            excludes += setOf("META-INF/versions/**", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "OSGI-INF/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    testImplementation("junit:junit:4.13.2")
}
