package com.github.brokenhappy.kotlinconfchallengeplugin.shared

import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.datetime.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.MessageDigest
import java.util.*
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Serializable
data class Challenge(val endTime: Instant, val duration: Duration, val imageUrl: String)

@Serializable(with = Blob.BlobSerializer::class)
class Blob(private val data: ByteArray) {
    fun toByteArray(): ByteArray = data.clone()
    override fun equals(other: Any?): Boolean = this === other || other is Blob && data.contentEquals(other.data)
    override fun hashCode(): Int = data.contentHashCode()

    companion object {
        fun fromByteArray(bytes: ByteArray): Blob = Blob(bytes.clone())
        fun fromBase64(base64: String): Blob = Blob(Base64.getDecoder().decode(base64))
    }

    object BlobSerializer : KSerializer<Blob> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Blob", PrimitiveKind.STRING)
        override fun serialize(encoder: Encoder, value: Blob) {
            encoder.encodeString(Base64.getEncoder().encodeToString(value.data))
        }
        override fun deserialize(decoder: Decoder): Blob = fromBase64(decoder.decodeString())
    }
}

suspend fun downloadChallengesCsv(sheetId: String): String? =
    withContext(Dispatchers.IO) {
        HttpClient { expectSuccess = false }.use { client ->
            client
                .get("https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv")
                .takeIf { it.status.isSuccess() }
                ?.bodyAsText()
        }
    }

suspend fun parseChallenges(csv: String, onError: (String) -> Unit): List<Challenge>? {
    return csv
        .lines()
        .drop(1)
        .mapIndexed { index, line ->
            line.split(',').also {
                if (it.size < 3) {
                    onError("Error for sheet at row ${index + 2}: Expecting end time, duration, and url. Got $it.")
                    return null
                }
            }
        }
        .mapIndexed { index, (endTime, duration, imageUrl) ->
            Challenge(
                endTime = try {
                    parseEndTime(endTime)
                } catch (_: Throwable) {
                    coroutineContext.ensureActive()
                    onError("Error for sheet at end time of row ${index + 2}: Expect hh:mm (seconds are not considered). Got $endTime")
                    return null
                },
                duration = try {
                    parseDuration(duration)
                } catch (_: Throwable) {
                    coroutineContext.ensureActive()
                    onError("Error for sheet at duration of row ${index + 2}: Expect hh:mm:ss. Got $endTime")
                    return null
                },
                imageUrl = imageUrl,
            )
        }
}

fun parseDuration(duration: String): Duration =
    duration
        .split(':')
        .map { it.toInt() }
        .let { (h, m, s) ->
            check(h in 0..24)
            check(m in 0..60)
            check(s in 0..60)
            h.hours + m.minutes + s.seconds
        }

fun parseEndTime(timeString: String): Instant =
    Clock.System.now().withHoursAndMinutes(
        hours = timeString.substringBefore(':').toInt().also { check(it < 24) },
        minutes = timeString.substringAfter(':').toInt().also { check(it < 60) },
    )

suspend fun downloadImage(imageUrl: String): Blob? =
    withContext(Dispatchers.IO) {
        HttpClient { expectSuccess = false }.use { client ->
            client
                .get(imageUrl)
                .takeIf { it.status.isSuccess() }
                ?.readRawBytes()
                ?.let { Blob(it) }
        }
    }

fun Instant.withHoursAndMinutes(hours: Int, minutes: Int): Instant {
    val timeZone = TimeZone.currentSystemDefault()
    val localDateTime = this.toLocalDateTime(timeZone)
    return LocalDateTime(
        year = localDateTime.year,
        monthNumber = localDateTime.monthNumber,
        dayOfMonth = localDateTime.dayOfMonth,
        hour = hours,
        minute = minutes,
        second = 0,
        nanosecond = 0,
    ).toInstant(timeZone)
}

fun computeCsvChecksum(csv: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(csv.toByteArray())
    return hash.joinToString("") { "%02x".format(it) }
}
