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
    id("com.gradle.develocity") version "4.0.2"
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
