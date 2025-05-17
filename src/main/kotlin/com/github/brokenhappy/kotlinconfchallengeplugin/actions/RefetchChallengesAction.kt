package com.github.brokenhappy.kotlinconfchallengeplugin.actions

import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeDownloadCachingService
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking

class RefetchChallengesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        refetchChallenges(project)
    }

    private fun refetchChallenges(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Refetching Challenges") {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val success = runBlocking {
                    project.service<ChallengeDownloadCachingService>().hydrateFreshCaches(onError = { message ->
                        showErrorNotification(project, message)
                    })
                }

                if (!success) {
                    showErrorNotification(project, "Failed to download challenges. Please check your internet connection and try again.")
                }
            }
        })
    }

}

internal fun showErrorNotification(project: Project, string: String) {
    com.intellij.notification.Notifications.Bus.notify(
        com.intellij.notification.Notification(
            "KotlinConfChallengePlugin.Notifications",
            "Challenge Download Failed",
            string,
            NotificationType.ERROR
        ),
        project
    )
}
