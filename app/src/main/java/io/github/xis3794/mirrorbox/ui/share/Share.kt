package io.github.xis3794.mirrorbox.ui.share

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

fun shareFile(context: Context, file: File, mime: String = "*/*") {
    try {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享 ${file.name}"))
    } catch (t: Throwable) {
        // Ignore: sharing is a convenience action.
    }
}