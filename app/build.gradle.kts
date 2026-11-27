import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    val productionReleaseSmoke = providers.gradleProperty("productionReleaseSmoke").orNull == "true"
    // FAST PR builds must not receive the persistent production signing key.
    val quickUnsignedRelease = providers.gradleProperty("quickUnsignedRelease").orNull == "true"
    testBuildType = if(productionReleaseSmoke) "release" else "debug"
    namespace = "com.andreyceilings.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.andreyceilings.app"
        minSdk = 30
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // GitHub run numbers are per workflow. Both quick PR and post-merge
        // release builds need globally increasing codes for safe in-place updates.
        val actionsRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        val ciVersionCode = System.getenv("GITHUB_RUN_ID")?.let { id ->
            val candidate = id.toLongOrNull()?.div(1000L)
            require(candidate != null && candidate in 600_000L..2_099_999_999L) {
                "Invalid GitHub Actions run id for Android versionCode"
            }
            candidate.toInt()
        }
        versionCode = ciVersionCode ?: (600000 + actionsRunNumber)
        versionName = "0.6.3" + when {
            ciVersionCode != null -> "-ci$ciVersionCode"
            actionsRunNumber > 0 -> "-$actionsRunNumber"
            else -> ""
        }
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
            signingConfig = if (quickUnsignedRelease) null else signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // Reuse the P1 persistence/migration contract across real platform drivers.
    sourceSets.getByName("androidTest").kotlin.directories.add("../shared/src/commonTest/kotlin/com/andreyceilings/shared/db")
    sourceSets.getByName("androidTest").kotlin.directories.add("../shared/src/testFixtures/kotlin")
    if(productionReleaseSmoke) {
        // Release tests contain no debug-only crash provider, and exercise the actual signed app.
        sourceSets.getByName("androidTest").java.setSrcDirs(emptyList<String>())
        sourceSets.getByName("androidTest").kotlin.setSrcDirs(listOf("src/productionTest/kotlin",
            "../shared/src/commonTest/kotlin/com/andreyceilings/shared/db", "../shared/src/testFixtures/kotlin"))
    } else sourceSets.getByName("androidTest").kotlin.directories.add("src/productionTest/kotlin")

    // AGP 9+ built-in Kotlin uses the Java compile target by default.
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
}

// The app instrumentation proof exercises the actual internal shared writer and
// PDF layout. Give only the debug instrumentation compiler friend access to
// the Android API classes jar; do not widen production visibility or suppress
// Kotlin visibility diagnostics.
val sharedInstrumentationFriends by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    isTransitive = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_API))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.ANDROID))
        attribute(Attribute.of("artifactType", String::class.java), "android-classes-jar")
    }
}

tasks.withType<KotlinJvmCompile>().configureEach {
    if (name == "compileDebugAndroidTestKotlin") {
        friendPaths.from(sharedInstrumentationFriends.incoming.files)
    }
}

dependencies {
    add(sharedInstrumentationFriends.name, project(":shared"))
    // Shared KMP calculator and encrypted SQLDelight repositories.
    implementation(project(":shared"))
    implementation("app.cash.sqldelight:runtime:2.4.1")
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
    implementation("net.zetetic:sqlcipher-android:4.19.1")
    implementation("androidx.sqlite:sqlite:2.7.1")
    implementation("androidx.datastore:datastore-preferences:1.3.0")
    // SAF folder access and periodic background work for automatic backups.
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(project(":shared"))
    androidTestImplementation("org.jetbrains.kotlin:kotlin-test:2.4.21")
    androidTestImplementation("app.cash.sqldelight:android-driver:2.4.1")
    androidTestImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Explicit test dependency: shared keeps its date implementation internal.
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}

