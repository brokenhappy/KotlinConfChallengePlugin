package com.github.brokenhappy.kotlinconfchallengeplugin.actions

import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeStateService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import kotlinx.coroutines.runBlocking

class ToggleForceChallengeStartAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val project = e.project ?: return
        val weNowAreForcingChallengeStart = project.service<ChallengeStateService>().appState().value.forceChallengeStart
        e.presentation.text = when (weNowAreForcingChallengeStart) {
            true -> "Stop Forcing Kotlin Challenge Start"
            false -> "Start Forcing Kotlin Challenge Start"
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        project.service<ChallengeStateService>().update { currentState ->
            currentState.copy(forceChallengeStart = !currentState.forceChallengeStart)
        }
    }
}