package com.github.brokenhappy.kotlinconfchallengeplugin.voteserver

import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.websocket.*

fun main() {
    val port = System.getenv("VOTE_SERVER_PORT")?.toIntOrNull() ?: 8080
    val password = System.getenv("VOTE_ADMIN_PASSWORD") ?: "secret password"
    val state = VotingState(password)

    embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        configureRouting(state)
    }.start(wait = true)
}
