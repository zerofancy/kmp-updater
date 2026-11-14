plugins {
    alias(libs.plugins.compose) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.android.kotlin.multiplatform.library) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.vanniktech.mavenPublish) apply false
}

val onJitPack = providers.environmentVariable("JITPACK").orNull == "true"
val publicationGroup = if (onJitPack) {
    "${providers.environmentVariable("GROUP").get()}.${providers.environmentVariable("ARTIFACT").get()}"
} else "io.github.zerofancy"
val publicationVersion = if (onJitPack) providers.environmentVariable("VERSION").get() else "0.2.2"

allprojects {
    group = publicationGroup
    version = publicationVersion
}
