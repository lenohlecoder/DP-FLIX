package com.dpflix.android.dreaming

import android.content.Context
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Accès public au flux Dreaming du site DP-Flix.
 *
 * Exemple :
 * val repo = DreamingNotificationRepository("https://mon-site.netlify.app")
 */
class DreamingNotificationRepository(
    baseUrl: String,
    private val client: OkHttpClient = OkHttpClient()
) {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(): DreamingNotificationResponse {
        val request = Request.Builder()
            .url("$base/.netlify/functions/list-notifications")
            .get()
            .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Dreaming HTTP ${response.code}")
            json.decodeFromString<DreamingNotificationResponse>(response.body?.string().orEmpty())
        }
    }

    fun imageUrl(key: String): String =
        "$base/.netlify/functions/get-image?key=${java.net.URLEncoder.encode(key, "UTF-8")}" 

    fun isVisibleNow(item: DreamingNotification, now: Instant = Instant.now()): Boolean {
        if (!item.active) return false
        val start = parseInstant(item.startAt) ?: return true
        val end = item.endAt?.let(::parseInstant)
        return !now.isBefore(start) && (end == null || now.isBefore(end))
    }

    private fun parseInstant(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        try { Instant.parse(value) } catch (_: Exception) { null }
    }
}

/** Persistance locale des notifications fermées par l'utilisateur. */
class DreamingNotificationState(context: Context) {
    private val prefs = context.getSharedPreferences("dpflix_dreaming", Context.MODE_PRIVATE)

    fun isDismissed(id: String): Boolean = prefs.getBoolean("dismissed_$id", false)

    fun dismiss(id: String) {
        prefs.edit().putBoolean("dismissed_$id", true).apply()
    }

    fun clearDismissed(id: String) {
        prefs.edit().remove("dismissed_$id").apply()
    }

    fun isSystemNotified(id: String): Boolean = prefs.getBoolean("notified_$id", false)

    fun markSystemNotified(id: String) {
        prefs.edit().putBoolean("notified_$id", true).apply()
    }
}
