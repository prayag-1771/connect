import java.util.Properties

/**
 * Release signing details, kept out of the repository.
 *
 * Without this file the release build still configures, it just goes unsigned
 * — so a fresh clone is not broken, it simply cannot ship.
 */
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.google.services)
}

android {
    namespace = "com.obsidian.connect"
    compileSdk = 35

    defaultConfig {
        /*
         * Phones only.
         *
         * WebRTC ships native code for four architectures and two of them,
         * x86 and x86_64, exist for emulators - no handset has ever used
         * them. They were twenty-four megabytes of an APK that gets sent over
         * WhatsApp.
         *
         * armeabi-v7a stays. It is another six megabytes and neither of our
         * phones needs it, but dropping it would refuse to install on any
         * 32-bit device, and that is a worse failure than a larger download.
         *
         * The cost is that this APK will not run on an x86 emulator, which
         * matters to development and to nobody else.
         */
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }

        applicationId = "com.obsidian.connect"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Read from a gitignored properties file. Absent, GIF search is simply
        // switched off rather than the build breaking — a fresh clone still
        // compiles and runs.
        buildConfigField(
            "String",
            "GIPHY_KEY",
            "\"${keystoreProperties.getProperty("giphyKey") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "YOUTUBE_KEY",
            "\"${keystoreProperties.getProperty("youtubeKey") ?: ""}\"",
        )
        buildConfigField(
            "String",
            "GEMINI_KEY",
            "\"${keystoreProperties.getProperty("geminiKey") ?: ""}\"",
        )
    }

    signingConfigs {
        create("release") {
            val store = keystoreProperties.getProperty("storeFile")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")

            // Minification is off deliberately. R8 renames and strips, and this
            // app leans on reflection in places R8 cannot see: Firestore maps
            // documents onto model classes by field name, and the widgets are
            // reached only through the manifest. Keep rules cover the cases I
            // know about, but the failure mode is a silent one — a widget that
            // does not draw, a document that deserialises to nulls — and none
            // of that has been tested. Not worth it to save a few megabytes on
            // an app being handed to one person.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            // No applicationIdSuffix on purpose. A suffix changes the package
            // name, and the google-services plugin then demands a second app
            // registered in the Firebase console or the build fails outright.
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Home screen widget
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    // Background image download when a push arrives
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // Device biometrics with PIN/pattern/password fallback. Deliberately not a
    // PIN of our own: storing and verifying one correctly is a security problem
    // with no upside when the platform already solves it.
    implementation(libs.androidx.biometric)

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.androidx.exifinterface)
    // Supplies ListenableFuture.await(); CameraX 1.4 has no suspend accessor.
    implementation(libs.androidx.concurrent.futures.ktx)

    // camera-core exposes ListenableFuture across its public API, yet nothing
    // puts that interface on the *compile* classpath. Guava arrives only as a
    // runtime dependency, and the standalone listenablefuture artifact is
    // always upgraded to guava's "9999.0-empty-to-avoid-conflict" placeholder,
    // which is an intentionally empty jar containing no classes.
    //
    // Asking for listenablefuture:1.0 does not help even as compileOnly, since
    // it lands in the same conflict resolution and loses to 9999.0 again. Guava
    // proper is not subject to that swap, so it is what goes here.
    //
    // compileOnly keeps the runtime graph untouched: guava still supplies the
    // real class on device, nothing extra is packaged, and there is no
    // duplicate class. Declaring it as implementation fails the build.
    compileOnly(libs.guava)

    implementation(libs.androidx.webkit)
    implementation(libs.webrtc)

    implementation(libs.coil.compose)
    implementation(libs.coil.gif)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
}
