package com.github.brokenhappy.kotlinconfchallengeplugin.toolWindow

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.github.brokenhappy.kotlinconfchallengeplugin.RunConfigType
import com.github.brokenhappy.kotlinconfchallengeplugin.runConfigurations
import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeDownloadCachingService
import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeStateService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted.Companion.Eagerly
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.shareIn
import kotlinx.datetime.*
import org.jetbrains.jewel.bridge.addComposeTab
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.skia.Image
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.times


class KotlinConfChallengeToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        toolWindow.addComposeTab {
            val scope = rememberCoroutineScope()
            val challenges by remember {
                project.service<ChallengeDownloadCachingService>().challenges().shareIn(scope, started = Eagerly, replay = 1)
            }.collectAsState(null)

            if (challenges == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Fetching challenges...")
                }
                return@addComposeTab
            }

            val challenge = challenges
                ?.sortedBy { it.endTime }
                ?.firstOrNull { it.endTime > Clock.System.now() }

            if (challenge == null) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("The last challenge has already passed.")
                }
            } else {
                val appState by remember { project.service<ChallengeStateService>().appState() }.collectAsState()
                fun startChallenge() {
                    project.service<ChallengeStateService>().update {
                        it.copy(currentlyRunningChallengeEndTime = challenge.endTime)
                    }
                }
                LaunchedEffect(appState.forceChallengeStart) {
                    if (appState.forceChallengeStart) {
                        startChallenge()
                    }
                }

                if (appState.currentlyRunningChallengeEndTime != challenge.endTime) {
                    val startTime = challenge.endTime - challenge.duration
                    val timeLeft by countdownTo(startTime, interval = 10.milliseconds).collectAsState(1.hours)
                    Box(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text("Next challenge starts in:")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "$timeLeft", fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.height(24.dp))

                            DefaultButton(
                                enabled = timeLeft == Duration.ZERO,
                                onClick = {
                                    startChallenge()
                                },
                                modifier = Modifier.padding(8.dp),
                            ) {
                                Text("Start challenge!")
                            }
                        }
                    }
                } else {
                    val timeLeft by countdownTo(challenge.endTime, interval = 10.milliseconds).collectAsState(1.hours)
                    LaunchedEffect(timeLeft, appState.settings.enableAutoStartJvmAndAndroidAfterChallengeCompletion) {
                        if (!appState.settings.enableAutoStartJvmAndAndroidAfterChallengeCompletion) {
                            return@LaunchedEffect
                        }

                        if (timeLeft == Duration.ZERO) {
                            project.runConfigurations(RunConfigType.JVM, RunConfigType.ANDROID)
                        }
                    }
                    val hasRunApp = appState.endTimeOfChallengeThatHasOpenedChallenge == challenge.endTime
                    val timeUntilAppRun = (timeLeft - challenge.duration / 2).coerceAtLeast(Duration.ZERO)
                    Column {
                        Row(
                            modifier = Modifier
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                if (hasRunApp) {
                                    Text(text = "Time left: ")
                                    Text(text = "$timeLeft", fontFamily = FontFamily.Monospace)
                                } else {
                                    Text(text = "Time left until app run: ")
                                    Text(text = "$timeUntilAppRun", fontFamily = FontFamily.Monospace)
                                }
                            }
                            Spacer(Modifier.width(20.dp))
                            DefaultButton(
                                enabled = !hasRunApp && timeUntilAppRun == Duration.ZERO,
                                onClick = {
                                    project.service<ChallengeStateService>().update {
                                        it.copy(endTimeOfChallengeThatHasOpenedChallenge = challenge.endTime)
                                    }
                                    project.runConfigurations(RunConfigType.JVM)
                                },
                            ) {
                                if (hasRunApp) {
                                    Text("App has been run")
                                } else if (timeUntilAppRun == Duration.ZERO) {
                                    Text("Run the App (Only once!)")
                                } else {
                                    Text("Run the App")
                                }
                            }
                        }
                        ChallengeImage(challenge.imageUrl, project)
                    }
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
        imageMutable = project.service<ChallengeDownloadCachingService>().getImage(url)?.toByteArray()
    }

    if (downloadErrorOccurred) {
        Box(
            modifier = Modifier.fillMaxSize().padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Aaaah, I'm so sorry, we failed to download the image!")
        }
        return
    }

    if (image == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("Loading image...")
        }
    } else {
        // Keep the image full-screen as required
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
