package com.dpflix.android.dreaming

import android.app.PendingIntent
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Polling léger pour les utilisateurs actuellement connectés à DP-Flix.
 * Intervalle par défaut : 60 s. Pour les notifications alors que l'app est complètement
 * arrêtée, utiliser FCM dans le projet hôte.
 */
class DreamingNotificationPoller(
    private val context: Context,
    private val repository: DreamingNotificationRepository,
    private val state: DreamingNotificationState = DreamingNotificationState(context),
    private val intervalMs: Long = 60_000L
) {
    private var job: Job? = null

    fun start(scope: CoroutineScope, contentIntent: PendingIntent? = null) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                runCatching {
                    val response = repository.fetch()
                    response.items
                        .filter { repository.isVisibleNow(it) }
                        .filterNot { state.isDismissed(it.id) }
                        .filterNot { state.isSystemNotified(it.id) }
                        .take(3)
                        .forEach {
                            DreamingNotificationManager.show(context, it, contentIntent)
                            state.markSystemNotified(it.id)
                        }
                }
                delay(intervalMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }
}
