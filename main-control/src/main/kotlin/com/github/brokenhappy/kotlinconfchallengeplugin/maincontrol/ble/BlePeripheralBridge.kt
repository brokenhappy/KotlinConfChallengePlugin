package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ble

import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class BlePeripheralBridge(
    private val scope: CoroutineScope,
) {
    private var lib: ControlBleLib? = null
    private var pollJob: Job? = null
    private var encryption: BleEncryption? = null

    private val _redConnected = MutableStateFlow(false)
    val redConnected: StateFlow<Boolean> = _redConnected.asStateFlow()

    private val _blueConnected = MutableStateFlow(false)
    val blueConnected: StateFlow<Boolean> = _blueConnected.asStateFlow()

    private val _peripheralStatus = MutableStateFlow("Stopped")
    val peripheralStatus: StateFlow<String> = _peripheralStatus.asStateFlow()

    private val _statusReports = MutableSharedFlow<Pair<Team, StatusReport>>(extraBufferCapacity = 16)
    val statusReports: SharedFlow<Pair<Team, StatusReport>> = _statusReports.asSharedFlow()

    private val redAssembler = MessageAssembler()
    private val blueAssembler = MessageAssembler()

    fun start(encryptionKeyBase64: String): String? {
        _peripheralStatus.value = "Loading BLE library..."
        val loadedLib = loadControlBleLib()
        if (loadedLib == null) {
            val msg = "BLE library not available — is the dylib bundled?"
            _peripheralStatus.value = msg
            return msg
        }
        lib = loadedLib

        try {
            encryption = BleEncryption.fromBase64(encryptionKeyBase64)
        } catch (e: Exception) {
            val msg = "Invalid encryption key: ${e.message}"
            _peripheralStatus.value = msg
            return msg
        }

        _peripheralStatus.value = "Starting GATT server..."
        loadedLib.control_ble_start(
            BleConfig.SERVICE_UUID,
            BleConfig.RED_COMMAND_UUID,
            BleConfig.RED_STATUS_UUID,
            BleConfig.BLUE_COMMAND_UUID,
            BleConfig.BLUE_STATUS_UUID,
        )
        _peripheralStatus.value = "Advertising as '${BleConfig.SERVICE_UUID.take(8)}...'"

        pollJob = scope.launch {
            while (isActive) {
                poll()
                delay(50)
            }
        }
        return null
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        lib?.control_ble_stop()
        lib = null
        encryption = null
        _redConnected.value = false
        _blueConnected.value = false
        _peripheralStatus.value = "Stopped"
    }

    suspend fun sendCommand(team: Team, command: Command) {
        sendCommandWithProgress(team, command) { _, _ -> }
    }

    /**
     * Sends a command with per-chunk progress reporting.
     * [onProgress] is called with (chunksSent, totalChunks) after each chunk.
     */
    suspend fun sendCommandWithProgress(
        team: Team,
        command: Command,
        onProgress: (sent: Int, total: Int) -> Unit,
    ) {
        val enc = encryption ?: return
        val l = lib ?: return
        val encrypted = enc.encrypt(command.encode())
        val chunks = encodeChunked(encrypted)
        onProgress(0, chunks.size)
        for ((i, chunk) in chunks.withIndex()) {
            currentCoroutineContext().ensureActive()
            when (team) {
                Team.RED -> l.control_ble_send_red(chunk, chunk.size)
                Team.BLUE -> l.control_ble_send_blue(chunk, chunk.size)
            }
            onProgress(i + 1, chunks.size)
            yield()
        }
    }

    suspend fun sendCommandToBoth(command: Command) {
        sendCommand(Team.RED, command)
        sendCommand(Team.BLUE, command)
    }

    /**
     * Waits for a [StatusReport.ClipboardContent] from [team], with timeout.
     * Returns the clipboard text, or null on timeout/error.
     */
    suspend fun awaitClipboardFrom(team: Team): String? {
        return withTimeoutOrNull(10_000) {
            statusReports.first { (t, r) -> t == team && r is StatusReport.ClipboardContent }
                .let { (_, r) -> (r as StatusReport.ClipboardContent).content }
        }
    }

    private suspend fun poll() {
        val l = lib ?: return

        _redConnected.value = l.control_ble_is_red_connected()
        _blueConnected.value = l.control_ble_is_blue_connected()

        pollTeam(l, Team.RED)
        pollTeam(l, Team.BLUE)
    }

    private suspend fun pollTeam(l: ControlBleLib, team: Team) {
        val buffer = ByteArray(1024)
        val assembler = when (team) {
            Team.RED -> redAssembler
            Team.BLUE -> blueAssembler
        }
        val n = when (team) {
            Team.RED -> l.control_ble_recv_red(buffer, buffer.size)
            Team.BLUE -> l.control_ble_recv_blue(buffer, buffer.size)
        }
        if (n > 0) {
            val chunk = buffer.copyOf(n)
            val assembled = assembler.processChunk(chunk) ?: return
            try {
                val enc = encryption ?: return
                val decrypted = enc.decrypt(assembled)
                val report = decodeStatusReport(decrypted)
                _statusReports.emit(team to report)
            } catch (e: Exception) {
                System.err.println("Failed to decrypt/decode message from $team: ${e.message}")
            }
        }
    }
}
