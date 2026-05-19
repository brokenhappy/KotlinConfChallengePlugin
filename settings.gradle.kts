pluginManagement {
    repositories {
        maven("https://packages.jetbrains.team/maven/p/kpm/public/")
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
    id("com.gradle.develocity") version "4.4.1"
}

develocity {
    accessKey = settings.ext["gradleEnterpriseKey"]?.toString()
    server = "https://ge.labs.jb.gg/"
    buildScan {
        publishing {
            onlyIf { System.getenv("CI") == "true" }
        }
    }
}

rootProject.name = "KotlinConfChallengePlugin"

include("shared", "main-control", "vote-server")
