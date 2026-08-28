// AGP 9 applies Kotlin itself, so org.jetbrains.kotlin.android is gone --
// but the Compose compiler plugin is still declared separately.
// See https://kotl.in/gradle/agp-built-in-kotlin
plugins {
    id("com.android.application") version "9.3.2" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
