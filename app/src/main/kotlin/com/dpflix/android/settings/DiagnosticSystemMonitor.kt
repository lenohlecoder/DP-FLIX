package com.dpflix.android.settings

import android.content.Context
import android.webkit.WebResourceRequest
import java.io.File
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Interceptor

/**
 * Diagnostic système temporaire DP-FLIX.
 *
 * - Désactivé par défaut.
 * - Une session dure au maximum 10 minutes.
 * - Aucune collecte n'est faite hors session.
 * - À la fin, la surveillance s'arrête automatiquement et un rapport est écrit localement.
 * - Les cookies/tokens/mots de passe/Authorization et query strings ne sont jamais stockés.
 *
 * Le moteur est volontairement générique : les couches WebView, téléchargement, lecteur et
 * réseau appellent les méthodes record* lorsqu'une session est active.
 */
object DiagnosticSystemMonitor {
    private const val SESSION_DURATION_MS = 10 * 60 * 1000L
    private const val TICK_MS = 1_000L
    private const val MAX_EVENTS = 2_000
    private const val REPORT_FILE = "diagnostic_system_report.txt"

    enum class Status { SUCCESS, WARNING, ERROR }
    enum class Severity { INFO, WARNING, CRITICAL }

    data class Event(
        val timestampMillis: Long,
        val area: String,
        val action: String,
        val status: Status,
        val detail: String,
        val cause: String? = null,
        val severity: Severity = when (status) {
            Status.SUCCESS -> Severity.INFO
            Status.WARNING -> Severity.WARNING
            Status.ERROR -> Severity.CRITICAL
        }
    )

