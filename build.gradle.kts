plugins {
    // Kotlin 2.4.21 is the current stable KMP release. Keep Gradle/AGP within
    // the officially listed compatibility range while the shared module is introduced.
    id("com.android.application") version "9.3.1" apply false
    id("com.android.kotlin.multiplatform.library") version "9.3.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.4.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
    id("org.jetbrains.compose") version "1.12.2" apply false
    id("app.cash.sqldelight") version "2.4.0" apply false
}
