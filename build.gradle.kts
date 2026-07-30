// Top-level build file. Plugins are declared here with `apply false` so the
// versions live in one place (gradle/libs.versions.toml) and modules just
// opt in to the ones they need.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
