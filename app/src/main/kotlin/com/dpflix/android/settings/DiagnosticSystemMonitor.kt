package com.dpflix.android.settings

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob

/**
 * Diagnostic système temporaire et process-wide.
 *
 * Il est volontairement inactif par défaut. Aucun événement n'est conservé quand une
 * session n'est pas active. Une session dure au maximum 10 minutes, puis est finalisée
 * automatiquement en rapport texte local. Les secrets (cookies, tokens, mots de passe,
 * URLs complètes avec query) ne sont jamais écrits dans le rapport.
 */
object DiagnosticSystemMonitor {
    private const val SESSION_DURATION_MS = 10 * 60 * 1000L
    private const val TICK_MS = 1_000L
    private const val REPORT_FILE = "diagnostic_system_report.txt"
    private const val MAX_EVENTS = 1000

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
            Status.ERROR -> Severity.CRITICAL
            Status.WARNING -> Severity.WARNING
            Status.SUCCESS -> Severity.INFO
        }
    )

    data class State(
        val active: Boolean = false,
        val remainingMillis: Long = 0L,
        val actions: Int = 0,
        val warnings: Int = 0,
        val errors: Int = 0,
        val report: String? = null,
        val lastEvent: Event? = null
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<Event>()
    private val active = AtomicBoolean(false)
    private var timerJob: Job? = null
    private var context: Context? = null
    private var endAtElapsedRealtime = 0L
    private val _state = MutableStateFlow(State(report = null))
    val state: StateFlow<State> = _state.asStateFlow()

    @Synchronized
    fun initialize(appContext: Context) {
        if (context == null) context = appContext.applicationContext
        if (_state.value.report == null) {
            _state.value = _state.value.copy(report = readReport())
        }
    }

    fun start(): Boolean {
        if (!active.compareAndSet(false, true)) return false
        events.clear()
        endAtElapsedRealtime = SystemClock.elapsedRealtime() + SESSION_DURATION_MS
        _state.value = State(active = true, remainingMillis = SESSION_DURATION_MS)
        record("Diagnostic", "Démarrage de l'analyse système", Status.SUCCESS, "Session de 10 minutes activée")
        timerJob?.cancel()
        timerJob = scope.launch {
            while (active.get()) {
                val remaining = max(0L, endAtElapsedRealtime - SystemClock.elapsedRealtime())
                _state.value = buildState(active = true, remaining = remaining)
                if (remaining <= 0L) {
                    finalizeSession("Fin automatique après 10 minutes")
                    break
                }
                delay(TICK_MS)
            }
        }
        return true
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        timerJob?.cancel()
        finalizeSession("Analyse arrêtée manuellement")
    }

    /** Enregistre un événement uniquement pendant une session active. */
    fun record(
        area: String,
        action: String,
        status: Status,
        detail: String,
        cause: String? = null,
        severity: Severity = when (status) {
            Status.ERROR -> Severity.CRITICAL
            Status.WARNING -> Severity.WARNING
            Status.SUCCESS -> Severity.INFO
        }
    ) {
        if (!active.get()) return
        val safeEvent = Event(
            timestampMillis = System.currentTimeMillis(),
            area = sanitizeText(area),
            action = sanitizeText(action),
            status = status,
            detail = sanitizeText(detail),
            cause = cause?.let(::sanitizeText),
            severity = severity
        )
        if (events.size >= MAX_EVENTS) events.removeAt(0)
        events.add(safeEvent)
        _state.value = buildState(active = true, remaining = max(0L, endAtElapsedRealtime - SystemClock.elapsedRealtime()), last = safeEvent)
    }

    /** Réseau : conserve uniquement des indicateurs techniques, jamais les valeurs sensibles. */
    fun recordHttp(
        area: String,
        action: String,
        code: Int,
        url: String,
        userAgentPresent: Boolean? = null,
        cookiesPresent: Boolean? = null,
        contentType: String? = null
    ) {
        if (!active.get()) return
        val hostPath = sanitizeUrl(url)
        val status = when {
            code in 200..299 -> Status.SUCCESS
            code in 300..399 -> Status.WARNING
            else -> Status.ERROR
        }
        val cause = when (code) {
            401 -> "Authentification/session requise ou refusée."
            403 -> "Serveur ayant refusé la requête (HTTP 403)."
            404 -> "Ressource introuvable (HTTP 404)."
            408, 504 -> "Délai d'attente réseau dépassé."
            in 500..599 -> "Erreur côté serveur (HTTP $code)."
            else -> null
        }
        val details = buildString {
            append("HTTP $code · $hostPath")
            contentType?.let { append(" · Content-Type=$it") }
            userAgentPresent?.let { append(if (it) " · User-Agent présent" else " · User-Agent absent") }
            cookiesPresent?.let { append(if (it) " · cookies disponibles" else " · cookies absents") }
        }
        record(area, action, status, details, cause)
    }

    fun recordException(area: String, action: String, throwable: Throwable) {
        val cause = when {
            throwable is java.net.SocketTimeoutException -> "Délai d'attente réseau dépassé."
            throwable is java.net.UnknownHostException -> "Hôte/DNS inaccessible."
            throwable is java.io.IOException -> "Erreur d'entrée/sortie réseau ou fichier."
            else -> throwable.javaClass.simpleName
        }
        record(area, action, Status.ERROR, cause, cause)
    }

    fun clearReport() {
        context?.let { runCatching { File(it.filesDir, REPORT_FILE).delete() } }
        _state.value = _state.value.copy(report = null)
    }

    private fun buildState(active: Boolean, remaining: Long, last: Event? = events.lastOrNull()): State {
        return State(
            active = active,
            remainingMillis = remaining,
            actions = events.size,
            warnings = events.count { it.status == Status.WARNING },
            errors = events.count { it.status == Status.ERROR },
            report = _state.value.report,
            lastEvent = last
        )
    }

    @Synchronized
    private fun finalizeSession(reason: String) {
        if (events.none { it.action == reason }) {
            // The finalization marker is deliberately not added through record(), because
            // active is already false at this point.
            val marker = Event(System.currentTimeMillis(), "Diagnostic", reason, Status.SUCCESS, reason)
            if (events.size >= MAX_EVENTS) events.removeAt(0)
            events.add(marker)
        }
        val report = generateReport(reason, events.toList())
        writeReport(report)
        _state.value = State(
            active = false,
            remainingMillis = 0L,
            actions = events.size,
            warnings = events.count { it.status == Status.WARNING },
            errors = events.count { it.status == Status.ERROR },
            report = report,
            lastEvent = events.lastOrNull()
        )
    }

    private fun generateReport(reason: String, snapshot: List<Event>): String {
        val format = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE)
        val started = snapshot.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        return buildString {
            appendLine("Diagnostic système — Rapport")
            appendLine("Durée maximale : 10 minutes")
            appendLine("Début : ${format.format(Date(started))}")
            appendLine("Fin : ${format.format(Date())}")
            appendLine("Motif d'arrêt : $reason")
            appendLine("Actions observées : ${snapshot.size}")
            appendLine("Actions réussies : ${snapshot.count { it.status == Status.SUCCESS }}")
            appendLine("Avertissements : ${snapshot.count { it.status == Status.WARNING }}")
            appendLine("Erreurs : ${snapshot.count { it.status == Status.ERROR }}")
            appendLine()
            if (snapshot.isEmpty()) {
                appendLine("Aucun événement n'a été observé pendant la session.")
            } else {
                snapshot.forEachIndexed { index, event ->
                    appendLine("${index + 1}. [${format.format(Date(event.timestampMillis))}] ${event.area}")
                    appendLine("   Action : ${event.action}")
                    appendLine("   Niveau : ${event.severity}")
                    appendLine("   État : ${event.status}")
                    appendLine("   Détails : ${event.detail}")
                    event.cause?.let { appendLine("   Cause : $it") }
                    appendLine()
                }
            }
            appendLine("Confidentialité : aucune valeur complète de cookie, token, mot de passe ou query d'URL n'est conservée dans ce rapport.")
        }
    }

    private fun writeReport(report: String) {
        context?.let { runCatching { File(it.filesDir, REPORT_FILE).writeText(report) } }
    }

    private fun readReport(): String? = context?.let {
        runCatching { File(it.filesDir, REPORT_FILE).takeIf(File::exists)?.readText() }.getOrNull()
    }

    private fun sanitizeUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            buildString {
                append(uri.scheme ?: "")
                append("://")
                append(uri.host ?: "")
                uri.path?.takeIf { it.isNotBlank() }?.let { append(it.take(160)) }
            }
        }.getOrElse { "URL masquée" }
    }

    private fun sanitizeText(value: String): String {
        return value
            .replace(Regex("(?i)(authorization|cookie|set-cookie|token|password|passwd|api[_-]?key)\\s*[:=]\\s*[^;\\s]+"), "$1=[masqué]")
            .replace(Regex("https?://[^\\s?]+\\?[^\\s]+"), "[URL avec paramètres masquée]")
            .take(1000)
    }
}
