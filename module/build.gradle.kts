// Root build. The Android plugin is applied in :app only.
plugins {
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    alias(libs.plugins.agp.app) apply false
}
