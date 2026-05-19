package com.github.brokenhappy.kotlinconfchallengeplugin.voteserver

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
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

    val server = embeddedServer(Netty, port = port) {
        install(ContentNegotiation) { json() }
        install(WebSockets)
        configureRouting(state)
    }.start(wait = false)

    application {
        Window(onCloseRequest = { server.stop(1000, 2000); exitApplication() }, title = "KotlinConf Vote Server") {
            VotingApp(state)
        }
    }
}

@Composable
fun VotingApp(state: VotingState) {
    val status by state.updates.collectAsState(state.currentStatus())

    MaterialTheme(colors = darkColors()) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = if (status.session_active) "Voting is OPEN" else "Voting is CLOSED",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.session_active) Color(0xFF4CAF50) else Color(0xFFF44336),
                )

                Spacer(Modifier.height(32.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Text(
                        text = "${status.number_of_red_votes}",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFEF5350),
                    )
                    Text(
                        text = "-",
                        fontSize = 64.sp,
                        color = Color.Gray,
                    )
                    Text(
                        text = "${status.number_of_blue_votes}",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF42A5F5),
                    )
                }

                Spacer(Modifier.height(32.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(
                        onClick = { state.voteRed() },
                        enabled = status.session_active,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFFD32F2F),
                            disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.size(width = 180.dp, height = 60.dp),
                    ) {
                        Text("Vote Red", color = Color.White, fontSize = 18.sp)
                    }

                    Button(
                        onClick = { state.voteBlue() },
                        enabled = status.session_active,
                        colors = ButtonDefaults.buttonColors(
                            backgroundColor = Color(0xFF1976D2),
                            disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                        ),
                        modifier = Modifier.size(width = 180.dp, height = 60.dp),
                    ) {
                        Text("Vote Blue", color = Color.White, fontSize = 18.sp)
                    }
                }
            }
        }
    }
}
