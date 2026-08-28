package com.dpflix.android.dreaming

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object DreamingNotificationManager {
    private const val CHANNEL_ID = "dpflix_dreaming"
    private const val CHANNEL_NAME = "Dreaming"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Annonces et programmes Dreaming de DP-Flix"
            }
        )
    }

    /**
     * Affiche une notification Android lorsque l'application est en mesure de la publier.
     * Pour un push lorsque l'application est complètement arrêtée, brancher FCM côté hôte
     * et appeler cette même méthode depuis le service de messagerie.
     */
    fun show(
        context: Context,
        item: DreamingNotification,
        contentIntent: PendingIntent? = null
    ) {
        ensureChannel(context)
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(item.titre.ifBlank { "DP-Flix" })
            .setContentText(item.texte.ifBlank { "Nouveau programme Dreaming" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(item.texte))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
        if (contentIntent != null) builder.setContentIntent(contentIntent)
        NotificationManagerCompat.from(context).notify(item.id.hashCode(), builder.build())
    }
}
