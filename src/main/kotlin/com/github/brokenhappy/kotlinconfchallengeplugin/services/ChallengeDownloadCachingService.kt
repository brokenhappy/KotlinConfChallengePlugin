package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.github.brokenhappy.kotlinconfchallengeplugin.actions.showErrorNotification
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.Blob
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.Challenge
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.parseChallenges
import com.github.brokenhappy.kotlinconfchallengeplugin.actions.reportToIdeErrorDialog
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.io.path.Path
import kotlin.io.path.absolutePathString
import kotlin.io.path.createFile
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
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
            challengesCache = null,
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
                    if (fileSharedBetweenRuntimeAndPlugin.absolutePathString().isEmpty()) return@collectLatest
                    fileSharedBetweenRuntimeAndPlugin.parent.toFile().mkdirs()
                    if (!fileSharedBetweenRuntimeAndPlugin.exists()) {
                        fileSharedBetweenRuntimeAndPlugin.createFile()
                    }
                    try {
                        val newCache = Json.decodeFromString<DownloadCache>(
                            fileSharedBetweenRuntimeAndPlugin.readText(),
                        )
                        state.update { newCache }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        reportToIdeErrorDialog("Failed to load cache from file", t)
                    }
                    coroutineScope {
                        launch {
                            while (isActive) {
                                delay(2.seconds)
                                try {
                                    val text = fileSharedBetweenRuntimeAndPlugin.readText()
                                    if (text.isBlank()) continue
                                    val newCache = Json.decodeFromString<DownloadCache>(text)
                                    state.update { current -> if (current != newCache) newCache else current }
                                } catch (_: CancellationException) {
                                    throw CancellationException()
                                } catch (_: Throwable) { /* file may be mid-write */ }
                            }
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
    }

    fun challenges(): Flow<List<Challenge>?> = flow {
        val needsRefresh = state
            .value
            .also { emit(it.challengesCache) }
            .takeIf { it.lastPoll + 1.minutes > Clock.System.now() }
            ?.challengesCache == null

        coroutineScope {
            if (needsRefresh) launch {
                while (!hydrateFreshCaches(onError = { showErrorNotification(project, it) })) delay(10.seconds)
            }
            state.collect { emit(it.challengesCache) }
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

    private suspend fun downloadChallenges(onError: (String) -> Unit): List<Challenge>? {
        val sheetId = project.service<ChallengeStateService>().appState().value.settings.googleSheetId
        val csv = try {
            downloadChallengesCsv(sheetId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            onError("Failed to download challenges: ${e.message}")
            reportToIdeErrorDialog("Failed to download CSV", e)
            return null
        }
        if (csv == null) {
            onError("Failed to download CSV — server returned an error. Check the Google Sheet ID in settings.")
            return null
        }
        return parseChallenges(csv, onError)
    }

    private suspend fun hydrateImages(map: Iterable<String>) {
        coroutineScope {
            for (image in map) {
                launch { getImage(image) }
            }
        }
    }
}

@Serializable
private data class DownloadCache(
    val imageCache: Map<String, Blob>,
    val challengesCache: List<Challenge>?,
    val lastPoll: Instant,
)
