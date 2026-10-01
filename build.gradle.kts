buildscript {
    dependencies {
        // AGP 9 ships built-in Kotlin; pin it to the version the Compose compiler plugin uses.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
