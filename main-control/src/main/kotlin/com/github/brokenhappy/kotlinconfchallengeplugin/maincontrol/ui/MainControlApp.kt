package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.MainControlState
import com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.SheetMonitor
import com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ble.BlePeripheralBridge
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.Command
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.Team
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

@Composable
fun MainControlApp(
    state: MainControlState,
    bleBridge: BlePeripheralBridge,
    sheetMonitor: SheetMonitor,
) {
    val scope = rememberCoroutineScope()
    val redConnected by bleBridge.redConnected.collectAsState()
    val blueConnected by bleBridge.blueConnected.collectAsState()

    LaunchedEffect(redConnected) { state.redConnected.value = redConnected }
    LaunchedEffect(blueConnected) { state.blueConnected.value = blueConnected }

    val bothEnabled = redConnected && blueConnected

    var votingStatus by remember { mutableStateOf<String?>(null) }
    val httpClient = remember { HttpClient(CIO) }

    fun callVoteEndpoint(path: String) {
        votingStatus = null
        scope.launch {
            try {
                val url = state.voteServerUrl.value.trimEnd('/') + path
                val password = state.voteAdminPassword.value
                val response = httpClient.post(url) {
                    contentType(ContentType.Application.Json)
                    setBody("""{"password":"${password.replace("\"", "\\\"")}"}""")
                }
                votingStatus = if (response.status.isSuccess()) "OK" else "Error: ${response.status}"
            } catch (e: Exception) {
                votingStatus = "Error: ${e.message}"
            }
        }
    }

    var redClipboard by remember { mutableStateOf(ClipboardTransferProgress()) }
    var blueClipboard by remember { mutableStateOf(ClipboardTransferProgress()) }
    var redClipboardJob by remember { mutableStateOf<Job?>(null) }
    var blueClipboardJob by remember { mutableStateOf<Job?>(null) }

    fun sendClipboardTo(team: Team) {
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard

        val command: Command
        val resultDescription: String

        val text = try {
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor))
                clipboard.getData(DataFlavor.stringFlavor) as? String
            else null
        } catch (_: Exception) { null }

        if (!text.isNullOrEmpty()) {
            command = Command.SetClipboard(text)
            resultDescription = text
        } else {
            val imageBytes = try {
                if (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    val img = clipboard.getData(DataFlavor.imageFlavor) as? java.awt.Image
                    img?.let {
                        val buffered = if (it is java.awt.image.BufferedImage) it
                        else java.awt.image.BufferedImage(
                            it.getWidth(null), it.getHeight(null),
                            java.awt.image.BufferedImage.TYPE_INT_ARGB,
                        ).also { bImg -> bImg.createGraphics().apply { drawImage(img, 0, 0, null); dispose() } }
                        val baos = java.io.ByteArrayOutputStream()
                        javax.imageio.ImageIO.write(buffered, "png", baos)
                        baos.toByteArray()
                    }
                } else null
            } catch (_: Exception) { null }

            if (imageBytes != null) {
                command = Command.SetClipboardBinary(
                    contentBase64 = java.util.Base64.getEncoder().encodeToString(imageBytes),
                    type = "image",
                )
                resultDescription = "Image (${imageBytes.size / 1024} KB)"
            } else {
                @Suppress("UNCHECKED_CAST")
                val file = try {
                    if (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor))
                        (clipboard.getData(DataFlavor.javaFileListFlavor) as? List<java.io.File>)?.firstOrNull()
                    else null
                } catch (_: Exception) { null }

                if (file != null && file.exists()) {
                    val bytes = file.readBytes()
                    command = Command.SetClipboardBinary(
                        contentBase64 = java.util.Base64.getEncoder().encodeToString(bytes),
                        type = "file",
                        fileName = file.name,
                    )
                    resultDescription = "File: ${file.name} (${bytes.size / 1024} KB)"
                } else {
                    return
                }
            }
        }

        val setProgress = if (team == Team.RED) { p: ClipboardTransferProgress -> redClipboard = p } else { p: ClipboardTransferProgress -> blueClipboard = p }
        val setJob = if (team == Team.RED) { j: Job? -> redClipboardJob = j } else { j: Job? -> blueClipboardJob = j }

        setProgress(ClipboardTransferProgress(state = ClipboardTransferState.SENDING))
        val job = scope.launch {
            try {
                bleBridge.sendCommandWithProgress(team, command) { sent, total ->
                    setProgress(ClipboardTransferProgress(state = ClipboardTransferState.SENDING, sent = sent, total = total))
                }
                setProgress(ClipboardTransferProgress(state = ClipboardTransferState.DONE, resultText = resultDescription))
            } catch (_: kotlinx.coroutines.CancellationException) {
                setProgress(ClipboardTransferProgress())
                throw kotlinx.coroutines.CancellationException()
            } catch (e: Exception) {
                setProgress(ClipboardTransferProgress(state = ClipboardTransferState.ERROR, resultText = e.message))
            } finally {
                setJob(null)
            }
        }
        setJob(job)
    }

    fun getClipboardFrom(team: Team) {
        val setProgress = if (team == Team.RED) { p: ClipboardTransferProgress -> redClipboard = p } else { p: ClipboardTransferProgress -> blueClipboard = p }
        val setJob = if (team == Team.RED) { j: Job? -> redClipboardJob = j } else { j: Job? -> blueClipboardJob = j }

        setProgress(ClipboardTransferProgress(state = ClipboardTransferState.RECEIVING))
        val job = scope.launch {
            try {
                bleBridge.sendCommand(team, Command.GetClipboard)
                setProgress(ClipboardTransferProgress(state = ClipboardTransferState.RECEIVING))
                val text = bleBridge.awaitClipboardFrom(team)
                if (text != null) {
                    try {
                        val selection = StringSelection(text)
                        Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                    } catch (e: Exception) {
                        setProgress(ClipboardTransferProgress(state = ClipboardTransferState.ERROR, resultText = "Failed to set clipboard: ${e.message}"))
                        return@launch
                    }
                    setProgress(ClipboardTransferProgress(state = ClipboardTransferState.DONE, resultText = text))
                } else {
                    setProgress(ClipboardTransferProgress(state = ClipboardTransferState.ERROR, resultText = "Timeout waiting for response"))
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                setProgress(ClipboardTransferProgress())
                throw kotlinx.coroutines.CancellationException()
            } catch (e: Exception) {
                setProgress(ClipboardTransferProgress(state = ClipboardTransferState.ERROR, resultText = e.message))
            } finally {
                setJob(null)
            }
        }
        setJob(job)
    }

    MaterialTheme(colors = darkColors()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                TopBar(state, bleBridge, sheetMonitor)
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    TeamPanel(
                        teamName = "Team Red",
                        teamColor = Color(0xFFF44336),
                        connected = redConnected,
                        lastCheckTime = state.redLastCheckTime.value,
                        clipboardProgress = redClipboard,
                        onRefresh = { scope.launch { bleBridge.sendCommand(Team.RED, Command.RefreshSheet) } },
                        onRestart = { scope.launch { bleBridge.sendCommand(Team.RED, Command.RestartRunConfig) } },
                        onSendClipboard = { sendClipboardTo(Team.RED) },
                        onGetClipboard = { getClipboardFrom(Team.RED) },
                        onCancelClipboard = { redClipboardJob?.cancel() },
                        modifier = Modifier.weight(1f),
                    )
                    BothPanel(
                        bothEnabled = bothEnabled,
                        currentChecksum = state.currentChecksum.value,
                        lastUpdateTime = state.lastSheetUpdateTime.value,
                        onRefreshBoth = { scope.launch { bleBridge.sendCommandToBoth(Command.RefreshSheet) } },
                        onRestartBoth = { scope.launch { bleBridge.sendCommandToBoth(Command.RestartRunConfig) } },
                        onStartVoting = { callVoteEndpoint("/admin-panel-v9k3m/startVotingSession") },
                        onEndVoting = { callVoteEndpoint("/admin-panel-v9k3m/endVotingSessionButDontResetVoteCount") },
                        onResetVotes = { callVoteEndpoint("/admin-panel-v9k3m/resetVoteCount") },
                        votingStatus = votingStatus,
                        modifier = Modifier.weight(1f),
                    )
                    TeamPanel(
                        teamName = "Team Blue",
                        teamColor = Color(0xFF2196F3),
                        connected = blueConnected,
                        lastCheckTime = state.blueLastCheckTime.value,
                        clipboardProgress = blueClipboard,
                        onRefresh = { scope.launch { bleBridge.sendCommand(Team.BLUE, Command.RefreshSheet) } },
                        onRestart = { scope.launch { bleBridge.sendCommand(Team.BLUE, Command.RestartRunConfig) } },
                        onSendClipboard = { sendClipboardTo(Team.BLUE) },
                        onGetClipboard = { getClipboardFrom(Team.BLUE) },
                        onCancelClipboard = { blueClipboardJob?.cancel() },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@OptIn(FlowPreview::class)
@Composable
private fun TopBar(
    state: MainControlState,
    bleBridge: BlePeripheralBridge,
    sheetMonitor: SheetMonitor,
) {
    val scope = rememberCoroutineScope()
    val peripheralStatus by bleBridge.peripheralStatus.collectAsState()
    var settingsExpanded by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        snapshotFlow { state.encryptionKeyBase64.value }
            .debounce(300)
            .collectLatest { key ->
                bleBridge.stop()
                sheetMonitor.stop()
                startError = null
                if (key.isNotBlank()) {
                    val error = bleBridge.start(key)
                    if (error != null) {
                        startError = error
                    } else {
                        sheetMonitor.start()
                    }
                }
            }
    }

    Surface(
        elevation = 4.dp,
        color = MaterialTheme.colors.surface,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Peripheral: $peripheralStatus",
                    color = if (startError != null) Color(0xFFF44336) else Color.Gray,
                    fontSize = MaterialTheme.typography.caption.fontSize,
                    modifier = Modifier.weight(1f),
                )

                Button(
                    onClick = { scope.launch { sheetMonitor.refreshNow() } },
                    enabled = state.encryptionKeyBase64.value.isNotBlank(),
                ) {
                    Text("Refresh Sheet")
                }

                Button(onClick = { settingsExpanded = !settingsExpanded }) {
                    Text(if (settingsExpanded) "Hide Settings" else "Settings")
                }
            }

            if (startError != null) {
                Text(
                    text = "Error: $startError",
                    color = Color(0xFFF44336),
                    fontSize = MaterialTheme.typography.caption.fontSize,
                )
            }

            if (settingsExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.sheetId.value,
                        onValueChange = {
                            state.sheetId.value = it
                            state.save()
                        },
                        label = { Text("Google Sheet ID") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )

                    OutlinedTextField(
                        value = state.encryptionKeyBase64.value,
                        onValueChange = {
                            state.encryptionKeyBase64.value = it
                            state.save()
                        },
                        label = { Text("Encryption Key (Base64)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedTextField(
                        value = state.voteServerUrl.value,
                        onValueChange = {
                            state.voteServerUrl.value = it
                            state.save()
                        },
                        label = { Text("Vote Server URL") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                    )

                    OutlinedTextField(
                        value = state.voteAdminPassword.value,
                        onValueChange = {
                            state.voteAdminPassword.value = it
                            state.save()
                        },
                        label = { Text("Vote Admin Password") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }
            }
        }
    }
}
