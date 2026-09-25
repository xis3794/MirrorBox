package io.github.xis3794.mirrorbox.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Domain icons drawn by hand so the app does not need the (large) material-icons-extended artifact.
 * Icon() applies the tint, therefore a plain black stroke is enough here.
 */
object MbIcons {

    val Disk: ImageVector by lazy {
        ImageVector.Builder(
            name = "MbDisk",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            ) {
                moveTo(4.5f, 7.5f); lineTo(19.5f, 7.5f)
                moveTo(4.5f, 12f); lineTo(19.5f, 12f)
                moveTo(4.5f, 16.5f); lineTo(19.5f, 16.5f)
            }
        }.build()
    }

    val Iso: ImageVector by lazy {
        ImageVector.Builder(
            name = "MbIso",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            ) {
                moveTo(12f, 3.5f); lineTo(20f, 12f); lineTo(12f, 20.5f); lineTo(4f, 12f); close()
                moveTo(12f, 9.5f); lineTo(14.5f, 12f); lineTo(12f, 14.5f); lineTo(9.5f, 12f); close()
            }
        }.build()
    }

    val Terminal: ImageVector by lazy {
        ImageVector.Builder(
            name = "MbTerminal",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            ) {
                moveTo(3.5f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
                moveTo(7f, 10f); lineTo(9.5f, 12.2f); lineTo(7f, 14.4f)
                moveTo(12.5f, 15f); lineTo(17f, 15f)
            }
        }.build()
    }

    val Grid: ImageVector by lazy {
        ImageVector.Builder(
            name = "MbGrid",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineJoin = StrokeJoin.Round,
                fill = null,
            ) {
                moveTo(3.5f, 3.5f); lineTo(10.5f, 3.5f); lineTo(10.5f, 10.5f); lineTo(3.5f, 10.5f); close()
                moveTo(13.5f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 10.5f); lineTo(13.5f, 10.5f); close()
                moveTo(3.5f, 13.5f); lineTo(10.5f, 13.5f); lineTo(10.5f, 20.5f); lineTo(3.5f, 20.5f); close()
                moveTo(13.5f, 13.5f); lineTo(20.5f, 13.5f); lineTo(20.5f, 20.5f); lineTo(13.5f, 20.5f); close()
            }
        }.build()
    }

    val Hex: ImageVector by lazy {
        ImageVector.Builder(
            name = "MbHex",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                fill = null,
            ) {
                moveTo(9.5f, 4f); lineTo(8f, 20f)
                moveTo(16f, 4f); lineTo(14.5f, 20f)
                moveTo(4.5f, 9f); lineTo(19.5f, 9f)
                moveTo(4f, 15f); lineTo(19f, 15f)
            }
        }.build()
    }

    val Partition: ImageVector by lazy {
        ImageVector.Builder(
            name = "MbPartition",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply {
            path(
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round,
                fill = null,
            ) {
                moveTo(3.5f, 20f); lineTo(20.5f, 20f)
                moveTo(5.5f, 20f); lineTo(5.5f, 9f)
                moveTo(10.5f, 20f); lineTo(10.5f, 5.5f)
                moveTo(15.5f, 20f); lineTo(15.5f, 12f)
                moveTo(20f, 20f); lineTo(20f, 14.5f)
            }
        }.build()
    }
}