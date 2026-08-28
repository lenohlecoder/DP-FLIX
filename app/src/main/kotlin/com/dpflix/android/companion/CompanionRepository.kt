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
import java.util.concurrent.atomic.AtomicReference

/**
 * Récupération du status compagnon (badge + vidéo d'accueil).
 * OkHttp déjà présent dans DP-Flix — pas de nouvelle dépendance.
 *
 * Cache court + [prefetchStartupMedia] : sur l'écran code d'accès, on résout déjà
 * `videoUrl` et on chauffe DNS/TLS vers l'hôte vidéo pour que [StartupVideoScreen]
 * n'attende pas le réseau « à froid » après le déverrouillage (surtout TV).
 */
class CompanionRepository(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(CompanionConfig.STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(CompanionConfig.STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .callTimeout(CompanionConfig.STATUS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .addInterceptor(com.dpflix.android.settings.DiagnosticSystemMonitor.okHttpInterceptor)
        .build()
) {
    private data class CachedStatus(val status: CompanionStatus, val atMs: Long)

    private val cache = AtomicReference<CachedStatus?>(null)

    /** Durée de validité du cache status (évite un 2e round-trip juste après le code). */
    private val cacheTtlMs = 120_000L

    /**
     * Status en cache s'il est encore frais, sinon `null`.
     * Utile pour [StartupVideoScreen] : pas d'attente si le préchargement a déjà tourné.
     */
    fun peekCachedStatus(): CompanionStatus? {
        val c = cache.get() ?: return null
        if (System.currentTimeMillis() - c.atMs > cacheTtlMs) return null
        return c.status
    }

    /**
     * @return status ou `null` en cas d'échec réseau / timeout / JSON invalide.
     * Ne jette jamais : l'UI doit pouvoir fallback silencieusement.
     */
    suspend fun getStatus(forceRefresh: Boolean = false): CompanionStatus? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            peekCachedStatus()?.let { return@withContext it }
        }
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
                    val serverTimeMs = parseHttpDateMs(response.header("Date"))
                    parseStatus(body, serverTimeMs)
                }
            }.getOrNull()
        }?.also { status ->
            cache.set(CachedStatus(status, System.currentTimeMillis()))
        }
    }

    /**
     * À lancer dès l'affichage de l'écran code (ou pendant la saisie) :
     * 1) résout `/api/status` → `videoUrl`
     * 2) ouvre une connexion HTTP vers l'URL vidéo (page ou MP4) pour DNS + TLS + éventuelle
     *    redirection, sans lire tout le corps.
     * Best-effort : aucune exception remontée.
     */
    suspend fun prefetchStartupMedia() = withContext(Dispatchers.IO) {
        val status = getStatus() ?: return@withContext
        val url = status.videoUrl.trim()
        if (url.isEmpty()) return@withContext
        warmUrl(url)
    }

    private fun warmUrl(url: String) {
        runCatching {
            // GET avec Range minimal : certains hôtes n'aiment pas HEAD ; on ferme dès les headers.
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Range", "bytes=0-0")
                .header("Accept", "*/*")
                .build()
            httpClient.newCall(request).execute().use { /* drain not needed */ }
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

    private fun parseHttpDateMs(headerValue: String?): Long? {
        if (headerValue.isNullOrBlank()) return null
        return runCatching {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.timeZone = TimeZone.getTimeZone("GMT")
            format.parse(headerValue)?.time
        }.getOrNull()
    }
}
