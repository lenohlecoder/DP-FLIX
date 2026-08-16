package com.dpflix.android.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
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
                    // En-tête HTTP standard, envoyé par n'importe quel serveur web sans
                    // configuration particulière côté site — sert de source d'heure fiable
                    // pour le verrou local (voir CompanionStatus.serverTimeMs).
                    val serverTimeMs = parseHttpDateMs(response.header("Date"))
                    parseStatus(body, serverTimeMs)
                }
            }.getOrNull()
        }
    }

    private fun parseStatus(json: String, serverTimeMs: Long?): CompanionStatus {
        val o = JSONObject(json)
        return CompanionStatus(
            infosVersion = o.optInt("infosVersion", 0),
            infosCount = o.optInt("infosCount", 0),
            videoUrl = o.optString("videoUrl", ""),
            videoVersion = o.optInt("videoVersion", 0),
            updatedAt = o.optString("updatedAt", null).takeIf { !it.isNullOrBlank() },
            serverTimeMs = serverTimeMs
        )
    }

    /**
     * Parse un en-tête HTTP `Date` (format RFC 1123, ex. "Sat, 15 Aug 2026 10:03:00 GMT").
     * `SimpleDateFormat`/`java.text` plutôt que `java.time` : minSdk 23 du module, pas de
     * core library desugaring configurée — `java.time.*` (API 26+) n'est pas disponible.
     * Retourne `null` (jamais d'exception) si l'en-tête est absent ou dans un format
     * inattendu — l'appelant doit alors se rabattre sur l'heure locale de l'appareil.
     */
    private fun parseHttpDateMs(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.parse(headerValue)?.time
        }.getOrNull()
    }
}
