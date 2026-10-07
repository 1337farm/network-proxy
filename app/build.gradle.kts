plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.0"
}

android {
    namespace = "com.forgerig.gatekeeper.proxy"
    compileSdk = 35

    defaultConfig {
        // Forge variant: a sibling install alongside the stable proxy. The
        // applicationId is the install identity, so it must differ from the
        // stable app's (com.forgerig.gatekeeper.proxy) or the two APKs
        // overwrite each other on the device. The Kotlin namespace stays
        // put: renaming it would touch every file for zero runtime effect.
        applicationId = "com.forgerig.forge.proxy"
        minSdk = 26
        targetSdk = 35
        // Run one git command against the build's own checkout, returning
        // trimmed stdout or null. The CWD is pinned because a reused Gradle
        // daemon starts wherever it happened to start, and a bare `git`
        // there fails instead of describing this checkout.
        fun git(vararg args: String): String? = try {
            val proc = ProcessBuilder(*args)
                .directory(project.rootProject.projectDir)
                .redirectErrorStream(true)
                .start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            out.ifEmpty { null }
        } catch (_: Exception) {
            null
        }

        // versionCode MUST be monotonic across builds, or `adb install -r`
        // rejects the next one as INSTALL_FAILED_VERSION_DOWNGRADE, and
        // there is no way past it: --allow-downgrade is refused for a
        // release APK.
        //
        // Commit COUNT is not monotonic here. A squash-merge collapses the
        // branch's commits into one, so the count on main lands BELOW the
        // count CI built the PR at -- GitHub checks out an ephemeral
        // test-merge ref for `pull_request`, which adds one more. main's
        // post-merge artifact then refuses to install over the PR build
        // already on the device. That is not hypothetical: it blocked the
        // sideload twice by hand, 66001 vs 67001.
        //
        // The commit's own timestamp IS monotonic. The squash commit is
        // created at merge time, strictly later than the branch commits it
        // squashes, so main's rebuild always outranks the PR build it
        // supersedes. Dividing by 10 keeps it far below the 2100000000
        // ceiling, and CI and a local build read the same checkout, so both
        // produce the same number with nothing to remember.
        versionCode = run {
            val ts = git("git", "show", "-s", "--format=%ct", "HEAD")?.toLongOrNull()
            if (ts != null && ts > 0) {
                (ts / 10).toInt()
            } else {
                // No usable timestamp (git missing, or a checkout too
                // shallow to resolve the commit). Degrade to the old count
                // scheme rather than to 1, so a degraded build is never
                // itself a downgrade of a normal one.
                val count = git("git", "rev-list", "--count", "HEAD")?.toIntOrNull()
                if (count != null && count > 0) count * 1_000 + 1 else 1
            }
        }

        // Version name = short SHA of the commit actually built, read from
        // the checkout rather than from GITHUB_SHA so a CI artifact and a
        // local build of the same commit are provably identical. Included
        // in the APK filename automatically by the Android plugin.
        versionName = run {
            val sha = git("git", "rev-parse", "--short=8", "HEAD")
            if (sha != null && sha.matches(Regex("[0-9a-f]{8}"))) sha else "dev"
        }
    }

    signingConfigs {
        // Stable demo certificate (keystore/network-proxy-demo.keystore, a
        // committed demo-only key) so every CI build installs as an update.
        // Falls back to the ephemeral debug key when the file is absent
        // (fresh clones before the keystore lands, forks).
        val demoKs = rootProject.file("keystore/network-proxy-demo.keystore")
        create("demo") {
            if (demoKs.exists()) {
                storeFile = demoKs
                storePassword = System.getenv("DEMO_KEYSTORE_PASSWORD") ?: "networkproxy"
                keyAlias = "demo"
                keyPassword = System.getenv("DEMO_KEY_PASSWORD") ?: "networkproxy"
            }
        }
        // CI fallback: ephemeral debug keystore generated before
        // assembleRelease, so the release APK is always produced even when
        // the demo keystore is absent.
        val homeDir = System.getenv("HOME") ?: System.getProperty("user.home")
        create("ciDebug") {
            storeFile = file(System.getenv("DEBUG_KEYSTORE") ?: "$homeDir/.android/debug.keystore")
            storePassword = System.getenv("DEBUG_STORE_PASSWORD") ?: "android"
            keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
            keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (rootProject.file("keystore/network-proxy-demo.keystore").exists())
                signingConfigs.getByName("demo") else signingConfigs.getByName("ciDebug")
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
    packaging {
        resources {
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/11/OSGI-INF/MANIFEST.MF",
                "META-INF/versions/17/OSGI-INF/MANIFEST.MF"
            )
        }
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
    // X.509 issuance for the opt-in HTTPS split (local CA + per-host leafs).
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the JVM test classpath (Android framework stubs
    // throw "not mocked" without it); context-layer adapters parse JSON.
    testImplementation("org.json:json:20240303")
}