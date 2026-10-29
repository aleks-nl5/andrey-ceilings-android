plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.sqldelight")
}

kotlin {
    androidLibrary {
        namespace = "com.andreyceilings.shared"
        compileSdk = 37
        minSdk = 30
    }

    jvm()

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { target ->
        target.binaries.framework {
            baseName = "AndreyCeilingsShared"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation("app.cash.sqldelight:runtime:2.4.1")
        }

        androidMain.dependencies {
            implementation("app.cash.sqldelight:android-driver:2.4.1")
            // Reuse the app's established SQLCipher implementation for the Android spike.
            implementation("net.zetetic:sqlcipher-android:4.19.1")
        }

        jvmMain.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
            implementation("io.github.s0d3s:sqlcipher-multiplatform-jdbc-core:0.2.13")
            runtimeOnly("io.github.s0d3s:sqlcipher-multiplatform-jdbc-windows-x64:0.2.13")
        }

        jvmTest.dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        iosTest.dependencies {
            implementation(kotlin("test"))
        }

        iosMain.dependencies {
            // Isolated iOS SQLCipher proof; not used by production repositories.
            implementation("io.github.yet300:sqlcipher-driver:0.1.0")
        }
    }
}

sqldelight {
    // Disable SQLDelight's system SQLite linker for Apple targets; SQLCipher supplies sqlite3_*.
    linkSqlite.set(false)
    databases {
        create("PrototypeDatabase") {
            packageName.set("com.andreyceilings.shared.db")
            // INSERT ... RETURNING requires SQLite >= 3.35 on every supported driver.
            dialect("app.cash.sqldelight:sqlite-3-35-dialect:2.4.1")
        }
    }
}
