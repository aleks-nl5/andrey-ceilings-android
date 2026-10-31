plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
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
}

compose.desktop {
    application {
        mainClass = "com.andreyceilings.desktop.MainKt"
    }
}
