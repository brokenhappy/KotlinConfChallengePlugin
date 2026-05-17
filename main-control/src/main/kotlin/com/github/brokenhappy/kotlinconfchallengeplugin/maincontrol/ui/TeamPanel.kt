package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

enum class ClipboardTransferState {
    IDLE, SENDING, RECEIVING, DONE, ERROR
}

data class ClipboardTransferProgress(
    val state: ClipboardTransferState = ClipboardTransferState.IDLE,
    val sent: Int = 0,
    val total: Int = 0,
    val resultText: String? = null,
)

@Composable
fun TeamPanel(
    teamName: String,
    teamColor: Color,
    connected: Boolean,
    lastCheckTime: Instant?,
    clipboardProgress: ClipboardTransferProgress,
    onRefresh: () -> Unit,
    onRestart: () -> Unit,
    onSendClipboard: () -> Unit,
    onGetClipboard: () -> Unit,
    onCancelClipboard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.padding(8.dp).fillMaxHeight(),
        elevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = teamName,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = teamColor,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (connected) Color(0xFF4CAF50) else Color(0xFFF44336))
                )
                Text(
                    text = if (connected) "Connected" else "Disconnected",
                    color = if (connected) Color(0xFF4CAF50) else Color(0xFFF44336),
                    fontWeight = FontWeight.Medium,
                )
            }

            Divider()

            if (lastCheckTime != null) {
                val local = lastCheckTime.toLocalDateTime(TimeZone.currentSystemDefault())
                Text(
                    text = "Last check: %02d:%02d:%02d".format(local.hour, local.minute, local.second),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = Color.Gray,
                )
            } else {
                Text(
                    text = "No check reported",
                    fontSize = 14.sp,
                    color = Color.Gray,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onRefresh,
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = teamColor.copy(alpha = 0.8f),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                ),
            ) {
                Text("Refresh Sheet", color = Color.White)
            }

            Button(
                onClick = onRestart,
                enabled = connected,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = teamColor.copy(alpha = 0.8f),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                ),
            ) {
                Text("Restart Run Config", color = Color.White)
            }

            Divider()

            ClipboardSection(
                teamColor = teamColor,
                connected = connected,
                progress = clipboardProgress,
                onSendClipboard = onSendClipboard,
                onGetClipboard = onGetClipboard,
                onCancel = onCancelClipboard,
            )
        }
    }
}

@Composable
private fun ClipboardSection(
    teamColor: Color,
    connected: Boolean,
    progress: ClipboardTransferProgress,
    onSendClipboard: () -> Unit,
    onGetClipboard: () -> Unit,
    onCancel: () -> Unit,
) {
    val transferring = progress.state == ClipboardTransferState.SENDING || progress.state == ClipboardTransferState.RECEIVING

    Text(
        text = "Clipboard",
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
    )

    if (transferring) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val label = if (progress.state == ClipboardTransferState.SENDING) "Sending..." else "Receiving..."
            Text(text = label, fontSize = 12.sp, color = Color.Gray)

            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = progress.sent.toFloat() / progress.total,
                    modifier = Modifier.fillMaxWidth(),
                    color = teamColor,
                )
                Text(
                    text = "${progress.sent}/${progress.total} chunks",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.Gray,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = teamColor,
                )
            }

            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Cancel", color = Color(0xFFF44336))
            }
        }
    } else {
        when (progress.state) {
            ClipboardTransferState.DONE -> {
                val text = if (progress.resultText != null) {
                    val preview = progress.resultText.take(50)
                    "Done: $preview${if (progress.resultText.length > 50) "..." else ""}"
                } else "Done!"
                Text(text = text, fontSize = 11.sp, color = Color(0xFF4CAF50))
            }
            ClipboardTransferState.ERROR -> {
                Text(text = progress.resultText ?: "Transfer failed", fontSize = 11.sp, color = Color(0xFFF44336))
            }
            else -> {}
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onSendClipboard,
                enabled = connected && !transferring,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = teamColor.copy(alpha = 0.8f),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                ),
            ) {
                Text("Send", color = Color.White, fontSize = 12.sp)
            }

            Button(
                onClick = onGetClipboard,
                enabled = connected && !transferring,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = teamColor.copy(alpha = 0.8f),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                ),
            ) {
                Text("Get", color = Color.White, fontSize = 12.sp)
            }
        }
    }
}
