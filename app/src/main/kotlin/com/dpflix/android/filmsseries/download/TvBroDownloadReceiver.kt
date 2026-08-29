package com.dpflix.android.filmsseries.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import com.dpflix.android.DpFlixApplication
import com.dpflix.android.filmsseries.stream.DetectedStream
import com.dpflix.android.filmsseries.stream.StreamSniffer
import com.dpflix.android.filmsseries.stream.StreamType

/**
 * Bridge entre le moteur de téléchargement TV Bro et la bibliothèque DP-FLIX.
 *
 * Le navigateur reste indépendant : il émet uniquement une demande de téléchargement.
 * Le traitement lourd (Room + WorkManager) reste dans FilmDownloadManager.
 */
class TvBroDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DPFLIX_DOWNLOAD) return

        val url = intent.getStringExtra("url")?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: return
        val referer = intent.getStringExtra("referer")
        val userAgent = intent.getStringExtra("user_agent")
        val mime = intent.getStringExtra("mime_type")
        val size = intent.getLongExtra("content_length", 0L).takeIf { it > 0L }
        val title = intent.getStringExtra("title")
        val type = StreamSniffer.classify(url, mime) ?: when {
            mime?.startsWith("video/", ignoreCase = true) == true -> StreamType.OTHER
            else -> StreamType.MP4
        }

        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val stream = DetectedStream(
                    url = url,
                    type = type,
                    mimeType = mime,
                    contentLength = size,
                    pageUrl = referer
                )
                (context.applicationContext as? DpFlixApplication)
                    ?.container?.appRepository?.filmDownloads
                    ?.enqueue(stream, title, userAgent)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DPFLIX_DOWNLOAD = "com.dpflix.android.ACTION_TV_BRO_DOWNLOAD"
    }
}
