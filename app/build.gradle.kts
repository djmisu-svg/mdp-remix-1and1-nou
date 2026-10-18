plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// CI may increase versionCode without editing source or changing package identity.
val mdpDefaultVersionCode = 4
val mdpDefaultVersionName = "1.0.1"
val mdpVersionCode = providers.gradleProperty("mdpVersionCode").orNull?.let {
    requireNotNull(it.toIntOrNull()) { "mdpVersionCode must be an integer." }
} ?: mdpDefaultVersionCode
require(mdpVersionCode in 1..2_100_000_000) { "mdpVersionCode must be a valid positive Android version code." }
val mdpVersionName = providers.gradleProperty("mdpVersionName").orNull ?: mdpDefaultVersionName
require(Regex("[0-9]+\\.[0-9]+\\.[0-9]+").matches(mdpVersionName)) {
    "mdpVersionName must use numeric major.minor.patch format."
}

// Production signing is supplied only by the protected release environment.
// Missing credentials must never cause a debug-signed production APK.
val releaseKeystorePath = providers.environmentVariable("MDP_RELEASE_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("MDP_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("MDP_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("MDP_RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning = listOf(
    releaseKeystorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "ro.mdp.remix"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "ro.mdp.remix"
        minSdk = 23
        targetSdk = 35
        versionCode = mdpVersionCode
        versionName = mdpVersionName
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("production") {
                storeFile = file(requireNotNull(releaseKeystorePath))
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        create("premiumTest") {
            initWith(getByName("debug"))
            applicationIdSuffix = ".premiumtest"
            versionNameSuffix = "-premium-test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("debug")
        }
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("production")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testBuildType = "premiumTest"
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = true }
}

gradle.taskGraph.whenReady {
    val packagesRelease = allTasks.any {
        it.project == project && it.name in setOf(
            "assembleRelease", "bundleRelease", "packageRelease", "installRelease"
        )
    }
    if (packagesRelease) {
        check(hasReleaseSigning && file(requireNotNull(releaseKeystorePath)).isFile) {
            "Production release signing is unavailable. Configure the protected android-production environment; no unsigned or debug-signed release will be built."
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
