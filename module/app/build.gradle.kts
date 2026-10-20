plugins {
    alias(libs.plugins.agp.app)
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.focuspen.enhance.v2"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.focuspen.enhance.v2"
        minSdk = 28
        targetSdk = 37
        versionCode = 31
        versionName = "0.7.6-guide-dev"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Skeleton phase: we need a valid, installable APK, not a lint-clean project.
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("top.yukonga.miuix.kmp:miuix-ui:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-preference:0.9.4")
    implementation("top.yukonga.miuix.kmp:miuix-icons:0.9.4")
    // Legacy Xposed API 82: provided by the framework at runtime, never packaged.
    compileOnly(libs.xposed.api)
    // Compile-only lifecycle types; the installed framework supplies them at runtime.
    // module.prop deliberately targets API 101 for the existing legacy hook adapter.
    compileOnly(libs.libxposed.api)
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
