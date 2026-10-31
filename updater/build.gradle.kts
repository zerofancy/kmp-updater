// Adapted from Kotlin/multiplatform-library-template; see NOTICE and UPSTREAM-LICENSE.txt.
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.vanniktech.mavenPublish)
}

group = "io.github.zerofancy"
version = "0.1.0-SNAPSHOT"

kotlin {
    explicitApi()
    jvm {
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    android {
        namespace = "top.ntutn.updater"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        withHostTestBuilder {}.configure {}
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    sourceSets {
        commonMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies { implementation(libs.ktor.client.okhttp) }
        jvmMain.dependencies { implementation(libs.ktor.client.okhttp) }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ktor.client.mock)
        }
    }
}

mavenPublishing {
    coordinates(group.toString(), "kmp-updater", version.toString())
    pom {
        name = "KMP Updater"
        description = "Android and JVM foundation for a Kotlin Multiplatform application updater."
        inceptionYear = "2026"
        url = "https://github.com/zerofancy/kmp-updater"
        licenses {
            license {
                name = "MIT License"
                url = "https://opensource.org/license/mit"
                distribution = "repo"
            }
        }
        developers {
            developer {
                id = "zerofancy"
                name = "zerofancy"
                url = "https://github.com/zerofancy"
            }
        }
        scm {
            url = "https://github.com/zerofancy/kmp-updater"
            connection = "scm:git:https://github.com/zerofancy/kmp-updater.git"
            developerConnection = "scm:git:ssh://git@github.com/zerofancy/kmp-updater.git"
        }
    }
}
