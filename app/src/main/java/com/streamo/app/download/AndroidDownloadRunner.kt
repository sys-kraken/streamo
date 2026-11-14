package com.streamo.app.download

import android.content.Context
import android.net.Uri
import java.io.File

/** Android's [DownloadRunner]: downloads run in [DownloadService], files live in Movies/Music. */
class AndroidDownloadRunner(context: Context) : DownloadRunner {
    private val context = context.applicationContext

    override fun start() = DownloadService.start(context)

    override fun deleteFile(uri: String) {
        val parsed = Uri.parse(uri)
        when (parsed.scheme) {
            "content" -> context.contentResolver.delete(parsed, null, null)
            "file" -> parsed.path?.let { File(it).delete() }
        }
    }
}
