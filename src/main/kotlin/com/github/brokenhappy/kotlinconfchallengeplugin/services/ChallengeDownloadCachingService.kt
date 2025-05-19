package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.github.brokenhappy.kotlinconfchallengeplugin.actions.showErrorNotification
import com.github.brokenhappy.kotlinconfchallengeplugin.toolWindow.KotlinConfChallengeToolWindowFactory
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import fleet.util.logging.logger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.SharingStarted.Companion.WhileSubscribed
import kotlinx.datetime.*
import kotlinx.datetime.TimeZone
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encodeToString
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.coroutines.coroutineContext
import kotlin.io.path.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@Service(Service.Level.PROJECT)
internal class ChallengeDownloadCachingService(
    private val project: Project,
    projectScope: CoroutineScope,
) {
    private val state: MutableStateFlow<DownloadCache> = MutableStateFlow(
        DownloadCache(
            imageCache = emptyMap(),
            challenges = null,
            lastPoll = Instant.DISTANT_PAST,
        ),
    )

    init {
        projectScope.launch {
            project.service<ChallengeStateService>()
                .appState()
                .map { it.settings.fileSharedBetweenRuntimeAndPlugin }
                .distinctUntilChanged()
                .map { Path(it) }
                .collectLatest { fileSharedBetweenRuntimeAndPlugin ->
                    if (!fileSharedBetweenRuntimeAndPlugin.exists()) return@collectLatest
                    try {
                        val newCache = Json.decodeFromString<DownloadCache>(
                            fileSharedBetweenRuntimeAndPlugin.readText(),
                        )
                        state.update { newCache }
                    } catch (t: Throwable) {
                        logger<KotlinConfChallengeToolWindowFactory>().error(t, "Failed to load cache from file.")
                    }
                    state
                        .drop(1)
                        .collectLatest {
                            delay(.2.seconds)
                            fileSharedBetweenRuntimeAndPlugin.writeText(Json.encodeToString(it))
                        }
                }
        }
    }

    fun challenges(): Flow<List<Challenge>?> = flow {
        val needsRefresh = state
            .value
            .also { emit(it.challenges) }
            .takeIf { it.lastPoll + 1.minutes > Clock.System.now() }
            ?.challenges == null

        coroutineScope {
            if (needsRefresh) launch {
                while (!hydrateFreshCaches(onError = { showErrorNotification(project, it) })) delay(10.seconds)
            }
            state.collect { emit(it.challenges) }
        }
    }

    /** @return true if it succeeded */
    suspend fun hydrateFreshCaches(onError: (String) -> Unit): Boolean = downloadAndCacheChallenges(onError) != null

    suspend fun getImage(imageUrl: String): Blob? = state
        .value
        .imageCache[imageUrl]
        ?: downloadImage(imageUrl).also { image ->
            if (image != null) {
                state.update { it.copy(imageCache = it.imageCache + (imageUrl to image)) }
            }
        }
        ?: state.value.imageCache[imageUrl]

    private suspend fun downloadAndCacheChallenges(onError: (String) -> Unit): List<Challenge>? =
        downloadChallenges(onError).also { dbState ->
            if (dbState != null) {
                state.update {
                    it.copy(
                        challengesCache = dbState,
                        lastPoll = Clock.System.now(),
                    )
                }
                hydrateImages(dbState.map { it.imageUrl })
            }
        }

    private suspend fun downloadChallenges(onError: (String) -> Unit): List<Challenge>? =
        downloadChallenges(project.service<ChallengeStateService>().appState().value.settings.googleSheetId, onError)

    private suspend fun hydrateImages(map: Iterable<String>) {
        coroutineScope {
            for (image in map) {
                launch { getImage(image) }
            }
        }
    }
}


@Serializable
internal data class Challenge(val endTime: Instant, val duration: Duration, val imageUrl: String)

private suspend fun downloadChallenges(sheetId: String, onError: (String) -> Unit): List<Challenge>? =
    withContext(Dispatchers.IO) {
        HttpClient { expectSuccess = false }.use { client ->
            client
                .get("https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv")
                .takeIf { it.status.isSuccess() }
                .also { if (it == null) logger<KotlinConfChallengeToolWindowFactory>().error("Failed to download CSV") }
                ?.bodyAsText()
                ?.let { parseChallenges(it, onError) }
        }
    }

private suspend fun parseChallenges(csv: String, onError: (String) -> Unit): List<Challenge>? {
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

private fun parseDuration(duration: String): Duration =
    duration
        .split(':')
        .map { it.toInt() }
        .let { (h, m, s) ->
            check(h in 0..24)
            check(m in 0..60)
            check(s in 0..60)
            h.hours + m.minutes + s.seconds
        }

private fun parseEndTime(timeString: String): Instant =
    Clock.System.now().withHoursAndMinutes(
        hours = timeString.substringBefore(':').toInt().also { check(it <= 24) },
        minutes = timeString.substringAfter(':').toInt().also { check(it <= 60) },
    )

private suspend fun downloadImage(imageUrl: String): Blob? =
    withContext(Dispatchers.IO) {
        HttpClient { expectSuccess = false }.use { client ->
            client
                .get(imageUrl)
                .takeIf { it.status.isSuccess() }
                ?.readRawBytes()
                ?.let { Blob(it) }
        }
    }

private fun Instant.withHoursAndMinutes(hours: Int, minutes: Int): Instant {
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

@Serializable
private class DownloadCache(
    val imageCache: Map<String, Blob>,
    val challenges: List<Challenge>?,
    val lastPoll: Instant,
)

private fun DownloadCache.copy(
    imageCache: Map<String, Blob> = this.imageCache,
    challengesCache: List<Challenge>? = this.challenges,
    lastPoll: Instant = this.lastPoll,
) = DownloadCache(imageCache, challengesCache, lastPoll)

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
