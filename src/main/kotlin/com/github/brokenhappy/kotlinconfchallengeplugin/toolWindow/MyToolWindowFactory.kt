package com.github.brokenhappy.kotlinconfchallengeplugin.toolWindow

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeStateService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import fleet.util.logging.logger
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.isSuccess
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.*
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Image
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.times


class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab {
            var challengeMutable: Challenge? by remember { mutableStateOf(null) }
            val appState by project.service<ChallengeStateService>().appState().collectAsState()
            val googleSheetId = appState.settings.googleSheetId
            val challenge = challengeMutable
            LaunchedEffect(googleSheetId) {
                challengeMutable = downloadCurrentChallenge(googleSheetId)
            }

            if (challenge == null) {
                Text("Fetching next challenge:")
            } else {
                var hasStartedChallenge by remember { mutableStateOf(false) }
                if (!hasStartedChallenge) {
                    val startTime = challenge.endTime - 10.minutes
                    val timeLeft by countdownTo(startTime, interval = 10.milliseconds).collectAsState(Duration.ZERO)
                    Column {
                        Text("Next challenge starts in: $timeLeft")

                        DefaultButton(enabled = timeLeft == Duration.ZERO, onClick = { hasStartedChallenge = true }) {
                            Text("Start challenge!")
                        }
                    }
                } else {
                    ChallengeImage(challenge.imageUrl)
                }
            }
        }
    }

    override fun shouldBeAvailable(project: Project) = true
}

@Composable
private fun ChallengeImage(url: String) {
    var imageMutable by remember { mutableStateOf<ByteArray?>(null) }
    val image = imageMutable
    var downloadErrorOccurred by remember { mutableStateOf(false) }

    LaunchedEffect(url, downloadErrorOccurred) {
        if (downloadErrorOccurred) return@LaunchedEffect
        HttpClient { expectSuccess = false }.use { client ->
            imageMutable = client
                .get(url)
                .takeIf { it.status.isSuccess() }
                .also {
                    if (it == null) {
                        downloadErrorOccurred = true
                        logger<MyToolWindowFactory>().error("Failed to download image")
                    }
                }
                ?.readRawBytes()
        }
    }

    if (downloadErrorOccurred) {
        Text("Aaaah, I'm so sorry, we failed to download the image!")
        return
    }

    if (image == null) {
        Text("Loading image...")
    } else {
        Image(
            bitmap = Image.makeFromEncoded(image).toComposeImageBitmap(),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit,

        )
    }
}

private data class Challenge(val endTime: Instant, val imageUrl: String)

private suspend fun downloadCurrentChallenge(sheetId: String): Challenge? =
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
            ?.sortedBy { it.endTime }
            ?.firstOrNull { it.endTime > Clock.System.now() }
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

private fun countdownTo(instant: Instant, interval: Duration): Flow<Duration> = flow {
    emit(instant - Clock.System.now())
    while (true) {
        val now = Clock.System.now()
        val distance = instant - now
        if (distance <= Duration.ZERO) {
            emit(Duration.ZERO)
            break
        }
        delay(((distance / interval) % 1.0) * interval)
        emit(distance)
    }
}