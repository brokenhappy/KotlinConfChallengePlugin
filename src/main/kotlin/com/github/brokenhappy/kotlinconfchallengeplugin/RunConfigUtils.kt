package com.github.brokenhappy.kotlinconfchallengeplugin

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ExecutorRegistry
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class RunConfigType {
    JVM,
    ANDROID,
    JS,
}

internal suspend fun Project.runConfigurations(vararg types: RunConfigType) {
    val config = RunManager.getInstance(this).allSettings.firstOrNull { "hotRun" in it.name }
        ?: return
    executeRunConfiguration(this, config)
}

private suspend fun executeRunConfiguration(project: Project, configSettings: RunnerAndConfigurationSettings) {
    withBackgroundProgress(project, "Restarting ${configSettings.name}") {
        withContext(Dispatchers.Default) {
            ExecutionManager.getInstance(project)
                .getRunningDescriptors { it.name == configSettings.name }
                .forEach { it.processHandler?.destroyProcess() }
        }
    }

    withContext(Dispatchers.EDT) {
        val executor = ExecutorRegistry.getInstance().getExecutorById("Run")
            ?: ExecutorRegistry.getInstance().registeredExecutors.firstOrNull()
            ?: return@withContext
        val runner = ProgramRunner.getRunner(executor.id, configSettings.configuration)
            ?: return@withContext
        val environment = ExecutionEnvironment(executor, runner, configSettings, project)
        environment.setHeadless()
        runner.execute(environment)
    }
}