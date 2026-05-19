package com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val bleJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
sealed interface Command {
    @Serializable
    data object RefreshSheet : Command

    @Serializable
    data object RestartRunConfig : Command

    @Serializable
    data class Ping(val timestamp: Long) : Command

    @Serializable
    data class SetClipboard(val content: String) : Command

    @Serializable
    data class SetClipboardBinary(
        val contentBase64: String,
        val type: String,
        val fileName: String? = null,
    ) : Command

    @Serializable
    data object GetClipboard : Command
}

@Serializable
sealed interface StatusReport {
    @Serializable
    data class SheetState(val checksum: String, val checkedAt: Long) : StatusReport

    @Serializable
    data class Pong(val pingTimestamp: Long, val pongTimestamp: Long) : StatusReport

    @Serializable
    data class Heartbeat(val timestamp: Long) : StatusReport

    @Serializable
    data class ClipboardContent(val content: String) : StatusReport
}

fun Command.encode(): ByteArray =
    bleJson.encodeToString(Command.serializer(), this).encodeToByteArray()

fun decodeCommand(bytes: ByteArray): Command =
    bleJson.decodeFromString(Command.serializer(), bytes.decodeToString())

fun StatusReport.encode(): ByteArray =
    bleJson.encodeToString(StatusReport.serializer(), this).encodeToByteArray()

fun decodeStatusReport(bytes: ByteArray): StatusReport =
    bleJson.decodeFromString(StatusReport.serializer(), bytes.decodeToString())
