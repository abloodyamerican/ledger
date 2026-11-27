plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.max.ledger"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.max.ledger"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // CI builds sign with the PC's debug key (from the DEBUG_KEYSTORE_B64 secret) so phone
    // installs update the existing app. Local builds don't set this and keep Android Studio's key.
    signingConfigs {
        getByName("debug") {
            System.getenv("LEDGER_DEBUG_KEYSTORE")?.takeIf { it.isNotBlank() }?.let {
                storeFile = file(it)
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        // the Claude SDK uses java.time, which older Androids (7 and 7.1) lack
        isCoreLibraryDesugaringEnabled = true
    }
    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                "META-INF/*.kotlin_module", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // on-phone text reading for paystub / receipt scans (works offline, nothing uploaded)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Analyze > Ask Claude (uses your own Anthropic API key)
    implementation("com.anthropic:anthropic-java:3.14.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}