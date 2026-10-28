plugins {
    kotlin("jvm") version "2.4.21"
    id("app.cash.sqldelight") version "2.4.1"
    application
}

dependencies {
    implementation("app.cash.sqldelight:runtime:2.4.1")
    implementation("app.cash.sqldelight:sqlite-driver:2.4.1")
    implementation("io.github.s0d3s:sqlcipher-multiplatform-jdbc-core:0.2.13")
    runtimeOnly("io.github.s0d3s:sqlcipher-multiplatform-jdbc-windows-x64:0.2.13")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.andreyceilings.poc.MainKt")
}

tasks.register<JavaExec>("verifySqlCipherSqlDelight") {
    group = "verification"
    description = "Verifies SQLDelight 2.4.1 JDBC integration with the isolated SQLCipher JDBC driver."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.andreyceilings.poc.MainKt")
}
