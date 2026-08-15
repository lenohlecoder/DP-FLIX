package com.dpflix.android.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Récupération du status compagnon (badge + vidéo d'accueil).
 * OkHttp déjà présent dans DP-Flix — pas de nouvelle dépendance.
 */
class CompanionRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CompanionConfig.STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CompanionConfig.STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CompanionConfig.STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()
) {
    /**
     * @return status ou `null` en cas d'échec réseau / timeout / JSON invalide.
     * Ne jette jamais : l'UI doit pouvoir fallback silencieusement.
     */
    suspend fun getStatus(): CompanionStatus? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(CompanionConfig.STATUS_TIMEOUT_MS) {
            runCatching {
                val request = Request.Builder()
                    .url(CompanionConfig.STATUS_URL)
                    .get()
                    .header("Accept", "application/json")
                    .build()
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@runCatching null
                    val body = response.body?.string() ?: return@runCatching null
                    parseStatus(body)
                }
            }.getOrNull()
        }
    }

    private fun parseStatus(json: String): CompanionStatus {
        val o = JSONObject(json)
        return CompanionStatus(
            infosVersion = o.optInt("infosVersion", 0),
            infosCount = o.optInt("infosCount", 0),
            videoUrl = o.optString("videoUrl", ""),
            videoVersion = o.optInt("videoVersion", 0),
            updatedAt = o.optString("updatedAt", null).takeIf { !it.isNullOrBlank() }
        )
    }
}
