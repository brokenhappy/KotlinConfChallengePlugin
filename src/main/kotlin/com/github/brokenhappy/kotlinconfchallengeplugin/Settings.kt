package com.github.brokenhappy.kotlinconfchallengeplugin

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.input.TextFieldState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.jewel.ui.component.TextField
import com.github.brokenhappy.kotlinconfchallengeplugin.services.BleClientService
import com.github.brokenhappy.kotlinconfchallengeplugin.services.BleConnectionState
import com.github.brokenhappy.kotlinconfchallengeplugin.services.BleTestResult
import com.github.brokenhappy.kotlinconfchallengeplugin.services.ChallengeStateService
import com.intellij.openapi.components.service
import com.github.brokenhappy.kotlinconfchallengeplugin.actions.reportToIdeErrorDialog
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import org.jetbrains.jewel.bridge.JewelComposePanel
import org.jetbrains.jewel.ui.component.Checkbox
import org.jetbrains.jewel.ui.component.DefaultButton
import org.jetbrains.jewel.ui.component.Dropdown
import org.jetbrains.jewel.ui.component.OutlinedButton
import org.jetbrains.jewel.ui.component.Text
import org.jetbrains.jewel.ui.component.separator
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import javax.swing.JComponent


@Serializable
internal data class ChallengeSettings(
    val googleSheetId: String = "",
    val fileSharedBetweenRuntimeAndPlugin: String = "",
    val enableAutoStartJvmAndAndroidAfterChallengeCompletion: Boolean = false,
    val enableAutoPatchAndUndoChangesAfterChallengeCompletion: Boolean = false,
    val bleTeam: String = "",
    val bleEncryptionKeyBase64: String = "",
)

private class SettingsConfigurable(private val project: Project): Configurable {
    private val localSetting = MutableStateFlow(project.service<ChallengeStateService>().appState().value.settings)

    override fun getDisplayName(): @NlsContexts.ConfigurableName String? = "KotlinConf Challenge Plugin"

    override fun createComponent(): JComponent = JewelComposePanel {
        val appState by project.service<ChallengeStateService>().appState().collectAsState()

        ChallengeSettingsView(
            project = project,
            settings = appState.settings,
            onChange = { newSettings -> localSetting.value = newSettings },
        )
    }

    override fun isModified(): Boolean =
        project.service<ChallengeStateService>().appState().value.settings != localSetting.value

    override fun apply() {
        val newSettings = localSetting.value
        project.service<ChallengeStateService>().update { it.copy(settings = newSettings) }
        project.service<BleClientService>().connectFromSettings()
    }
}

@Composable
private fun ChallengeSettingsView(
    project: Project,
    settings: ChallengeSettings,
    onChange: (ChallengeSettings) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { current = settings }

    fun update(newSettings: ChallengeSettings) {
        current = newSettings
        onChange(newSettings)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Google sheet ID:")
            RedactedField(
                value = current.googleSheetId,
                onChange = { update(current.copy(googleSheetId = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("File location of file that is shared between runtime and plugin:")
            TextField(
                current.fileSharedBetweenRuntimeAndPlugin,
                onChange = { update(current.copy(fileSharedBetweenRuntimeAndPlugin = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatically start the JVM and Android run configs after challenge completion:")
            Checkbox(
                checked = current.enableAutoStartJvmAndAndroidAfterChallengeCompletion,
                onCheckedChange = { update(current.copy(enableAutoStartJvmAndAndroidAfterChallengeCompletion = it)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Automatically save and undo the changes that the user made after challenge completion:")
            Checkbox(
                checked = current.enableAutoPatchAndUndoChangesAfterChallengeCompletion,
                onCheckedChange = { update(current.copy(enableAutoPatchAndUndoChangesAfterChallengeCompletion = it)) },
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text("BLE Connection")

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Team:")
            Dropdown(
                menuContent = {
                    selectableItem(
                        selected = current.bleTeam == "RED",
                        onClick = { update(current.copy(bleTeam = "RED")) },
                    ) { Text("Red") }
                    selectableItem(
                        selected = current.bleTeam == "BLUE",
                        onClick = { update(current.copy(bleTeam = "BLUE")) },
                    ) { Text("Blue") }
                },
            ) {
                Text(
                    when (current.bleTeam) {
                        "RED" -> "Red"
                        "BLUE" -> "Blue"
                        else -> "Select team..."
                    }
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Encryption key (Base64):")
            RedactedField(
                value = current.bleEncryptionKeyBase64,
                onChange = { update(current.copy(bleEncryptionKeyBase64 = it)) },
            )
        }

        BleStatusSection(project)
        BleTestSection(project, current)
    }
}

@Composable
private fun BleTestSection(project: Project, current: ChallengeSettings) {
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<BleTestResult?>(null) }
    var testStep by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DefaultButton(
                enabled = !testing,
                onClick = {
                    testing = true
                    testResult = null
                    testStep = ""
                    scope.launch {
                        try {
                            testResult = project.service<BleClientService>().testConnection(
                                teamStr = current.bleTeam,
                                keyBase64 = current.bleEncryptionKeyBase64,
                                onStep = { testStep = it },
                            )
                        } catch (e: CancellationException) {
                            throw e
                        } catch (t: Throwable) {
                            reportToIdeErrorDialog("BLE test connection failed unexpectedly", t)
                            testResult = BleTestResult.Failure("Unexpected: ${t::class.qualifiedName}: ${t.message}")
                        } finally {
                            testing = false
                        }
                    }
                },
            ) {
                Text(if (testing) "Testing..." else "Test Connection")
            }
        }

        if (testing && testStep.isNotEmpty()) {
            Text(text = testStep, modifier = Modifier.padding(top = 4.dp))
        }

        val result = testResult
        if (result != null) {
            Text(
                text = result.message,
                modifier = Modifier.padding(top = 4.dp),
            )
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

@Composable
private fun RedactedField(
    value: String,
    onChange: (String) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = if (value.isBlank()) "(empty)" else "••••••••",
            modifier = Modifier.padding(horizontal = 8.dp),
            fontSize = 13.sp,
        )
        OutlinedButton(onClick = {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            val pasted = try {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } catch (_: Throwable) { null }
            if (pasted != null) onChange(pasted)
        }) { Text("Paste") }
        if (value.isNotBlank()) {
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(onClick = { onChange("") }) { Text("Clear") }
        }
    }
}

@Composable
private fun BleStatusSection(project: Project) {
    val bleService = remember { project.service<BleClientService>() }
    val bleState by bleService.connectionState.collectAsState()
    val bleStatus by bleService.statusMessage.collectAsState()
    val color = when (bleState) {
        BleConnectionState.CONNECTED -> Color(0xFF4CAF50)
        BleConnectionState.SCANNING, BleConnectionState.CONNECTING -> Color(0xFFFFC107)
        BleConnectionState.DISCONNECTED -> Color(0xFFF44336)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 4.dp),
    ) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = bleStatus, fontSize = 11.sp, maxLines = 1)
    }
}
