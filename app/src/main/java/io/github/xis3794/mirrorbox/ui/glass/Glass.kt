package io.github.xis3794.mirrorbox.ui.glass

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.Prefs
import io.github.xis3794.mirrorbox.ui.theme.isDarkScheme

/**
 * Liquid-glass surface.
 *
 * A true backdrop blur is not available to normal apps across all Android versions, so the glass
 * look is built from stacked translucent gradients + a light rim + a top specular highlight, drawn
 * over the vivid app background. The result reads as frosted glass on every device from API 24 up,
 * costs almost nothing and never depends on RenderEffect support.
 */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    strength: Float = 1f,
    showBorder: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    val dark = isDarkScheme()
    val factor = ((Prefs.glassIntensity / 100f).coerceIn(0.35f, 1f)) *
        if (Prefs.reduceEffects) 0.75f else 1f
    val tint = if (dark) Color.White else Color.White
    val topAlpha = (if (dark) 0.085f else 0.62f) * factor * strength
    val bottomAlpha = (if (dark) 0.028f else 0.42f) * factor * strength
    val borderBrush = Brush.verticalGradient(
        listOf(
            if (dark) Color.White.copy(alpha = 0.22f * factor) else Color.White.copy(alpha = 0.95f),
            if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f),
        ),
    )

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(tint.copy(alpha = topAlpha), tint.copy(alpha = bottomAlpha)),
                ),
            )
            .then(if (showBorder) Modifier.border(1.dp, borderBrush, shape) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Full screen background: deep gradient plus two soft colour glows. */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isDarkScheme()
    val background = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF060A11), Color(0xFF0A1322), Color(0xFF070B14)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFF2F6FD), Color(0xFFE8EEF9), Color(0xFFF5F8FD)))
    }
    Box(modifier.fillMaxSize().background(background)) {
        if (!Prefs.reduceEffects) {
            Canvas(Modifier.fillMaxSize()) {
                val cyan = Offset(size.width * 0.16f, size.height * 0.06f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x3322D3EE), Color(0x0022D3EE)),
                        center = cyan,
                        radius = size.width * 0.9f,
                    ),
                    center = cyan,
                    radius = size.width * 0.9f,
                )
                val violet = Offset(size.width * 0.95f, size.height * 0.28f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(Color(0x2E8B5CF6), Color(0x008B5CF6)),
                        center = violet,
                        radius = size.width * 0.8f,
                    ),
                    center = violet,
                    radius = size.width * 0.8f,
                )
            }
        }
        content()
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    GlassSurface(
        modifier = modifier.then(clickModifier),
        shape = shape,
        contentPadding = contentPadding,
    ) {
        Column(content = content)
    }
}

/**
 * Glass action button. `onClick` is deliberately the last parameter so call sites can use a
 * trailing lambda together with named arguments for icon/accent/enabled.
 */
@Composable
fun GlassButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    accent: Color? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(18.dp)
    val dark = isDarkScheme()
    val accentColor = accent ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(
                Brush.horizontalGradient(
                    listOf(
                        accentColor.copy(alpha = if (dark) 0.22f else 0.16f),
                        accentColor.copy(alpha = if (dark) 0.09f else 0.09f),
                    ),
                ),
            )
            .border(1.dp, accentColor.copy(alpha = if (enabled) 0.35f else 0.15f), shape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 18.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = accentColor, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) accentColor else accentColor.copy(alpha = 0.4f),
            )
        }
    }
}

/** Small glass chip used for tags and badges. */
@Composable
fun GlassChip(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
) {
    val color = accent ?: MaterialTheme.colorScheme.onSurfaceVariant
    GlassSurface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        strength = 0.9f,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

/** Circular glass action button (used for the create FAB). */
@Composable
fun GlassFab(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    Box(
        modifier = modifier
            .size(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(Brush.linearGradient(listOf(primary, secondary)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = Color(0xFF06202A))
    }
}