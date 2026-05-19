plugins {
    alias(libs.plugins.kotlin)
    kotlin("plugin.serialization") version libs.versions.kotlin
    application
}

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.html.builder)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.github.brokenhappy.kotlinconfchallengeplugin.voteserver.MainKt")
}
