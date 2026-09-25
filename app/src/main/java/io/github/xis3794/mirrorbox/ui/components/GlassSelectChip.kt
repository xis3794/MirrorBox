package io.github.xis3794.mirrorbox.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.xis3794.mirrorbox.ui.glass.GlassSurface

/** Selectable glass chip used by the creation / conversion wizards. */
@Composable
fun GlassSelectChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = MaterialTheme.colorScheme.primary
    GlassSurface(
        modifier = modifier.clickable(onClick = onClick),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
        strength = if (selected) 1.15f else 0.85f,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}