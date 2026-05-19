package com.github.brokenhappy.kotlinconfchallengeplugin.voteserver

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@Serializable
data class VoteStatus(
    val number_of_red_votes: Int,
    val number_of_blue_votes: Int,
    val session_active: Boolean,
)

@Serializable
data class AdminRequest(val password: String)

class VotingState(private val password: String) {
    private val redVotes = AtomicInteger(0)
    private val blueVotes = AtomicInteger(0)
    private val sessionActive = AtomicBoolean(false)
    private val _updates = MutableSharedFlow<VoteStatus>(replay = 1)
    val updates: SharedFlow<VoteStatus> = _updates

    fun isSessionActive(): Boolean = sessionActive.get()

    fun currentStatus(): VoteStatus = VoteStatus(
        number_of_red_votes = redVotes.get(),
        number_of_blue_votes = blueVotes.get(),
        session_active = sessionActive.get(),
    )

    fun voteRed() {
        if (!sessionActive.get()) return
        redVotes.incrementAndGet()
        emit()
    }

    fun voteBlue() {
        if (!sessionActive.get()) return
        blueVotes.incrementAndGet()
        emit()
    }

    fun checkPassword(candidate: String): Boolean = candidate == password

    fun startSession() {
        sessionActive.set(true)
        emit()
    }

    fun endSession() {
        sessionActive.set(false)
        emit()
    }

    fun resetVotes() {
        redVotes.set(0)
        blueVotes.set(0)
        emit()
    }

    private fun emit() {
        _updates.tryEmit(currentStatus())
    }
}
