package com.github.brokenhappy.kotlinconfchallengeplugin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import org.jetbrains.jewel.ui.component.TextField
import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeStateService
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Text
import javax.swing.JComponent


@Serializable
internal data class ChallengeSettings(
    val currentChallengeNumber: Int,
    val pathToImages: String,
    /** Is used to communicate from the IDE plugin to the challenge runtime */
    val pathToFakeDatabaseFile: String,
)

private class SettingsConfigurable(private val project: Project): Configurable {
    private val localSetting = MutableStateFlow(project.service<ChallengeStateService>().appState().value.settings)

    override fun getDisplayName(): @NlsContexts.ConfigurableName String? = "KotlinConf Challenge Plugin"

    override fun createComponent(): JComponent = JewelComposePanel {
        val appState by project.service<ChallengeStateService>().appState().collectAsState()

        ChallengeSettingsView(
            appState.settings,
            onChange = { newSettings -> localSetting.value = newSettings },
        )
    }

    override fun isModified(): Boolean =
        project.service<ChallengeStateService>().appState().value.settings != localSetting.value

    override fun apply() {
        val newSettings = localSetting.value
        project.service<ChallengeStateService>().update { it.copy(settings = newSettings) }
    }
}

@Composable
private fun ChallengeSettingsView(settings: ChallengeSettings, onChange: (ChallengeSettings) -> Unit) {
    Column {
        Row {
            Text("Current challenge number:")
            val textState = remember { TextFieldState(initialText = settings.currentChallengeNumber.toString()) }
            LaunchedEffect(textState.text) {
                val number = textState.text.toString().toIntOrNull() ?: return@LaunchedEffect
                onChange(settings.copy(currentChallengeNumber = number))
            }
            TextField(textState)
        }
        Row {
            Text("Path to images")
            TextField(settings.pathToImages, onChange = { onChange(settings.copy(pathToImages = it)) })
        }
    }
}

@Composable
private fun TextField(
    text: String,
    onChange: (String) -> Unit,
) {
    val textState = remember { TextFieldState(initialText = text) }
    LaunchedEffect(textState.text) {
        onChange(textState.text.toString())
    }
    TextField(textState)
}