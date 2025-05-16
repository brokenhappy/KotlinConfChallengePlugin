package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.github.brokenhappy.kotlinconfchallengeplugin.toolWindow.MyToolWindowFactory
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import fleet.util.logging.logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.isSuccess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.minutes

@Service(Service.Level.PROJECT)
@State(name = "KotlinConfChallengeStateCache")
@Storage(StoragePathMacros.CACHE_FILE)
internal class ChallengeDownloadCachingService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : SerializablePersistentStateComponent<DownloadCacheJson>(
    DownloadCacheJson(
        Json.encodeToString(
            DownloadCache(
                imageCache = emptyMap(),
                dbCache = null,
                lastPoll = Instant.DISTANT_PAST,
            ),
        ),
    ),
) {

    suspend fun getDatabase(): List<Challenge>? = state
        .asRealState()
        .takeIf { it.lastPoll + 1.minutes <= Clock.System.now() }
        ?.dbCache
        ?: downloadChallenges(project.service<ChallengeStateService>().appState().value.settings.googleSheetId)
            .also { dbState ->
                if (dbState != null) {
                    coroutineScope.launch {
                        updateState {
                            it.asRealState().copy(
                                dbCache = dbState,
                                lastPoll = Clock.System.now(),
                            ).jsonWrapped()
                        }
                        hydrateImages(dbState.map { it.imageUrl })
                    }
                }
            }
        ?: state.asRealState().dbCache

    suspend fun getImage(imageUrl: String): ByteArray? = state
        .asRealState()
        .imageCache[imageUrl]
        ?: downloadImage(imageUrl).also { image ->
            if (image != null) {
                updateState { oldStateJson ->
                    oldStateJson
                        .asRealState()
                        .let { it.copy(imageCache = it.imageCache + (imageUrl to image)) }
                        .jsonWrapped()
                }
            }
        }
        ?: this.state.asRealState().imageCache[imageUrl]

    private suspend fun hydrateImages(map: Iterable<String>) {
        coroutineScope {
            for (image in map) {
                launch { getImage(image) }
            }
        }
    }
}

@Serializable
internal data class DownloadCacheJson(
    // DON'T MUTATE THIS, JUST A NECESSITY OF THE PERSISTENCE INFRA
    @JvmField var json: String
)

internal fun DownloadCacheJson.asRealState(): DownloadCache = Json.decodeFromString(this.json)
internal fun DownloadCache.jsonWrapped(): DownloadCacheJson = DownloadCacheJson(Json.encodeToString(this))

@Serializable
internal class DownloadCache(
    val imageCache: Map<String, ByteArray>,
    val dbCache: List<Challenge>?,
    val lastPoll: Instant,
)

internal fun DownloadCache.copy(
    imageCache: Map<String, ByteArray> = this.imageCache,
    dbCache: List<Challenge>? = this.dbCache,
    lastPoll: Instant = this.lastPoll,
) = DownloadCache(imageCache, dbCache, lastPoll)


@Serializable
internal data class Challenge(val endTime: Instant, val imageUrl: String)

private suspend fun downloadChallenges(sheetId: String): List<Challenge>? =
    withContext(Dispatchers.IO) {
        HttpClient { expectSuccess = false }.use { client ->
            client
                .get("https://docs.google.com/spreadsheets/d/$sheetId/export?format=csv")
                .takeIf { it.status.isSuccess() }
                .also { if (it == null) logger<MyToolWindowFactory>().error("Failed to download CSV") }
                ?.bodyAsText()
                ?.lines()
                ?.map { it.split(',') }
                ?.map { (time, imageUrl) ->
                    val hours = time.substringBefore(':').toInt()
                    val minutes = time.substringAfter(':').toInt()
                    Challenge(Clock.System.now().withHoursAndMinutes(hours, minutes), imageUrl)
                }
        }
    }

private suspend fun downloadImage(imageUrl: String): ByteArray? =
    withContext(Dispatchers.IO) {
        HttpClient { expectSuccess = false }.use { client ->
            client
                .get(imageUrl)
                .takeIf { it.status.isSuccess() }
                ?.readRawBytes()
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
        second = localDateTime.second,
        nanosecond = localDateTime.nanosecond
    ).toInstant(timeZone)
}
