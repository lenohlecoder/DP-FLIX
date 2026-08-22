package com.dpflix.android.companion

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client HTTP des 5 endpoints publics codes / contenu du site compagnon.
 * OkHttp déjà dans le projet — pas de dépendance supplémentaire.
 *
 * URL de base : [CompanionConfig.BASE_URL] (modifiable en un seul endroit
 * pour basculer netlify dev → prod).
 */
class CompanionCodesApi(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()
) {
    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    suspend fun redeemCode(code: String, sessionId: String? = null): RedeemCodeResponse =
        withContext(Dispatchers.IO) {
        val payload = JSONObject().put("code", code)
        if (!sessionId.isNullOrBlank()) payload.put("sessionId", sessionId)
        val body = payload.toString().toRequestBody(jsonMedia)
        val request = Request.Builder()
            .url(CompanionConfig.REDEEM_CODE_URL)
            .post(body)
            .header("Accept", "application/json")
            .build()
        executeRedeemLike(request)
    }

    suspend fun codeStatus(code: String, sessionId: String? = null): CodeStatusResponse = withContext(Dispatchers.IO) {
        val url = CompanionConfig.codeStatusUrl(code, sessionId)
        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 429) {
                    return@withContext CodeStatusResponse(ok = false, error = "rate_limited")
                }
                if (!response.isSuccessful && raw.isBlank()) {
                    return@withContext CodeStatusResponse(ok = false, error = "http_${response.code}")
                }
                parseStatus(raw)
            }
        } catch (e: Exception) {
            CodeStatusResponse(ok = false, error = e.javaClass.simpleName)
        }
    }

    suspend fun getVideo(): GetVideoResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(CompanionConfig.GET_VIDEO_URL)
            .get()
            .header("Accept", "application/json")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful && raw.isBlank()) {
                    return@withContext GetVideoResponse(ok = false, error = "http_${response.code}")
                }
                val o = JSONObject(raw.ifBlank { "{}" })
                GetVideoResponse(
                    ok = o.optBoolean("ok", response.isSuccessful),
                    url = o.optString("url").takeIf { it.isNotBlank() },
                    videoVersion = o.optString("videoVersion").takeIf { it.isNotBlank() }
                        ?: o.optString("version").takeIf { it.isNotBlank() },
                    error = o.optString("error").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            GetVideoResponse(ok = false, error = e.javaClass.simpleName)
        }
    }

    suspend fun listInfos(): ListInfosResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(CompanionConfig.LIST_INFOS_URL)
            .get()
            .header("Accept", "application/json")
            .build()
        try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful && raw.isBlank()) {
                    return@withContext ListInfosResponse(ok = false, error = "http_${response.code}")
                }
                val o = JSONObject(raw.ifBlank { "{}" })
                val items = mutableListOf<InfoItem>()
                val arr: JSONArray = when {
                    o.has("items") -> o.optJSONArray("items") ?: JSONArray()
                    o.has("infos") -> o.optJSONArray("infos") ?: JSONArray()
                    else -> JSONArray()
                }
                for (i in 0 until arr.length()) {
                    val it = arr.optJSONObject(i) ?: continue
                    items.add(
                        InfoItem(
                            key = it.optString("key").takeIf { s -> s.isNotBlank() }
                                ?: it.optString("id").takeIf { s -> s.isNotBlank() },
                            title = it.optString("title").takeIf { s -> s.isNotBlank() },
                            body = it.optString("body").takeIf { s -> s.isNotBlank() }
                                ?: it.optString("texte").takeIf { s -> s.isNotBlank() },
                            imageKey = it.optString("imageKey").takeIf { s -> s.isNotBlank() }
                                ?: it.optString("image").takeIf { s -> s.isNotBlank() },
                            createdAt = it.optString("createdAt").takeIf { s -> s.isNotBlank() }
                        )
                    )
                }
                ListInfosResponse(
                    ok = o.optBoolean("ok", true),
                    items = items,
                    error = o.optString("error").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            ListInfosResponse(ok = false, error = e.javaClass.simpleName)
        }
    }

    /** URL absolue pour Coil : image servie par get-image?key=… */
    fun imageUrl(key: String): String = CompanionConfig.imageUrl(key)

    private fun executeRedeemLike(request: Request): RedeemCodeResponse {
        return try {
            httpClient.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (response.code == 429) {
                    return RedeemCodeResponse(ok = false, error = "rate_limited")
                }
                if (!response.isSuccessful && raw.isBlank()) {
                    return RedeemCodeResponse(ok = false, error = "http_${response.code}")
                }
                val o = JSONObject(raw.ifBlank { "{}" })
                RedeemCodeResponse(
                    ok = o.optBoolean("ok", false),
                    statut = o.optString("statut").takeIf { it.isNotBlank() },
                    expireLe = o.optString("expireLe").takeIf { it.isNotBlank() },
                    dureeJours = if (o.has("dureeJours") && !o.isNull("dureeJours")) o.optInt("dureeJours") else null,
                    sessionId = o.optString("sessionId").takeIf { it.isNotBlank() },
                    reason = o.optString("reason").takeIf { it.isNotBlank() },
                    error = o.optString("error").takeIf { it.isNotBlank() }
                )
            }
        } catch (e: Exception) {
            RedeemCodeResponse(ok = false, error = e.javaClass.simpleName)
        }
    }

    private fun parseStatus(raw: String): CodeStatusResponse {
        val o = JSONObject(raw.ifBlank { "{}" })
        return CodeStatusResponse(
            ok = o.optBoolean("ok", false),
            statut = o.optString("statut").takeIf { it.isNotBlank() },
            expireLe = o.optString("expireLe").takeIf { it.isNotBlank() },
            dureeJours = if (o.has("dureeJours") && !o.isNull("dureeJours")) o.optInt("dureeJours") else null,
            sessionActive = o.optBoolean("sessionActive", false),
            sessionAuthorized = if (o.has("sessionAuthorized") && !o.isNull("sessionAuthorized"))
                o.optBoolean("sessionAuthorized") else null,
            error = o.optString("error").takeIf { it.isNotBlank() }
        )
    }
}
