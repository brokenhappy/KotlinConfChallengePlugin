package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol

import androidx.compose.runtime.mutableStateOf
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.*

@Serializable
data class PersistedConfig(
    val sheetId: String = "",
    val encryptionKeyBase64: String = "",
    val voteServerUrl: String = "https://voting-app.labs.jb.gg",
    val voteAdminPassword: String = "secret password",
)

class MainControlState {
    val sheetId = mutableStateOf("")
    val encryptionKeyBase64 = mutableStateOf("")
    val voteServerUrl = mutableStateOf("https://voting-app.labs.jb.gg")
    val voteAdminPassword = mutableStateOf("secret password")

    val redConnected = mutableStateOf(false)
    val blueConnected = mutableStateOf(false)

    val currentChecksum = mutableStateOf<String?>(null)
    val lastSheetUpdateTime = mutableStateOf<Instant?>(null)

    val redLastCheckTime = mutableStateOf<Instant?>(null)
    val blueLastCheckTime = mutableStateOf<Instant?>(null)

    private val configPath: Path = Path(
        System.getProperty("user.home"), ".kotlinconf-challenge-control", "config.json"
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun load() {
        try {
            if (configPath.exists()) {
                val config = json.decodeFromString<PersistedConfig>(configPath.readText())
                sheetId.value = config.sheetId
                encryptionKeyBase64.value = config.encryptionKeyBase64
                voteServerUrl.value = config.voteServerUrl
                voteAdminPassword.value = config.voteAdminPassword
            }
        } catch (_: Exception) {}
    }

    fun save() {
        try {
            configPath.parent.createDirectories()
            val config = PersistedConfig(
                sheetId = sheetId.value,
                encryptionKeyBase64 = encryptionKeyBase64.value,
                voteServerUrl = voteServerUrl.value,
                voteAdminPassword = voteAdminPassword.value,
            )
            configPath.writeText(json.encodeToString(config))
        } catch (_: Exception) {}
    }
}
