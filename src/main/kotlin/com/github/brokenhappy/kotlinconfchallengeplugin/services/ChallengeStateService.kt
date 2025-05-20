package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.github.brokenhappy.kotlinconfchallengeplugin.ChallengeSettings
import com.intellij.openapi.components.SerializablePersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Service(Service.Level.PROJECT)
@State(name = "KotlinConfChallengeState5")
internal class ChallengeStateService(private val project: Project) : SerializablePersistentStateComponent<ChallengeState>(
    ChallengeState(
        settings = ChallengeSettings(
            googleSheetId = "",
            fileSharedBetweenRuntimeAndPlugin = "${System.getProperty("user.home")}/Documents/filesForKotlinConfChallenge25/downloadCache.json",
            enableAutoStartJvmAndAndroidAfterChallengeCompletion = false,
        ),
        forceChallengeStart = false,
        currentlyRunningChallengeEndTime = Instant.DISTANT_PAST,
        endTimeOfChallengeThatHasOpenedChallenge = Instant.DISTANT_PAST,
    ),
) {
    private val appState = MutableStateFlow(state)

    override fun loadState(state: ChallengeState) {
        super.loadState(state)
        appState.value = state
    }

    fun update(updateFunction: (currentState: ChallengeState) -> ChallengeState) {
        appState.value = updateState(updateFunction)
    }

    fun appState(): StateFlow<ChallengeState> = appState.asStateFlow()
}

@Serializable
internal data class ChallengeState(
    // DON'T MUTATE THIS, JUST A NECESSITY OF THE PERSISTENCE INFRA
    @JvmField var settings: ChallengeSettings,
    @JvmField var forceChallengeStart: Boolean,
    @JvmField var currentlyRunningChallengeEndTime: Instant,
    @JvmField var endTimeOfChallengeThatHasOpenedChallenge: Instant,
)
