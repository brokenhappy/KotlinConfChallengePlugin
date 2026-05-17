plugins {
    alias(libs.plugins.kotlin)
    kotlin("plugin.serialization") version libs.versions.kotlin
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly(libs.kotlinx.serialization.json)
    compileOnly(libs.kotlinx.coroutines.core)
    compileOnly(libs.ktor.client.core)
    compileOnly(libs.ktor.client.cio)
    compileOnly(libs.kotlinx.datetime)
}
