package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ble.BlePeripheralBridge
import com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ui.MainControlApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

fun main() = application {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val state = MainControlState().apply { load() }
    val bleBridge = BlePeripheralBridge(appScope)
    val sheetMonitor = SheetMonitor(state, bleBridge, appScope)

    Window(
        onCloseRequest = {
            bleBridge.stop()
            sheetMonitor.stop()
            state.save()
            exitApplication()
        },
        title = "KotlinConf Challenge Control",
        state = rememberWindowState(width = 900.dp, height = 500.dp),
    ) {
        MainControlApp(state, bleBridge, sheetMonitor)
    }
}
