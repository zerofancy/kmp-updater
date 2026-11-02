import org.jetbrains.kotlin.gradle.dsl.JvmTarget
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.compose)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.vanniktech.mavenPublish)
}
group = "io.github.zerofancy"
version = "0.1.0-SNAPSHOT"
kotlin {
    explicitApi()
    jvm { compilerOptions { jvmTarget = JvmTarget.JVM_11 } }
    android {
        namespace = "top.ntutn.updater.compose"
        compileSdk = 37
        minSdk = 24
        withHostTestBuilder {}.configure {}
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
    }
    sourceSets {
        commonMain.dependencies {
            api(project(":updater"))
            api(compose.runtime)
            implementation(compose.foundation)
            implementation(libs.material3)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
mavenPublishing {
    coordinates(group.toString(), "kmp-updater-compose", version.toString())
    pom {
        name = "KMP Updater Compose"
        description = "Optional Material 3 update UI for Android and JVM Compose applications."
        url = "https://github.com/zerofancy/kmp-updater"
        licenses { license { name = "MIT License"; url = "https://opensource.org/license/mit" } }
        developers { developer { id = "zerofancy"; name = "zerofancy"; url = "https://github.com/zerofancy" } }
        scm { url = "https://github.com/zerofancy/kmp-updater" }
    }
}
