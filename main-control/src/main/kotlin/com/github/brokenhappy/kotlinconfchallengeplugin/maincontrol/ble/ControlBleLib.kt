package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ble

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

@Suppress("FunctionName")
interface ControlBleLib : Library {
    fun control_ble_start(
        serviceUuid: String,
        redCmdUuid: String,
        redStatusUuid: String,
        blueCmdUuid: String,
        blueStatusUuid: String,
    )
    fun control_ble_stop()
    fun control_ble_is_red_connected(): Boolean
    fun control_ble_is_blue_connected(): Boolean
    fun control_ble_send_red(data: ByteArray, length: Int): Boolean
    fun control_ble_send_blue(data: ByteArray, length: Int): Boolean
    fun control_ble_recv_red(buffer: ByteArray, maxLen: Int): Int
    fun control_ble_recv_blue(buffer: ByteArray, maxLen: Int): Int
}

fun loadControlBleLib(): ControlBleLib? {
    return try {
        val arch = System.getProperty("os.arch")
        val resPath = "/darwin-$arch/libcontrolble.dylib"
        val stream = ControlBleLib::class.java.getResourceAsStream(resPath) ?: return null
        val tmp = File.createTempFile("libcontrolble", ".dylib")
        tmp.deleteOnExit()
        stream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        Native.load(tmp.absolutePath, ControlBleLib::class.java)
    } catch (e: Exception) {
        System.err.println("Failed to load BLE library: ${e.message}")
        null
    }
}
