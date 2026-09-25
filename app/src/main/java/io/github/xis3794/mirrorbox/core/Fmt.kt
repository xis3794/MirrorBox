package io.github.xis3794.mirrorbox.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Formatting helpers (sizes, dates, sizes typed by humans). */
object Fmt {

    private val units = listOf("B", "KB", "MB", "GB", "TB", "PB")

    fun size(bytes: Long): String {
        if (bytes < 0) return "—"
        var value = bytes.toDouble()
        var index = 0
        while (value >= 1024.0 && index < units.size - 1) {
            value /= 1024.0
            index++
        }
        return if (index == 0) "$bytes B" else String.format(Locale.US, "%.2f %s", value, units[index])
    }

    fun dateTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))

    /**
     * Parses sizes like `10G`, `512M`, `1.5T`, `2048` (MiB by default when no unit given).
     * Returns bytes or null when unparsable.
     */
    fun parseSize(input: String, defaultUnit: Char = 'M'): Long? {
        val text = input.trim().uppercase(Locale.US).replace(" ", "")
        if (text.isEmpty()) return null
        val unit = text.last()
        val numberPart = if (unit.isDigit()) text else text.dropLast(1)
        val value = numberPart.toDoubleOrNull() ?: return null
        val multiplier = when (if (unit.isDigit()) defaultUnit else unit) {
            'B' -> 1L
            'K' -> 1024L
            'M' -> 1024L * 1024
            'G' -> 1024L * 1024 * 1024
            'T' -> 1024L * 1024 * 1024 * 1024
            else -> return null
        }
        val bytes = (value * multiplier).toLong()
        return if (bytes <= 0L) null else bytes
    }

    fun formatSizeInput(bytes: Long): String {
        val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) String.format(Locale.US, "%.0fG", gb) else String.format(Locale.US, "%.0fM", bytes.toDouble() / 1024.0 / 1024.0)
    }
}