package com.example.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ai.ActiveKeyType

/**
 * Key Status Pill displayed in ChatDetailScreen top bar / header or settings.
 * Shows which key is active (Primary vs Backup) with a one-tap manual toggle to switch/reset to Primary.
 */
@Composable
fun ApiKeyStatusPill(
    activeKeyType: ActiveKeyType,
    onToggleActiveKey: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isPrimary = activeKeyType == ActiveKeyType.PRIMARY

    val containerColor by animateColorAsState(
        targetValue = if (isPrimary) {
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        } else {
            MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.9f)
        },
        label = "pillContainerColor"
    )

    val contentColor by animateColorAsState(
        targetValue = if (isPrimary) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onTertiaryContainer
        },
        label = "pillContentColor"
    )

    Surface(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable { onToggleActiveKey() }
            .testTag("api_key_status_pill"),
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        border = BorderStroke(
            1.dp,
            if (isPrimary) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = if (isPrimary) Icons.Default.Key else Icons.Default.Bolt,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(13.dp)
            )

            Spacer(modifier = Modifier.width(4.dp))

            Text(
                text = activeKeyType.shortLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 11.sp),
                color = contentColor
            )

            if (!isPrimary) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = "Reset to Primary Key",
                    tint = contentColor,
                    modifier = Modifier.size(12.dp)
                )
            }
        }
    }
}
