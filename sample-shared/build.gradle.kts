import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
}
kotlin {
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
    android { namespace = "top.ntutn.updater.sample.shared"; compileSdk = 37; minSdk = 24 }
    sourceSets.commonMain.dependencies {
        if (providers.gradleProperty("usePublishedUpdater").orNull == "true") {
            implementation("io.github.zerofancy:kmp-updater-compose:0.1.0-SNAPSHOT")
        } else implementation(project(":updater-compose"))
        implementation(compose.foundation)
        implementation(libs.material3)
    }
}
