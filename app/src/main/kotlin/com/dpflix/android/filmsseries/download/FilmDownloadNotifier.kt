package com.dpflix.android.filmsseries.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Étape 4 — notifications système pour progression / fin / erreur des téléchargements films.
 *
 * Canal dédié [CHANNEL_ID]. L'intent de clic ouvre l'activité principale
 * (extra [EXTRA_OPEN_DOWNLOADS] pour que le NavHost bascule sur Mes téléchargements).
 */
class FilmDownloadNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val nm = NotificationManagerCompat.from(appContext)

    init {
        ensureChannel()
    }

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Téléchargements films",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Progression et fin des téléchargements Films & Séries"
            setShowBadge(false)
        }
        val sys = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        sys.createNotificationChannel(channel)
    }

    fun notifyProgress(id: String, title: String, progressPercent: Int) {
        safeNotify(notifId(id), buildProgressNotification(title, progressPercent))
    }

    /**
     * Fix (12 août 2026) : notification de progression construite mais NON postée,
     * réutilisée par [com.dpflix.android.filmsseries.download.FilmDownloadWorker] pour
     * promouvoir le worker en vrai foreground service (`setForeground`). Les appels
     * suivants à [notifyProgress] (même [id], donc même ID de notification via [notifId])
     * continuent de mettre à jour cette même notification persistante.
     */
    fun buildProgressNotification(title: String, progressPercent: Int): android.app.Notification {
        return baseBuilder(title)
            .setContentText("Téléchargement… $progressPercent %")
            .setProgress(100, progressPercent.coerceIn(0, 100), progressPercent <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notifyCompleted(id: String, title: String) {
        val notification = baseBuilder(title)
            .setContentText("Téléchargement terminé — appuyez pour ouvrir")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(notifId(id), notification)
    }

    fun notifyFailed(id: String, title: String, message: String?) {
        val notification = baseBuilder(title)
            .setContentText(message?.take(80) ?: "Échec du téléchargement")
            .setProgress(0, 0, false)
            .setOngoing(false)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        safeNotify(notifId(id), notification)
    }

    fun cancel(id: String) {
        nm.cancel(notifId(id))
    }

    private fun baseBuilder(title: String): NotificationCompat.Builder {
        return NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { "Film" })
            .setContentIntent(openDownloadsPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
    }

    private fun openDownloadsPendingIntent(): PendingIntent {
        val launch = appContext.packageManager.getLaunchIntentForPackage(appContext.packageName)
            ?: Intent().apply { setPackage(appContext.packageName) }
        launch.putExtra(EXTRA_OPEN_DOWNLOADS, true)
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getActivity(appContext, REQUEST_OPEN_DOWNLOADS, launch, flags)
    }

    private fun safeNotify(id: Int, notification: android.app.Notification) {
        try {
            nm.notify(id, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS non accordé (Android 13+) — silencieux
        }
    }

    fun notifId(downloadId: String): Int =
        (NOTIF_BASE xor downloadId.hashCode()) and 0x7FFFFFFF

    companion object {
        const val CHANNEL_ID = "film_downloads"
        const val EXTRA_OPEN_DOWNLOADS = "com.dpflix.android.OPEN_FILM_DOWNLOADS"
        private const val REQUEST_OPEN_DOWNLOADS = 41001
        // Fix (12 août 2026, build CI) : 0xF11D_0000 dépasse Int.MAX_VALUE, donc Kotlin
        // l'inférait comme Long — `xor` avec le Int de hashCode() ne compile pas
        // (Return/Argument type mismatch). Même motif binaire (0xF11D0000), exprimé comme
        // Int signé natif via son complément à deux : compile et donne des ID identiques.
        private const val NOTIF_BASE: Int = -0x0EE30000
    }
}
