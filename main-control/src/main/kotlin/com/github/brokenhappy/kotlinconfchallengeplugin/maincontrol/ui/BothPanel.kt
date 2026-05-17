package com.github.brokenhappy.kotlinconfchallengeplugin.maincontrol.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@Composable
fun BothPanel(
    bothEnabled: Boolean,
    currentChecksum: String?,
    lastUpdateTime: Instant?,
    onRefreshBoth: () -> Unit,
    onRestartBoth: () -> Unit,
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
                text = "Both",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF9C27B0),
            )

            if (!bothEnabled) {
                Text(
                    text = "Both teams must be connected",
                    fontSize = 13.sp,
                    color = Color.Gray,
                )
            }

            Divider()

            Text(
                text = "Sheet Status",
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
            )

            if (currentChecksum != null) {
                Text(
                    text = currentChecksum.take(16) + "...",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            } else {
                Text(
                    text = "No checksum yet",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }

            if (lastUpdateTime != null) {
                val local = lastUpdateTime.toLocalDateTime(TimeZone.currentSystemDefault())
                Text(
                    text = "Updated: %02d:%02d:%02d".format(local.hour, local.minute, local.second),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = Color.Gray,
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onRefreshBoth,
                enabled = bothEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF9C27B0),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                ),
            ) {
                Text("Refresh Both", color = Color.White)
            }

            Button(
                onClick = onRestartBoth,
                enabled = bothEnabled,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    backgroundColor = Color(0xFF9C27B0),
                    disabledBackgroundColor = Color.Gray.copy(alpha = 0.3f),
                ),
            ) {
                Text("Restart Both", color = Color.White)
            }
        }
    }
}
