package com.github.brokenhappy.kotlinconfchallengeplugin.voteserver

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

fun Application.configureRouting(state: VotingState) {
    routing {
        post("/vote/red") {
            if (!state.isSessionActive()) {
                call.respond(HttpStatusCode.Forbidden, "Voting session is not active")
                return@post
            }
            state.voteRed()
            call.respond(HttpStatusCode.OK)
        }

        post("/vote/blue") {
            if (!state.isSessionActive()) {
                call.respond(HttpStatusCode.Forbidden, "Voting session is not active")
                return@post
            }
            state.voteBlue()
            call.respond(HttpStatusCode.OK)
        }

        post("/hiddenUrl/startVotingSession") {
            val body = call.receive<AdminRequest>()
            if (!state.checkPassword(body.password)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid password")
                return@post
            }
            state.startSession()
            call.respond(HttpStatusCode.OK, "Voting session started")
        }

        post("/hiddenUrl/endVotingSessionButDontResetVoteCount") {
            val body = call.receive<AdminRequest>()
            if (!state.checkPassword(body.password)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid password")
                return@post
            }
            state.endSession()
            call.respond(HttpStatusCode.OK, "Voting session ended")
        }

        post("/hiddenUrl/resetVoteCount") {
            val body = call.receive<AdminRequest>()
            if (!state.checkPassword(body.password)) {
                call.respond(HttpStatusCode.Unauthorized, "Invalid password")
                return@post
            }
            state.resetVotes()
            call.respond(HttpStatusCode.OK, "Vote count reset")
        }

        webSocket("/ws/notSoHiddenUrlBecauseItWillBeInOpenSourceRepo/status") {
            val json = Json { encodeDefaults = true }
            state.updates.collect { status ->
                send(Frame.Text(json.encodeToString(status)))
            }
        }
    }
}
