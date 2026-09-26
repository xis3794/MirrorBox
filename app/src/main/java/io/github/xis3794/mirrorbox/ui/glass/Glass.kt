package io.github.xis3794.mirrorbox.ui.glass

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.core.Prefs
import io.github.xis3794.mirrorbox.ui.theme.isDarkScheme

/**
 * Liquid-glass surface.
 *
 * A true backdrop blur is not available to normal apps across all Android versions, so the glass
 * look is built from stacked translucent gradients + a light rim drawn over the app background.
 *
 * 性能说明（重要）：
 *  - 所有 Brush 都用 `remember` 缓存，绝不在绘制回调里创建（旧版每帧新建 radial shader 是卡顿主因）
 *  - 「性能模式」下退化成**一次**纯色绘制：无渐变、无边框、无额外图层
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
    val perf = Prefs.performanceMode
    val dark = isDarkScheme()
    val factor = ((Prefs.glassIntensity / 100f).coerceIn(0.35f, 1f)) * if (perf) 0.75f else 1f

    if (perf) {
        // 单层纯色卡片：足以区分层次，GPU 成本接近 0。
        // 浅色下背景用 #EFF3FA、卡片用纯白，保证"卡片能看出来"。
        val flat = if (dark) Color(0xFF161F2C) else Color(0xFFFFFFFF)
        val hairline = if (dark) Color(0x1FFFFFFF) else Color(0x14000000)
        Box(
            modifier = modifier
                .clip(shape)
                .background(flat)
                .then(if (showBorder) Modifier.border(1.dp, hairline, shape) else Modifier)
                .padding(contentPadding),
            content = content,
        )
        return
    }

    val tint = Color.White
    val topAlpha = (if (dark) 0.085f else 0.62f) * factor * strength
    val bottomAlpha = (if (dark) 0.028f else 0.42f) * factor * strength
    val fill = remember(topAlpha, bottomAlpha, tint) {
        Brush.verticalGradient(listOf(tint.copy(alpha = topAlpha), tint.copy(alpha = bottomAlpha)))
    }
    val borderBrush = remember(dark, factor) {
        Brush.verticalGradient(
            listOf(
                if (dark) Color.White.copy(alpha = 0.22f * factor) else Color.White.copy(alpha = 0.95f),
                if (dark) Color.White.copy(alpha = 0.05f) else Color.White.copy(alpha = 0.45f),
            ),
        )
    }

    Box(
        modifier = modifier
            .clip(shape)
            .background(fill)
            .then(if (showBorder) Modifier.border(1.dp, borderBrush, shape) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/**
 * Full screen background.
 *
 * 之前这里用一个全屏 Canvas，并在**绘制回调内部**构造两个 radial brush —— 每帧都会重新生成
 * shader 且要填充两块巨大的渐变区域，是滚动卡顿的最大来源。现在：
 *  - 性能模式：单一纯色
 *  - 正常模式：一个静态线性渐变 + 两个静态 radial 层（Brush 在组合期创建一次）
 */
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val dark = isDarkScheme()
    if (Prefs.performanceMode) {
        val flat = if (dark) Color(0xFF0B111A) else Color(0xFFEFF3FA)
        Box(modifier.fillMaxSize().background(flat)) { content() }
        return
    }

    val base = if (dark) {
        Brush.linearGradient(listOf(Color(0xFF060A11), Color(0xFF0A1322), Color(0xFF070B14)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFF2F6FD), Color(0xFFE8EEF9), Color(0xFFF5F8FD)))
    }
    val glowCyan = remember {
        Brush.radialGradient(
            colors = listOf(Color(0x3322D3EE), Color(0x0022D3EE)),
            center = Offset.Unspecified,
            radius = 900f,
        )
    }
    val glowViolet = remember {
        Brush.radialGradient(
            colors = listOf(Color(0x2E8B5CF6), Color(0x008B5CF6)),
            center = Offset.Unspecified,
            radius = 800f,
        )
    }
    Box(modifier.fillMaxSize().background(base)) {
        Box(
            Modifier
                .fillMaxSize()
                .align(Alignment.TopStart)
                .graphicsLayer { translationX = -size.width * 0.35f; translationY = -size.height * 0.35f }
                .background(glowCyan),
        )
        Box(
            Modifier
                .fillMaxSize()
                .align(Alignment.CenterEnd)
                .graphicsLayer { translationX = size.width * 0.35f }
                .background(glowViolet),
        )
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
    val perf = Prefs.performanceMode
    val accentColor = accent ?: MaterialTheme.colorScheme.primary
    val fill = remember(accentColor, dark, perf) {
        if (perf) {
            androidx.compose.ui.graphics.SolidColor(accentColor.copy(alpha = if (dark) 0.18f else 0.14f))
        } else {
            Brush.horizontalGradient(
                listOf(
                    accentColor.copy(alpha = if (dark) 0.22f else 0.16f),
                    accentColor.copy(alpha = if (dark) 0.09f else 0.09f),
                ),
            )
        }
    }
    Box(
        modifier = modifier
            .height(46.dp)
            .clip(shape)
            .background(fill)
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

/**
 * Circular glass action button (used for the create FAB).
 *
 * 可读性做过专门处理：实心主色渐变 + 白色图标 + 外圈描边 + 阴影，避免之前
 * "渐变底色 + 深色图标 + 无阴影" 导致按钮糊在背景和底栏上。
 */
@Composable
fun GlassFab(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    label: String? = null,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val shape = RoundedCornerShape(30.dp)
    val fill = remember(primary, secondary) { Brush.linearGradient(listOf(primary, secondary)) }
    val onFill = remember(primary) {
        // 主色偏亮时用深色图标，否则用白 —— 保证对比度。
        if (primary.luminance() > 0.55f) Color(0xFF06202A) else Color.White
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = modifier
                .size(60.dp)
                .shadow(10.dp, shape)
                .clip(shape)
                .background(fill)
                .border(2.dp, Color.White.copy(alpha = 0.35f), shape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = contentDescription,
                tint = onFill,
                modifier = Modifier.size(28.dp),
            )
        }
        if (label != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

/**
 * 当前主题下背景的代表色。
 *
 * 用于底部淡出遮罩：之前用黑色渐变，在浅色主题下会显得"下面黑一块"，很难看；
 * 用背景色渐隐就自然多了。
 */
@Composable
fun appBackgroundColor(): Color {
    val dark = isDarkScheme()
    return if (Prefs.performanceMode) {
        if (dark) Color(0xFF0B111A) else Color(0xFFEFF3FA)
    } else {
        if (dark) Color(0xFF080D16) else Color(0xFFF2F6FD)
    }
}