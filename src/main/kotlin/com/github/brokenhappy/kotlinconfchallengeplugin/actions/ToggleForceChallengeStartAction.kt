package com.github.brokenhappy.kotlinconfchallengeplugin.actions

import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeStateService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project

class ToggleForceChallengeStartAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        toggleForceChallengeStart(project)
    }

    private fun toggleForceChallengeStart(project: Project) {
        val challengeStateService = project.service<ChallengeStateService>()
        challengeStateService.update { currentState ->
            currentState.copy(forceChallengeStart = !currentState.forceChallengeStart)
        }
    }
}