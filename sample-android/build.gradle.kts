plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val sampleVersionCode = providers.gradleProperty("sampleVersionCode").map { value ->
    value.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: error("sampleVersionCode must be an integer between 1 and 2100000000")
}

android {
    namespace = "top.ntutn.updater.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "top.ntutn.updater.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = sampleVersionCode.getOrElse(6)
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_11; targetCompatibility = JavaVersion.VERSION_11 }
}
dependencies {
    androidTestImplementation(libs.androidx.compose.ui.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
    implementation(project(":sample-shared"))
    if (providers.gradleProperty("usePublishedUpdater").orNull == "true") {
        implementation("io.github.zerofancy:kmp-updater:0.3.0")
    } else implementation(project(":updater"))
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
