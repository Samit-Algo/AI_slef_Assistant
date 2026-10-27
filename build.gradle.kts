plugins {
    alias(libs.plugins.android.application) apply false
    // Not applied anywhere: listing it puts our chosen Kotlin version on the build classpath,
    // which AGP's built-in Kotlin support then uses.
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
