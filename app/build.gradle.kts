plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

android {
    namespace = "com.forgerig.gatekeeper.proxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.forgerig.gatekeeper.proxy"
        minSdk = 26
        targetSdk = 35
        versionCode = run {
                try {
                    val proc = ProcessBuilder("git", "rev-list", "--count", "HEAD")
                        .redirectErrorStream(true)
                        .start()
                    val count = proc.inputStream.bufferedReader().readText().trim().toIntOrNull()
                    proc.waitFor()
                    if (count != null && count > 0) count * 1_000 + 1
                    else 1_000_001
                } catch (_: Exception) { 1_000_001 }
            }
        // Version name = short git SHA (CI: GITHUB_SHA, local: git rev-parse).
        // Included in APK filename automatically by the Android plugin.
        versionName = System.getenv("GITHUB_SHA")?.take(8)
            ?: run {
                try {
                    val proc = ProcessBuilder("git", "rev-parse", "--short=8", "HEAD")
                        .redirectErrorStream(true)
                        .start()
                    val sha = proc.inputStream.bufferedReader().readText().trim()
                    proc.waitFor()
                    if (sha.matches(Regex("[0-9a-f]{8}"))) sha else "dev"
                } catch (_: Exception) {
                    "dev"
                }
            }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
}