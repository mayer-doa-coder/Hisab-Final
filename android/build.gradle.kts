// Root build file. Plugins are declared here (but not applied) so every
// module resolves the same version — see the app module for where they're used.
//
// No org.jetbrains.kotlin.android plugin: AGP 9's built-in Kotlin support
// replaces it (that plugin now actively fails the build if applied).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.spotless) apply false
}
