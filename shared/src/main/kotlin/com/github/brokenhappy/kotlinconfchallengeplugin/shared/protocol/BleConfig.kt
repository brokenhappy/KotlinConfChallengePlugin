package com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol

import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

object BleConfig {
    const val SERVICE_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    const val RED_COMMAND_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567891"
    const val RED_STATUS_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567892"
    const val BLUE_COMMAND_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567893"
    const val BLUE_STATUS_UUID = "a1b2c3d4-e5f6-7890-abcd-ef1234567894"

    val RECONNECT_INTERVAL = 5.seconds
    val SCAN_DURATION = 10.seconds
    val HEARTBEAT_INTERVAL = 500.milliseconds
    val HEARTBEAT_TIMEOUT = 2.seconds
}
