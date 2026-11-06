plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
    sourceSets.getByName("test").kotlin.srcDir("../shared/src/testFixtures/kotlin")
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    // Desktop host supplies its own actual navigation icons to shared CMP chrome.
    implementation(compose.materialIconsExtended)
    // Desktop now consumes the encrypted SQLDelight driver and its reactive repository.
    implementation("app.cash.sqldelight:runtime:2.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    testImplementation(kotlin("test"))
    // Exercise the real shared write and navigation during a paused SQLDelight transaction.
    testImplementation("app.cash.sqldelight:sqlite-driver:2.4.1")
}

compose.desktop {
    application {
        mainClass = "com.andreyceilings.desktop.MainKt"
    }
}

// A failing Compose scene test must identify the exact failed assertion on Windows.
// Keep test execution and CI gates unchanged; only improve failure diagnostics.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showCauses = true
        showStackTraces = true
    }
}
