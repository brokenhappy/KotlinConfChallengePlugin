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
import com.github.brokenhappy.kotlinconfchallengeplugin.services.Challenge
import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeDownloadCachingService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.*
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Image
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.times


class MyToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab {
            var challenges: List<Challenge>? by remember { mutableStateOf(null) }
            LaunchedEffect(Unit) {
                challenges = project.service<ChallengeDownloadCachingService>().getDatabase()
            }
            if (challenges == null) {
                Text("Fetching challenges...")
                return@addComposeTab
            }

            val challenge = challenges
                ?.sortedBy { it.endTime }
                ?.firstOrNull { it.endTime > Clock.System.now() }

            if (challenge == null) {
                Text("The last challenge has already passed.")
            } else {
                var hasStartedChallenge by remember { mutableStateOf(false) }
                if (!hasStartedChallenge) {
                    val startTime = challenge.endTime - 10.minutes
                    val timeLeft by countdownTo(startTime, interval = 10.milliseconds).collectAsState(1.hours)
                    Column {
                        Text("Next challenge starts in: $timeLeft")

                        DefaultButton(enabled = timeLeft == Duration.ZERO, onClick = { hasStartedChallenge = true }) {
                            Text("Start challenge!")
                        }
                    }
                } else {
                    ChallengeImage(challenge.imageUrl, project)
                }
            }
        }
    }

    override fun shouldBeAvailable(project: Project) = true
}

@Composable
private fun ChallengeImage(url: String, project: Project) {
    var imageMutable by remember { mutableStateOf<ByteArray?>(null) }
    val image = imageMutable
    var downloadErrorOccurred by remember { mutableStateOf(false) }

    LaunchedEffect(url, downloadErrorOccurred) {
        if (downloadErrorOccurred) return@LaunchedEffect
        imageMutable = project.service<ChallengeDownloadCachingService>().getImage(url)
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