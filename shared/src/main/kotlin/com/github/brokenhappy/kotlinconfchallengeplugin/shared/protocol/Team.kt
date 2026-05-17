package com.github.brokenhappy.kotlinconfchallengeplugin.shared.protocol

enum class Team {
    RED, BLUE;

    fun commandCharacteristicUuid(): String = when (this) {
        RED -> BleConfig.RED_COMMAND_UUID
        BLUE -> BleConfig.BLUE_COMMAND_UUID
    }

    fun statusCharacteristicUuid(): String = when (this) {
        RED -> BleConfig.RED_STATUS_UUID
        BLUE -> BleConfig.BLUE_STATUS_UUID
    }
}
