import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization") version libs.versions.kotlin
}

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(21)
}

val compileControlBle by tasks.registering(Exec::class) {
    val swiftSrc = rootProject.file("native/macos/ControlBlePeripheral.swift")
    val arch = System.getProperty("os.arch")
    val baseDir = layout.buildDirectory.dir("generated/controlble")
    val outputDir = layout.buildDirectory.dir("generated/controlble/darwin-$arch")
    inputs.file(swiftSrc)
    outputs.dir(baseDir)
    onlyIf { System.getProperty("os.name").contains("Mac", ignoreCase = true) }
    doFirst { outputDir.get().asFile.mkdirs() }
    commandLine(
        "swiftc", "-emit-library",
        "-o", outputDir.get().asFile.resolve("libcontrolble.dylib").absolutePath,
        swiftSrc.absolutePath,
        "-framework", "CoreBluetooth",
        "-framework", "Foundation",
    )
}

sourceSets.main {
    resources.srcDirs(compileControlBle.map { it.outputs.files })
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.jna)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}

compose.desktop {
    application {
        mainClass = "com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg)
            packageName = "KotlinConfChallengeControl"
            packageVersion = "1.0.0"
            macOS {
                bundleID = "com.github.brokenhappy.kotlinconfchallengecontrol"
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSBluetoothAlwaysUsageDescription</key>
                        <string>KotlinConf Challenge Control needs Bluetooth to communicate with challenge clients.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
