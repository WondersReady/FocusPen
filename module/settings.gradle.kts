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
        // Legacy Xposed API 82 is only published on api.xposed.info.
        maven {
            url = uri("https://api.xposed.info/")
            content { includeGroup("de.robv.android.xposed") }
        }
    }
}

rootProject.name = "FocusPenEnhance"
include(":app")
