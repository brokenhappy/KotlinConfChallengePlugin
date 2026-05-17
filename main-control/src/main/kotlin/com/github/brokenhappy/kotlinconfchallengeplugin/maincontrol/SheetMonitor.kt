package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol

import com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ble.BlePeripheralBridge
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.computeCsvChecksum
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.downloadChallengesCsv
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.Command
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.StatusReport
import com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol.Team
import kotlinx.coroutines.*
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.seconds

class SheetMonitor(
    private val state: MainControlState,
    private val bleBridge: BlePeripheralBridge,
    private val scope: CoroutineScope,
) {
    private var monitorJob: Job? = null
    private var statusListenerJob: Job? = null

    fun start() {
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (isActive) {
                checkSheet()
                delay(15.seconds)
            }
        }

        statusListenerJob?.cancel()
        statusListenerJob = scope.launch {
            bleBridge.statusReports.collect { (team, report) ->
                when (report) {
                    is StatusReport.SheetState -> {
                        val checkTime = Instant.fromEpochMilliseconds(report.checkedAt)
                        when (team) {
                            Team.RED -> state.redLastCheckTime.value = checkTime
                            Team.BLUE -> state.blueLastCheckTime.value = checkTime
                        }
                    }
                    is StatusReport.Pong -> {}
                    is StatusReport.Heartbeat -> {}
                    is StatusReport.ClipboardContent -> {}
                }
            }
        }
    }

    fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        statusListenerJob?.cancel()
        statusListenerJob = null
    }

    suspend fun refreshNow() {
        checkSheet()
    }

    private suspend fun checkSheet() {
        val sheetId = state.sheetId.value
        if (sheetId.isBlank()) return

        val csv = downloadChallengesCsv(sheetId) ?: return
        val newChecksum = computeCsvChecksum(csv)
        val oldChecksum = state.currentChecksum.value

        state.currentChecksum.value = newChecksum
        state.lastSheetUpdateTime.value = Clock.System.now()

        if (oldChecksum != null && oldChecksum != newChecksum) {
            bleBridge.sendCommandToBoth(Command.RefreshSheet)
        }
    }
}
