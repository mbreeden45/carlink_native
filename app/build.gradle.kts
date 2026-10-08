import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Optional release signing. Provide a gitignored `signing.properties` at the repo root with
// storeFile, storePassword, keyAlias, keyPassword -- or the same values as CARLINK_STORE_FILE,
// CARLINK_STORE_PASSWORD, CARLINK_KEY_ALIAS, CARLINK_KEY_PASSWORD environment variables (CI).
val signingProps =
    Properties().apply {
        val f = rootProject.file("signing.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

fun signingValue(
    prop: String,
    env: String,
): String? = signingProps.getProperty(prop) ?: System.getenv(env)

val releaseStoreFile = signingValue("storeFile", "CARLINK_STORE_FILE")

android {
    namespace = "com.carlink"
    compileSdk = 36

    defaultConfig {
        // Override with -Pcarlink.applicationId=... or in ~/.gradle/gradle.properties.
        // NOTE: Android stores the USB "always open with" default per package name, so changing
        // this after installing in the car means one more one-time permission prompt.
        applicationId = providers.gradleProperty("carlink.applicationId").getOrElse("com.myequinox.myapp")
        minSdk = 32
        targetSdk = 36
        // Play rejects a versionCode it has already seen. CI numbers its own builds (run number + 100,
        // so the first CI build is 101); override with -Pcarlink.versionCode=N for local builds.
        versionCode =
            providers.gradleProperty("carlink.versionCode").orNull?.toIntOrNull()
                ?: System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()?.plus(100)
                ?: 100
        versionName = "1.2.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = signingValue("storePassword", "CARLINK_STORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "CARLINK_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "CARLINK_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        buildConfig = true  // Enable BuildConfig generation for debug checks
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    testOptions {
        // android.util.Log etc. return defaults in plain JVM unit tests
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // Suppress DiscouragedApi warning for scheduleAtFixedRate usage.
        // Tested alternatives (coroutines, scheduleWithFixedDelay) caused issues
        // with microphone timing - Timer.scheduleAtFixedRate works reliably.
        // See documents/revisions.txt [19], [21] for history.
        disable += "DiscouragedApi"
    }
}

dependencies {
    // Kotlin
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // AndroidX Core
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.activity:activity-compose:1.12.2")

    // DataStore for preferences persistence
    implementation("androidx.datastore:datastore-preferences:1.2.0")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // MediaSession for AAOS integration (uses MediaSessionCompat)
    implementation("androidx.media:media:1.7.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    // Real org.json for JVM unit tests (the android.jar one is a stub)
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.12.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

