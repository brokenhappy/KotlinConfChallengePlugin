package com.github.brokenhappy.kotlinconfchallengeplugin.startup

import com.github.brokenhappy.kotlinconfchallengeplugin.services.BleClientService
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class BleAutoConnectStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        project.service<BleClientService>().connectFromSettings()
    }
}