    data class State(
        val active: Boolean = false,
        val remainingMillis: Long = 0L,
        val actions: Int = 0,
        val successes: Int = 0,
        val warnings: Int = 0,
        val errors: Int = 0,
        val report: String? = null,
        val lastEvent: Event? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<Event>()
    private val running = AtomicBoolean(false)
    private var timerJob: Job? = null
    private var appContext: Context? = null
    private var endAtElapsedRealtime = 0L
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Synchronized
    fun initialize(context: Context) {
        if (appContext == null) appContext = context.applicationContext
        if (_state.value.report == null) {
            _state.value = _state.value.copy(report = readReport())
        }
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false
        events.clear()
        endAtElapsedRealtime = android.os.SystemClock.elapsedRealtime() + SESSION_DURATION_MS
        _state.value = State(active = true, remainingMillis = SESSION_DURATION_MS)
        record("Diagnostic système", "Démarrage", Status.SUCCESS, "Analyse de 10 minutes activée")
        timerJob?.cancel()
        timerJob = scope.launch {
            while (running.get()) {
                val remaining = (endAtElapsedRealtime - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                _state.value = buildState(remaining)
                if (remaining == 0L) {
                    stopInternal("Fin automatique après 10 minutes")
                    break
                }
                delay(TICK_MS)
            }
        }
        return true
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        timerJob?.cancel()
        timerJob = null
        finalizeSession("Analyse arrêtée manuellement")
    }

    /** Indique si l'instrumentation est réellement active. */
    fun isRunning(): Boolean = running.get()

    /** Enregistre une action uniquement pendant la session active. */
    fun record(
        area: String,
        action: String,
        status: Status,
        detail: String,
        cause: String? = null,
        severity: Severity = when (status) {
            Status.SUCCESS -> Severity.INFO
            Status.WARNING -> Severity.WARNING
            Status.ERROR -> Severity.CRITICAL
        }
    ) {
        if (!running.get()) return
        val event = Event(
            timestampMillis = System.currentTimeMillis(),
            area = sanitizeText(area),
            action = sanitizeText(action),
            status = status,
            detail = sanitizeText(detail),
            cause = cause?.let(::sanitizeText),
            severity = severity
        )
        if (events.size >= MAX_EVENTS) {
            val firstNonError = events.indexOfFirst { it.status == Status.SUCCESS }
            events.removeAt(if (firstNonError >= 0) firstNonError else 0)
        }
        events.add(event)
        _state.value = buildState(
            remaining = (endAtElapsedRealtime - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(0L),
            last = event
        )
    }

    fun recordHttp(
        area: String,
        action: String,
        code: Int,
        url: String,
        userAgentPresent: Boolean? = null,
        cookiesPresent: Boolean? = null,
        contentType: String? = null
    ) {
        val status = when {
            code in 200..299 -> Status.SUCCESS
            code in 300..399 -> Status.WARNING
            else -> Status.ERROR
        }
        val cause = when (code) {
            401 -> "Authentification ou session requise/refusée."
            403 -> "Page/requête refusée par le serveur (HTTP 403)."
            404 -> "Ressource introuvable (HTTP 404)."
            408, 504 -> "Délai d'attente réseau dépassé."
            in 500..599 -> "Erreur côté serveur (HTTP $code)."
            else -> null
        }
        val details = buildString {
            append("HTTP $code · ${sanitizeUrl(url)}")
            contentType?.let { append(" · Content-Type=$it") }
            userAgentPresent?.let { append(if (it) " · User-Agent présent" else " · User-Agent absent") }
            cookiesPresent?.let { append(if (it) " · cookies présents" else " · cookies absents") }
        }
        record(area, action, status, details, cause)
    }

    fun recordWebViewRequest(
        area: String,
        request: WebResourceRequest,
        userAgentPresent: Boolean?,
        cookieHeaderPresent: Boolean?
    ) {
        record(
            area = area,
            action = "Requête WebView",
            status = Status.SUCCESS,
            detail = "${request.method} ${sanitizeUrl(request.url.toString())}" +
                " · User-Agent ${if (userAgentPresent == true) "présent" else "absent/non observé"}" +
                " · cookies ${if (cookieHeaderPresent == true) "présents" else "absents/non observés"}",
            cause = null
        )
    }

    fun recordDownload(
        action: String,
        status: Status,
        detail: String,
        cause: String? = null
    ) = record("Téléchargements", action, status, detail, cause)

    fun recordPlayback(
        action: String,
        status: Status,
        detail: String,
        cause: String? = null
    ) = record("Lecture", action, status, detail, cause)

    fun recordException(area: String, action: String, throwable: Throwable) {
        val cause = when (throwable) {
            is SocketTimeoutException -> "Délai d'attente réseau dépassé."
            is UnknownHostException -> "Hôte/DNS inaccessible."
            is java.io.FileNotFoundException -> "Fichier local introuvable."
            is java.io.IOException -> "Erreur d'entrée/sortie réseau ou fichier."
            else -> throwable.javaClass.simpleName
        }
        record(area, action, Status.ERROR, throwable.message?.take(500) ?: cause, cause)
    }

    /** Intercepteur OkHttp : surveillance passive de tous les clients qui l'ajoutent. */
    val okHttpInterceptor: Interceptor = Interceptor { chain ->
        val request = chain.request()
        val ua = request.header("User-Agent")?.isNotBlank() == true
        val cookie = request.header("Cookie")?.isNotBlank() == true
        try {
            val response = chain.proceed(request)
            recordHttp(
                area = "Réseau",
                action = "Requête HTTP ${request.method}",
                code = response.code,
                url = request.url.toString(),
                userAgentPresent = ua,
                cookiesPresent = cookie,
                contentType = response.header("Content-Type")
            )
            response
        } catch (t: Throwable) {
            recordException("Réseau", "Requête HTTP ${request.method}", t)
            throw t
        }
    }

    fun clearReport() {
        if (running.get()) return
        events.clear()
        appContext?.let { runCatching { File(it.filesDir, REPORT_FILE).delete() } }
        _state.value = State()
    }

    private fun stopInternal(reason: String) {
        if (!running.compareAndSet(true, false)) return
        timerJob = null
        finalizeSession(reason)
    }

    private fun buildState(remaining: Long, last: Event? = events.lastOrNull()): State = State(
        active = running.get(),
        remainingMillis = remaining,
        actions = events.size,
        successes = events.count { it.status == Status.SUCCESS },
        warnings = events.count { it.status == Status.WARNING },
        errors = events.count { it.status == Status.ERROR },
        report = _state.value.report,
        lastEvent = last
    )

    @Synchronized
    private fun finalizeSession(reason: String) {
        val marker = Event(
            timestampMillis = System.currentTimeMillis(),
            area = "Diagnostic système",
            action = reason,
            status = Status.SUCCESS,
            detail = reason
        )
        if (events.size >= MAX_EVENTS) {
            val firstNonError = events.indexOfFirst { it.status == Status.SUCCESS }
            events.removeAt(if (firstNonError >= 0) firstNonError else 0)
        }
        events.add(marker)
        val report = generateReport(reason, events.toList())
        writeReport(report)
        _state.value = State(
            active = false,
            remainingMillis = 0L,
            actions = events.size,
            successes = events.count { it.status == Status.SUCCESS },
            warnings = events.count { it.status == Status.WARNING },
            errors = events.count { it.status == Status.ERROR },
            report = report,
            lastEvent = events.lastOrNull()
        )
    }

    private fun generateReport(reason: String, snapshot: List<Event>): String {
        val formatter = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
        val started = snapshot.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        return buildString {
            appendLine("DP-FLIX — DIAGNOSTIC SYSTÈME")
            appendLine("Durée maximale : 10 minutes")
            appendLine("Début : ${formatter.format(Date(started))}")
            appendLine("Fin : ${formatter.format(Date())}")
            appendLine("Motif d'arrêt : $reason")
            appendLine("Actions observées : ${snapshot.size}")
            appendLine("Réussites : ${snapshot.count { it.status == Status.SUCCESS }}")
            appendLine("Avertissements : ${snapshot.count { it.status == Status.WARNING }}")
            appendLine("Erreurs : ${snapshot.count { it.status == Status.ERROR }}")
            appendLine()
            snapshot.forEachIndexed { index, event ->
                appendLine("${index + 1}. [${formatter.format(Date(event.timestampMillis))}] ${event.area}")
                appendLine("   Action : ${event.action}")
                appendLine("   État : ${event.status}")
                appendLine("   Gravité : ${event.severity}")
                appendLine("   Détails : ${event.detail}")
                event.cause?.let { appendLine("   Cause identifiée/probable : $it") }
                appendLine()
            }
            appendLine("Confidentialité : les valeurs complètes de cookies, tokens, mots de passe, Authorization, clés API et query strings ne sont pas conservées.")
        }
    }

    private fun writeReport(report: String) {
        appContext?.let { runCatching { File(it.filesDir, REPORT_FILE).writeText(report) } }
    }

    private fun readReport(): String? = appContext?.let {
        runCatching { File(it.filesDir, REPORT_FILE).takeIf(File::exists)?.readText() }.getOrNull()
    }

    private fun sanitizeUrl(raw: String): String = runCatching {
        val uri = URI(raw)
        buildString {
            append(uri.scheme ?: "")
            append("://")
            append(uri.host ?: "")
            uri.path?.takeIf { it.isNotBlank() }?.let { append(it.take(240)) }
        }
    }.getOrElse { "URL masquée" }

    private fun sanitizeText(value: String): String = value
        .replace(
            Regex("(?i)(authorization|cookie|set-cookie|token|password|passwd|api[_-]?key)\\s*[:=]\\s*[^;\\s]+"),
            "$1=[masqué]"
        )
        .replace(Regex("https?://[^\\s?]+\\?[^\\s]+"), "[URL avec paramètres masquée]")
        .take(1500)
}
