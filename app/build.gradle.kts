plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.andreyceilings.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andreyceilings.app"
        minSdk = 30
        targetSdk = 37
        // GitHub Actions run number is appended to the visible version for test builds.
        val actionsRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 600000 + actionsRunNumber
        versionName = "0.6.2" + if (actionsRunNumber > 0) "-$actionsRunNumber" else ""
    }

    signingConfigs {
        create("release") {
            val signingFile = System.getenv("ANDROID_KEYSTORE_FILE")
            if (!signingFile.isNullOrBlank()) {
                storeFile = file(signingFile)
                storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDROID_KEY_ALIAS")
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // AGP 9+ built-in Kotlin uses the Java compile target by default.
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

dependencies {
    // Required Go mobile library: all calculations run in Go; no Kotlin fallback exists.
    implementation(files("libs/ceilings-core.aar"))
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1") // WindowCompat.enableEdgeToEdge
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    // SAF folder access and periodic background work for automatic backups.
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
