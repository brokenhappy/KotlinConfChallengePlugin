package com.github.brokenhappy.kotlinconfchallengeplugin

import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

internal enum class RunConfigType {
    JVM,
    ANDROID,
    JS,
}

internal fun Project.runConfigurations(vararg types: RunConfigType) {
    val jvmConfigurations = RunManager.getInstance(this).allSettings.filter { settings ->
        settings.configuration.name in types.map { it.configName() }
    }

    if (jvmConfigurations.isEmpty()) {
        showNotification(
            this,
            "No Run Configuration Found",
            "No run configuration found in this Kotlin Multiplatform project.",
            NotificationType.WARNING
        )
        return
    }

    jvmConfigurations.forEach { executeRunConfiguration(this, it) }
}

private fun RunConfigType.configName(): String = when (this) {
    RunConfigType.JVM -> "main [jvm]"
    RunConfigType.ANDROID -> "composeApp"
    RunConfigType.JS -> "iosApp"
}

private fun executeRunConfiguration(project: Project, configSettings: RunnerAndConfigurationSettings) {
    try {
        val executor = ExecutorRegistry.getInstance().getExecutorById("Run")
            ?: ExecutorRegistry.getInstance().registeredExecutors.firstOrNull()
            ?: return

        val runner = ProgramRunner.getRunner(executor.id, configSettings.configuration) ?: return
        val environment = ExecutionEnvironment(executor, runner, configSettings, project)

        runner.execute(environment)

        showNotification(
            project,
            "Configuration Started",
            "Started running configuration: ${configSettings.name}",
            NotificationType.INFORMATION
        )
    } catch (e: Exception) {
        showNotification(
            project,
            "Failed to Run Configuration",
            "Error: ${e.message}",
            NotificationType.ERROR
        )
    }
}

private fun showNotification(project: Project, title: String, content: String, type: NotificationType) {
    com.intellij.notification.Notifications.Bus.notify(
        com.intellij.notification.Notification(
            "KotlinConfChallengePlugin.Notifications",
            title,
            content,
            type
        ),
        project
    )
}