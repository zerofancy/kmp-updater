plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}
android {
    namespace = "top.ntutn.updater.sample"
    compileSdk = 37
    defaultConfig {
        applicationId = "top.ntutn.updater.sample"
        minSdk = 24
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.0"
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
        implementation("io.github.zerofancy:kmp-updater:0.2.0")
    } else implementation(project(":updater"))
    implementation(libs.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
