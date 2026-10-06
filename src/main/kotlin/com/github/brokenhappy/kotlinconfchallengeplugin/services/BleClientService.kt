package com.github.brokenhappy.kotlinconfchallengeplugin.services

import com.github.brokenhappy.kotlinconfchallengeplugin.actions.reportToIdeErrorDialog
import com.github.brokenhappy.kotlinconfchallengeplugin.runJvm
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.computeCsvChecksum
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.*
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.juul.kable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

enum class BleConnectionState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

sealed class BleTestResult(val message: String) {
    class Success(message: String) : BleTestResult(message)
    class Failure(message: String) : BleTestResult(message)
}

@OptIn(ExperimentalUuidApi::class)
@Service(Service.Level.PROJECT)
class BleClientService(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val _connectionState = MutableStateFlow(BleConnectionState.DISCONNECTED)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _statusMessage = MutableStateFlow("Not started")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private var peripheral: Peripheral? = null
    private var connectJob: Job? = null
    private var encryption: BleEncryption? = null
    private var currentTeam: Team? = null

    private val log = logger<BleClientService>()
    private val serviceUuid = Uuid.parse(BleConfig.SERVICE_UUID)
    private val assembler = MessageAssembler()
    private val writeMutex = Mutex()

    private fun reportError(context: String, t: Throwable) {
        val detail = "$context: ${t::class.qualifiedName}: ${t.message}"
        _statusMessage.value = detail
        _connectionState.value = BleConnectionState.DISCONNECTED
        reportToIdeErrorDialog(context, t)
    }

    fun connectFromSettings() {
        val settings = project.service<ChallengeStateService>().appState().value.settings
        val teamStr = settings.bleTeam
        val keyBase64 = settings.bleEncryptionKeyBase64
        if (teamStr.isBlank() || keyBase64.isBlank()) {
            _statusMessage.value = "Not configured — set team and encryption key in Settings"
            return
        }

        val team = try {
            Team.valueOf(teamStr)
        } catch (e: Exception) {
            log.warn("Invalid team setting '$teamStr'", e)
            _statusMessage.value = "Invalid team '$teamStr' — must be RED or BLUE"
            return
        }
        currentTeam = team

        try {
            encryption = BleEncryption.fromBase64(keyBase64)
        } catch (e: Exception) {
            log.warn("Invalid encryption key", e)
            _statusMessage.value = "Invalid encryption key: ${e.message}"
            return
        }

        val oldJob = connectJob
        _statusMessage.value = "Starting BLE as Team $team..."
        connectJob = scope.launch(Dispatchers.Default) {
            oldJob?.cancelAndJoin()
            val stalePeripheral = peripheral
            peripheral = null
            if (stalePeripheral != null) {
                try { stalePeripheral.disconnect() } catch (_: Throwable) {}
            }
            try {
                connectLoop(team)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                reportError("connectLoop crashed", t)
            }
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        val p = peripheral
        peripheral = null
        _connectionState.value = BleConnectionState.DISCONNECTED
        if (p != null) {
            scope.launch(Dispatchers.Default) {
                try {
                    p.disconnect()
                } catch (_: CancellationException) {
                    throw CancellationException()
                } catch (t: Throwable) {
                    log.warn("Best-effort disconnect failed", t)
                }
            }
        }
    }

    suspend fun testConnection(
        teamStr: String,
        keyBase64: String,
        onStep: (String) -> Unit,
    ): BleTestResult {
        onStep("Validating settings...")
        if (teamStr.isBlank()) return BleTestResult.Failure("Team is not selected")
        if (keyBase64.isBlank()) return BleTestResult.Failure("Encryption key is empty")

        val team = try {
            Team.valueOf(teamStr)
        } catch (e: Exception) {
            log.warn("Test connection: invalid team '$teamStr'", e)
            return BleTestResult.Failure("Invalid team '$teamStr': ${e.message}")
        }

        val enc = try {
            BleEncryption.fromBase64(keyBase64)
        } catch (e: Exception) {
            log.warn("Test connection: invalid encryption key", e)
            return BleTestResult.Failure("Invalid encryption key: ${e.message}")
        }

        return withContext(Dispatchers.Default) {
            try {
                onStep("Creating scanner with service UUID filter...")
                val filteredScanner = Scanner {
                    filters { match { services = listOf(serviceUuid) } }
                }

                onStep("Scanning for peripheral (${BleConfig.SCAN_DURATION} timeout)...")
                val adv = try {
                    withTimeout(BleConfig.SCAN_DURATION) {
                        filteredScanner.advertisements.first()
                    }
                } catch (_: TimeoutCancellationException) {
                    return@withContext runDiagnosticScan(onStep)
                }

                val deviceName = adv.name ?: "unknown"
                onStep("Found '$deviceName'. Connecting...")
                val p = Peripheral(adv)
                withTimeout(10_000) { p.connect() }

                onStep("Connected. Writing test ping as $team...")
                val statusCharUuid = Uuid.parse(team.statusCharacteristicUuid())
                val statusChar = characteristicOf(service = serviceUuid, characteristic = statusCharUuid)

                val pingBytes = enc.encrypt(Command.Ping(Clock.System.now().toEpochMilliseconds()).encode())
                val chunks = encodeChunked(pingBytes)
                for ((i, chunk) in chunks.withIndex()) {
                    p.write(statusChar, chunk, WriteType.WithResponse)
                    onStep("Sent chunk ${i + 1}/${chunks.size}...")
                }

                onStep("Success! Disconnecting...")
                p.disconnect()
                BleTestResult.Success("Connected to '$deviceName', ping sent successfully as $team")
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                log.warn("Test connection failed", t)
                BleTestResult.Failure("${t::class.qualifiedName}: ${t.message}")
            }
        }
    }

    private suspend fun runDiagnosticScan(onStep: (String) -> Unit): BleTestResult {
        onStep("Service UUID not found. Running diagnostic scan (no filter)...")
        return try {
            val unfilteredScanner = Scanner {}
            val foundDevices = mutableListOf<String>()
            try {
                withTimeout(5_000) {
                    unfilteredScanner.advertisements.collect { adv ->
                        val desc = "'${adv.name ?: "unnamed"}'"
                        if (desc !in foundDevices) {
                            foundDevices.add(desc)
                            onStep("Diagnostic: found ${foundDevices.size} device(s) so far...")
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) { /* expected */ }

            if (foundDevices.isEmpty()) {
                BleTestResult.Failure(
                    "DIAGNOSTIC: No BLE devices found at all (0 devices in 5s).\n" +
                    "Likely causes:\n" +
                    "  1. Bluetooth is OFF on this Mac\n" +
                    "  2. IntelliJ lacks Bluetooth permission — check macOS System Settings > " +
                    "Privacy & Security > Bluetooth, and ensure IntelliJ IDEA is listed and enabled\n" +
                    "  3. If IntelliJ is not listed there, Kable/btleplug may not work from inside IntelliJ " +
                    "because IntelliJ's Info.plist lacks NSBluetoothAlwaysUsageDescription"
                )
            } else {
                BleTestResult.Failure(
                    "DIAGNOSTIC: Found ${foundDevices.size} BLE device(s) nearby " +
                    "(${foundDevices.joinToString(", ")}), " +
                    "but NONE advertise our service UUID (${BleConfig.SERVICE_UUID}).\n" +
                    "Likely causes:\n" +
                    "  1. Main control app is not running, or not started (click Start)\n" +
                    "  2. Main control's BLE peripheral failed to advertise — check its terminal for errors\n" +
                    "  3. UUID mismatch between main control and plugin"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Diagnostic BLE scan failed — Bluetooth likely inaccessible", t)
            BleTestResult.Failure(
                "DIAGNOSTIC: Unfiltered scan also failed: ${t::class.qualifiedName}: ${t.message}\n" +
                "This strongly suggests Bluetooth is not accessible from this process."
            )
        }
    }

    private suspend fun connectLoop(team: Team) {
        var attempt = 0
        while (currentCoroutineContext().isActive) {
            attempt++
            try {
                _connectionState.value = BleConnectionState.SCANNING
                _statusMessage.value = "Scanning for peripheral (attempt #$attempt)..."
                log.info("BLE: scanning (attempt #$attempt)...")

                val scanner = Scanner {
                    filters { match { services = listOf(serviceUuid) } }
                }
                val adv = withTimeout(BleConfig.SCAN_DURATION) {
                    scanner.advertisements.first()
                }

                _connectionState.value = BleConnectionState.CONNECTING
                val deviceName = adv.name ?: "unknown"
                _statusMessage.value = "Found '$deviceName'. Connecting..."
                log.info("BLE: found '$deviceName', connecting...")

                val p = Peripheral(adv)
                p.connect()
                peripheral = p
                _connectionState.value = BleConnectionState.CONNECTED
                _statusMessage.value = "Connected to '$deviceName' as $team"
                log.info("BLE: connected to '$deviceName' as $team")

                try {
                    coroutineScope {
                        launchObserve(team, p)
                        launchHeartbeat(team, p)
                        p.state.first { it is State.Disconnected }
                    }
                } catch (_: CancellationException) {
                    currentCoroutineContext().ensureActive()
                } catch (t: Throwable) {
                    if (!t.isBleDisconnection()) throw t
                    log.info("BLE: connection lost (${t.message})")
                }
                log.info("BLE: peripheral disconnected, will reconnect")
                _statusMessage.value = "Disconnected from '$deviceName'. Reconnecting..."
            } catch (_: TimeoutCancellationException) {
                log.info("BLE scan timeout (attempt #$attempt)")
                _statusMessage.value = "No peripheral found (attempt #$attempt) — retrying in ${BleConfig.RECONNECT_INTERVAL}..."
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                val detail = "${t::class.qualifiedName}: ${t.message}"
                log.warn("BLE connect loop error (attempt #$attempt)", t)
                _statusMessage.value = "$detail — retrying in ${BleConfig.RECONNECT_INTERVAL}..."
            }

            val stalePeripheral = peripheral
            peripheral = null
            _connectionState.value = BleConnectionState.DISCONNECTED
            if (stalePeripheral != null) {
                try { stalePeripheral.disconnect() } catch (_: Throwable) {}
            }
            delay(BleConfig.RECONNECT_INTERVAL)
        }
    }

    private fun CoroutineScope.launchObserve(team: Team, p: Peripheral) {
        val cmdCharUuid = Uuid.parse(team.commandCharacteristicUuid())
        val cmdChar = characteristicOf(service = serviceUuid, characteristic = cmdCharUuid)

        launch {
            p.observe(cmdChar).collect { bytes ->
                if (bytes.isNotEmpty() && bytes[0] == HEARTBEAT_BYTE) return@collect
                val assembled = assembler.processChunk(bytes) ?: return@collect
                val command = try {
                    val enc = encryption ?: return@collect
                    val decrypted = enc.decrypt(assembled)
                    decodeCommand(decrypted)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    reportToIdeErrorDialog("Command decode error", t)
                    _statusMessage.value = "Command decode error: ${t::class.qualifiedName}: ${t.message}"
                    return@collect
                }
                try {
                    handleCommand(command)
                } catch (e: CancellationException) {
                    throw e
                } catch (t: Throwable) {
                    reportToIdeErrorDialog("Command handler failed for ${command::class.simpleName}", t)
                }
            }
        }
    }

    private fun CoroutineScope.launchHeartbeat(team: Team, p: Peripheral) {
        val statusCharUuid = Uuid.parse(team.statusCharacteristicUuid())
        val statusChar = characteristicOf(service = serviceUuid, characteristic = statusCharUuid)

        launch {
            while (isActive) {
                delay(BleConfig.HEARTBEAT_INTERVAL)
                writeMutex.withLock {
                    p.write(statusChar, byteArrayOf(HEARTBEAT_BYTE), WriteType.WithResponse)
                }
            }
        }
    }

    private suspend fun handleCommand(command: Command) {
        when (command) {
            is Command.RefreshSheet -> {
                project.service<ChallengeDownloadCachingService>().hydrateFreshCaches {}
                reportSheetState()
            }
            is Command.RestartRunConfig -> {
                project.runJvm()
            }
            is Command.Ping -> {
                sendStatus(StatusReport.Pong(
                    pingTimestamp = command.timestamp,
                    pongTimestamp = Clock.System.now().toEpochMilliseconds(),
                ))
            }
            is Command.SetClipboard -> {
                try {
                    val selection = java.awt.datatransfer.StringSelection(command.content)
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, selection)
                } catch (t: Throwable) {
                    val detail = "Failed to set clipboard: ${t::class.qualifiedName}: ${t.message}"
                    reportToIdeErrorDialog("Failed to set clipboard", t)
                    _statusMessage.value = detail
                }
            }
            is Command.SetClipboardBinary -> {
                try {
                    val bytes = java.util.Base64.getDecoder().decode(command.contentBase64)
                    when (command.type) {
                        "image" -> {
                            val image = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(bytes))
                            if (image != null) {
                                val transferable = object : java.awt.datatransfer.Transferable {
                                    override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.imageFlavor)
                                    override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) =
                                        flavor == java.awt.datatransfer.DataFlavor.imageFlavor
                                    override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any {
                                        if (flavor == java.awt.datatransfer.DataFlavor.imageFlavor) return image
                                        throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                                    }
                                }
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                            }
                        }
                        "file" -> {
                            val tmpDir = java.nio.file.Files.createTempDirectory("ble-clipboard")
                            val fileName = command.fileName ?: "clipboard-file"
                            val tmpFile = tmpDir.resolve(fileName).toFile()
                            tmpFile.writeBytes(bytes)
                            val transferable = object : java.awt.datatransfer.Transferable {
                                private val flavors = arrayOf(
                                    java.awt.datatransfer.DataFlavor.javaFileListFlavor,
                                    java.awt.datatransfer.DataFlavor.stringFlavor,
                                )
                                override fun getTransferDataFlavors() = flavors
                                override fun isDataFlavorSupported(flavor: java.awt.datatransfer.DataFlavor) = flavor in flavors
                                override fun getTransferData(flavor: java.awt.datatransfer.DataFlavor): Any = when (flavor) {
                                    java.awt.datatransfer.DataFlavor.javaFileListFlavor -> listOf(tmpFile)
                                    java.awt.datatransfer.DataFlavor.stringFlavor -> tmpFile.absolutePath
                                    else -> throw java.awt.datatransfer.UnsupportedFlavorException(flavor)
                                }
                            }
                            java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(transferable, null)
                        }
                    }
                } catch (t: Throwable) {
                    val detail = "Failed to set clipboard binary: ${t::class.qualifiedName}: ${t.message}"
                    reportToIdeErrorDialog("Failed to set clipboard binary", t)
                    _statusMessage.value = detail
                }
            }
            is Command.GetClipboard -> {
                val text = try {
                    val clipboard = java.awt.Toolkit.getDefaultToolkit().systemClipboard
                    if (clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)) {
                        clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor) as? String
                    } else {
                        null
                    }
                } catch (t: Throwable) {
                    null
                }
                sendStatus(StatusReport.ClipboardContent(text ?: ""))
            }
        }
    }

    suspend fun reportSheetState() {
        val sheetId = project.service<ChallengeStateService>().appState().value.settings.googleSheetId
        val csv = try {
            downloadChallengesCsv(sheetId) ?: return
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            log.warn("Failed to download CSV for sheet state report", t)
            return
        }
        val checksum = computeCsvChecksum(csv)
        sendStatus(StatusReport.SheetState(checksum, Clock.System.now().toEpochMilliseconds()))
    }

    private fun Throwable.isBleDisconnection(): Boolean =
        generateSequence(this) { it.cause }.any { ex ->
            val msg = ex.message.orEmpty()
            "Channel closed" in msg || "NotConnected" in msg
        }

    private suspend fun sendStatus(report: StatusReport) {
        val p = peripheral ?: return
        val enc = encryption ?: return
        val team = currentTeam ?: return
        val statusCharUuid = Uuid.parse(team.statusCharacteristicUuid())
        val statusChar = characteristicOf(service = serviceUuid, characteristic = statusCharUuid)

        val encrypted = enc.encrypt(report.encode())
        val chunks = encodeChunked(encrypted)
        writeMutex.withLock {
            for (chunk in chunks) {
                try {
                    p.write(statusChar, chunk, WriteType.WithResponse)
                } catch (t: Throwable) {
                    val detail = "Status write failed: ${t::class.qualifiedName}: ${t.message}"
                    log.warn("BLE status write failed", t)
                    _statusMessage.value = detail
                    break
                }
            }
        }
    }
}
