plugins {
    kotlin("jvm") version "2.3.20"
    application
}

dependencies {
    implementation("io.github.s0d3s:sqlcipher-multiplatform:0.2.13")
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("com.andreyceilings.poc.MainKt")
}

tasks.register<JavaExec>("verifySqlCipher") {
    group = "verification"
    description = "Runs an isolated SQLCipher Windows/JVM encryption proof."
    dependsOn(tasks.named("classes"))
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.andreyceilings.poc.MainKt")
}
