import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}
val localConfig = Properties().apply {
    rootProject.file("protocol.properties").inputStream().use { load(it) }
    rootProject.file("release.properties").inputStream().use { load(it) }
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun releaseValue(name: String): String = providers.gradleProperty("release.$name").orNull ?: localConfig.getProperty("release.$name","")
val releaseRepository = releaseValue("repository").ifBlank { System.getenv("GITHUB_REPOSITORY").orEmpty() }
require(releaseRepository.isBlank() || releaseRepository.matches(Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+"))) { "Invalid release.repository; use owner/repository" }
val signingConfigFile = rootProject.file("signing.properties")
val releaseSigning = Properties().apply { if (signingConfigFile.exists()) signingConfigFile.inputStream().use { load(it) } }
fun signingValue(name: String): String = System.getenv("YUNJIAN_${name.replace(Regex("([a-z])([A-Z])"),"$1_$2").uppercase()}") ?: releaseSigning.getProperty(name,"")
android {
    namespace = "io.github.miyouchat"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.miyouchat"
        minSdk = 26
        targetSdk = 36
        versionCode = releaseValue("versionCode").toInt()
        versionName = releaseValue("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "BBS_DS_SALT", "\"${localConfig.getProperty("bbs.ds.salt", "")}\"")
        buildConfigField("String", "BBS_APP_VERSION", "\"${localConfig.getProperty("bbs.app.version")}\"")
        buildConfigField("String", "UPDATE_REPOSITORY", "\"$releaseRepository\"")
    }
    signingConfigs {
        if (signingValue("storeFile").isNotBlank()) create("distribution") {
            storeFile = rootProject.file(signingValue("storeFile"))
            storePassword = signingValue("storePassword")
            keyAlias = signingValue("keyAlias")
            keyPassword = signingValue("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("distribution")
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isReturnDefaultValues = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}
// Fail closed rather than accidentally distributing an unsigned or debug-signed APK.
tasks.matching { it.name == "validateSigningRelease" || it.name == "packageRelease" }.configureEach {
    doFirst {
        require(listOf("storeFile","storePassword","keyAlias","keyPassword").all { signingValue(it).isNotBlank() }) {
            "Release signing missing. Configure signing.properties or YUNJIAN_* environment variables; see RELEASING.md."
        }
        require(!releaseValue("versionName").endsWith("-dev")) { "Set a stable release.versionName before packaging release." }
    }
}
dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
