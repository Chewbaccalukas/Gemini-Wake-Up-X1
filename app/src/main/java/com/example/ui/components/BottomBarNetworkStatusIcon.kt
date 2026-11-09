package com.example.ui.components

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.util.NetworkConnectionState
import kotlinx.coroutines.delay

/**
 * Compact color-coded network status icon for the bottom bar.
 * Reveals the active connection type on tap.
 */
@Composable
fun BottomBarNetworkStatusIcon(
    networkState: NetworkConnectionState,
    onRefreshNetwork: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showConnectionLabel by remember { mutableStateOf(false) }

    LaunchedEffect(showConnectionLabel) {
        if (showConnectionLabel) {
            delay(3000L)
            showConnectionLabel = false
        }
    }

    val statusColor = when (networkState) {
        NetworkConnectionState.ONLINE_WIFI,
        NetworkConnectionState.ONLINE_ETHERNET -> Color(0xFF10B981) // Emerald
        NetworkConnectionState.ONLINE_CELLULAR -> Color(0xFF38BDF8) // Cyan
        NetworkConnectionState.OFFLINE -> Color(0xFFEF4444) // Red
    }

    val iconVector = when (networkState) {
        NetworkConnectionState.ONLINE_WIFI,
        NetworkConnectionState.ONLINE_ETHERNET -> Icons.Default.Wifi
        NetworkConnectionState.ONLINE_CELLULAR -> Icons.Default.SignalCellularAlt
        NetworkConnectionState.OFFLINE -> Icons.Default.WifiOff
    }

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(statusColor.copy(alpha = 0.14f))
            .border(1.dp, statusColor.copy(alpha = 0.45f), RoundedCornerShape(14.dp))
            .clickable {
                onRefreshNetwork()
                showConnectionLabel = !showConnectionLabel
                Toast.makeText(
                    context,
                    "Network: ${networkState.displayLabel}",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .testTag("bottom_bar_network_status_icon"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(statusColor)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Icon(
            imageVector = iconVector,
            contentDescription = "Network status: ${networkState.displayLabel}",
            tint = statusColor,
            modifier = Modifier.size(15.dp)
        )
        AnimatedVisibility(
            visible = showConnectionLabel,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = networkState.shortLabel,
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    ),
                    color = statusColor,
                    modifier = Modifier.testTag("network_status_label_text")
                )
            }
        }
    }
}
