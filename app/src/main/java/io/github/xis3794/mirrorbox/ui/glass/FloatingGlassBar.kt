package io.github.xis3794.mirrorbox.ui.glass

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.Prefs

data class NavDestination(val label: String, val icon: ImageVector)

/** Floating capsule navigation bar with a glass surface and an animated selection pill. */
@Composable
fun FloatingGlassBar(
    destinations: List<NavDestination>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val perfMode = Prefs.performanceMode
    GlassSurface(
        modifier = modifier.fillMaxWidth().height(64.dp),
        shape = RoundedCornerShape(26.dp),
        strength = if (perfMode) 1f else 1.15f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            destinations.forEachIndexed { index, destination ->
                val selected = index == selectedIndex
                // 性能模式下不做颜色动画（每项两个 animateColorAsState 在低端机上是持续帧开销）。
                val tint = if (perfMode) {
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        label = "navTint",
                    ).value
                }
                val pill = if (perfMode) {
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent
                } else {
                    animateColorAsState(
                        targetValue = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0f)
                        },
                        label = "navPill",
                    ).value
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(pill)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onSelect(index)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = destination.icon,
                            contentDescription = destination.label,
                            tint = tint,
                            modifier = Modifier.size(21.dp),
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = destination.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = tint,
                        )
                    }
                }
            }
        }
    }
}