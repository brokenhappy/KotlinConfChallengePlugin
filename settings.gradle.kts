pluginManagement {
    repositories {
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
        google()
        gradlePluginPortal()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "KotlinConfChallengePlugin"
