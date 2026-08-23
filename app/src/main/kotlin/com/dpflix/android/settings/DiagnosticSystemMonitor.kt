package com.dpflix.android.settings

import android.content.Context
import android.os.SystemClock
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
import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Diagnostic système global temporaire de DP-FLIX.
 *
 * IMPORTANT :
 * - désactivé par défaut ;
 * - aucune collecte hors session ;
 * - une session dure au maximum 10 minutes ;
 * - à 00:00 la session s'arrête automatiquement et un rapport est généré ;
 * - l'utilisateur peut arrêter la session avant la fin ;
 * - les cookies, tokens, mots de passe, clés API et query strings ne sont jamais écrits.
 *
 * Ce fichier fournit le moteur de diagnostic. Pour obtenir une surveillance réellement
 * globale, les différents points d'exécution de l'application doivent appeler les méthodes
 * record*/recordHttp*/recordWebView*/recordDownload*/recordPlayback* ci-dessous.
 *
 * Le DiagnosticOkHttpInterceptor peut être ajouté aux clients OkHttp utilisés par l'app :
 *
 *   .addInterceptor(DiagnosticSystemMonitor.okHttpInterceptor)
 *
 * Il ne modifie jamais la requête réelle et ne fait aucune collecte lorsque le diagnostic
 * est désactivé.
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
    private var endAtElapsedRealtime: Long = 0L

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * À appeler une fois depuis Application.onCreate().
     * Ne démarre aucune surveillance.
     */
    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (_state.value.report == null) {
            _state.value = _state.value.copy(report = readReport())
        }
    }

    fun isActive(): Boolean = running.get()

    /**
     * Lance une nouvelle session de 10 minutes.
     * Retourne false si une session est déjà active.
     */
    @Synchronized
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false

        events.clear()
        endAtElapsedRealtime = SystemClock.elapsedRealtime() + SESSION_DURATION_MS

        _state.value = State(
            active = true,
            remainingMillis = SESSION_DURATION_MS
        )

        record(
            area = "Diagnostic système",
            action = "Démarrage de la session",
            status = Status.SUCCESS,
            detail = "Analyse globale activée pour 10 minutes."
        )

        timerJob?.cancel()
        timerJob = scope.launch {
            while (running.get()) {
                val remaining =
                    max(0L, endAtElapsedRealtime - SystemClock.elapsedRealtime())

                _state.value = buildState(
                    active = true,
                    remainingMillis = remaining
                )

                if (remaining <= 0L) {
                    finalizeSession("Fin automatique après 10 minutes")
                    break
                }

                delay(TICK_MS)
            }
        }

        return true
    }

    /**
     * Arrêt manuel. Le rapport est généré immédiatement.
     */
    @Synchronized
    fun stop() {
        if (!running.compareAndSet(true, false)) return

        timerJob?.cancel()
        timerJob = null
        finalizeSession("Arrêt manuel de l'analyse")
    }

    /**
     * Enregistre une action générale.
     *
     * Exemple :
     * record(
     *   area = "Films & Séries",
     *   action = "Chargement de la page",
     *   status = Status.ERROR,
     *   detail = "La page n'a pas fourni le contenu attendu.",
     *   cause = "Ressources principales absentes."
     * )
     */
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

        synchronized(events) {
            if (events.size >= MAX_EVENTS) {
                events.removeAt(0)
            }
            events.add(event)
        }

        _state.value = buildState(
            active = true,
            remainingMillis = max(
                0L,
                endAtElapsedRealtime - SystemClock.elapsedRealtime()
            ),
            lastEvent = event
        )
    }

    /**
     * Analyse une réponse HTTP déjà obtenue par l'application.
     *
     * La méthode essaie de distinguer une cause constatée d'une cause seulement probable.
     */
    fun recordHttp(
        area: String,
        action: String,
        code: Int,
        url: String,
        userAgentPresent: Boolean? = null,
        cookiesPresent: Boolean? = null,
        contentType: String? = null,
        expectedMimeType: String? = null,
        contentLength: Long? = null
    ) {
        if (!running.get()) return

        val status = when {
            code in 200..299 -> Status.SUCCESS
            code in 300..399 -> Status.WARNING
            else -> Status.ERROR
        }

        val causes = mutableListOf<String>()

        when (code) {
            401 -> causes += "Authentification ou session requise/refusée."
            403 -> causes += "Serveur ayant refusé la requête (HTTP 403)."
            404 -> causes += "Ressource introuvable (HTTP 404)."
            408, 504 -> causes += "Délai d'attente réseau dépassé."
            in 500..599 -> causes += "Erreur côté serveur (HTTP $code)."
        }

        if (userAgentPresent == false) {
            causes += "User-Agent absent."
        }

        if (cookiesPresent == false) {
            causes += "Cookies absents."
        }

        if (
            expectedMimeType != null &&
            contentType != null &&
            !contentType.contains(expectedMimeType, ignoreCase = true)
        ) {
            causes += "Type de contenu incompatible : reçu '$contentType', attendu '$expectedMimeType'."
        }

        if (contentLength == 0L) {
            causes += "Réponse vide."
        }

        val detail = buildString {
            append("HTTP $code · ${sanitizeUrl(url)}")
            contentType?.let { append(" · Content-Type=$it") }
            contentLength?.let { append(" · taille=${if (it >= 0) it else "inconnue"} octets") }
            userAgentPresent?.let {
                append(if (it) " · User-Agent présent" else " · User-Agent absent")
            }
            cookiesPresent?.let {
                append(if (it) " · cookies disponibles" else " · cookies absents")
            }
        }

        record(
            area = area,
            action = action,
            status = status,
            detail = detail,
            cause = causes.takeIf { it.isNotEmpty() }?.joinToString(" "),
            severity = when {
                code >= 500 || code == 403 || code == 401 -> Severity.CRITICAL
                status == Status.WARNING -> Severity.WARNING
                else -> Severity.INFO
            }
        )
    }

    /**
     * Analyse une exception réseau/fichier sans conserver de secret.
     */
    fun recordException(
        area: String,
        action: String,
        throwable: Throwable
    ) {
        if (!running.get()) return

        val cause = when (throwable) {
            is SocketTimeoutException ->
                "Délai d'attente réseau dépassé."
            is UnknownHostException ->
                "Hôte ou résolution DNS inaccessible."
            is IOException ->
                "Erreur d'entrée/sortie réseau ou fichier."
            else ->
                throwable.javaClass.simpleName
        }

        record(
            area = area,
            action = action,
            status = Status.ERROR,
            detail = throwable.message?.takeIf { it.isNotBlank() } ?: cause,
            cause = cause
        )
    }

    /**
     * Action WebView : permet de distinguer page refusée, page chargée mais vide,
     * ressource manquante ou erreur JavaScript.
     */
    fun recordWebView(
        action: String,
        url: String,
        httpCode: Int? = null,
        pageLoaded: Boolean,
        expectedContentPresent: Boolean? = null,
        userAgentPresent: Boolean? = null,
        cookiesPresent: Boolean? = null,
        javascriptError: String? = null,
        missingResources: Int? = null
    ) {
        if (!running.get()) return

        val causes = mutableListOf<String>()

        if (httpCode != null && httpCode !in 200..299) {
            causes += "Réponse HTTP $httpCode."
        }
        if (userAgentPresent == false) causes += "User-Agent absent."
        if (cookiesPresent == false) causes += "Cookies absents."
        if (expectedContentPresent == false) {
            causes += "Page chargée mais contenu attendu absent."
        }
        if ((missingResources ?: 0) > 0) {
            causes += "$missingResources ressource(s) de page non chargée(s)."
        }
        if (!javascriptError.isNullOrBlank()) {
            causes += "Erreur JavaScript détectée."
        }

        val status = when {
            httpCode != null && httpCode !in 200..299 -> Status.ERROR
            !pageLoaded -> Status.ERROR
            expectedContentPresent == false -> Status.ERROR
            !javascriptError.isNullOrBlank() -> Status.WARNING
            (missingResources ?: 0) > 0 -> Status.WARNING
            else -> Status.SUCCESS
        }

        record(
            area = "WebView",
            action = action,
            status = status,
            detail = "URL=${sanitizeUrl(url)} · pageChargée=$pageLoaded",
            cause = causes.takeIf { it.isNotEmpty() }?.joinToString(" ")
        )
    }

    /**
     * Diagnostic du système de téléchargement.
     */
    fun recordDownload(
        action: String,
        url: String,
        success: Boolean,
        contentType: String? = null,
        expectedMimeType: String? = null,
        fileExtension: String? = null,
        errorMessage: String? = null,
        userAgentPresent: Boolean? = null,
        cookiesPresent: Boolean? = null
    ) {
        if (!running.get()) return

        val causes = mutableListOf<String>()

        if (userAgentPresent == false) causes += "User-Agent absent."
        if (cookiesPresent == false) causes += "Cookies absents."

        if (
            expectedMimeType != null &&
            contentType != null &&
            !contentType.contains(expectedMimeType, ignoreCase = true)
        ) {
            causes +=
                "Incompatibilité du type de fichier : reçu '$contentType', attendu '$expectedMimeType'."
        }

        if (fileExtension.isNullOrBlank()) {
            causes += "Extension du fichier absente ou inconnue."
        }

        if (!errorMessage.isNullOrBlank()) {
            causes += sanitizeText(errorMessage)
        }

        record(
            area = "Téléchargements",
            action = action,
            status = if (success) Status.SUCCESS else Status.ERROR,
            detail = buildString {
                append("URL=${sanitizeUrl(url)}")
                contentType?.let { append(" · Content-Type=$it") }
                fileExtension?.let { append(" · extension=$it") }
            },
            cause = causes.takeIf { it.isNotEmpty() }?.joinToString(" ")
        )
    }

    /**
     * Diagnostic de lecture : flux, buffer, lecteur, direct, etc.
     */
    fun recordPlayback(
        action: String,
        success: Boolean,
        detail: String,
        cause: String? = null,
        severity: Severity = if (success) Severity.INFO else Severity.CRITICAL
    ) {
        record(
            area = "Lecture",
            action = action,
            status = if (success) Status.SUCCESS else Status.ERROR,
            detail = detail,
            cause = cause,
            severity = severity
        )
    }

    /**
     * Efface uniquement le rapport sauvegardé.
     * Si une session est active, elle continue.
     */
    fun clearReport() {
        appContext?.let {
            runCatching { File(it.filesDir, REPORT_FILE).delete() }
        }
        _state.value = _state.value.copy(report = null)
    }

    fun snapshot(): List<Event> = synchronized(events) { events.toList() }

    private fun buildState(
        active: Boolean,
        remainingMillis: Long,
        lastEvent: Event? = synchronized(events) { events.lastOrNull() }
    ): State {
        val snapshot = snapshot()
        return State(
            active = active,
            remainingMillis = remainingMillis,
            actions = snapshot.size,
            successes = snapshot.count { it.status == Status.SUCCESS },
            warnings = snapshot.count { it.status == Status.WARNING },
            errors = snapshot.count { it.status == Status.ERROR },
            report = _state.value.report,
            lastEvent = lastEvent
        )
    }

    @Synchronized
    private fun finalizeSession(reason: String) {
        val snapshot = snapshot()
        val finalReport = generateReport(reason, snapshot)
        writeReport(finalReport)

        _state.value = State(
            active = false,
            remainingMillis = 0L,
            actions = snapshot.size,
            successes = snapshot.count { it.status == Status.SUCCESS },
            warnings = snapshot.count { it.status == Status.WARNING },
            errors = snapshot.count { it.status == Status.ERROR },
            report = finalReport,
            lastEvent = snapshot.lastOrNull()
        )
    }

    private fun generateReport(
        reason: String,
        snapshot: List<Event>
    ): String {
        val formatter = SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss",
            Locale.FRANCE
        )

        val startedAt =
            snapshot.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()

        return buildString {
            appendLine("DP-FLIX — DIAGNOSTIC SYSTÈME")
            appendLine("Durée maximale : 10 minutes")
            appendLine("Début : ${formatter.format(Date(startedAt))}")
            appendLine("Fin : ${formatter.format(Date())}")
            appendLine("Arrêt : $reason")
            appendLine()
            appendLine("Actions observées : ${snapshot.size}")
            appendLine("Réussites : ${snapshot.count { it.status == Status.SUCCESS }}")
            appendLine("Avertissements : ${snapshot.count { it.status == Status.WARNING }}")
            appendLine("Erreurs : ${snapshot.count { it.status == Status.ERROR }}")
            appendLine()

            if (snapshot.isEmpty()) {
                appendLine("Aucune action observée pendant la session.")
            } else {
                snapshot.forEachIndexed { index, event ->
                    appendLine(
                        "${index + 1}. ${formatter.format(Date(event.timestampMillis))}"
                    )
                    appendLine("   Section : ${event.area}")
                    appendLine("   Action : ${event.action}")
                    appendLine("   État : ${event.status}")
                    appendLine("   Gravité : ${event.severity}")
                    appendLine("   Détails : ${event.detail}")
                    event.cause?.let {
                        appendLine("   Cause : $it")
                    }
                    appendLine()
                }
            }

            appendLine(
                "Confidentialité : cookies, tokens, mots de passe, clés API " +
                    "et paramètres de requête ne sont pas conservés."
            )
        }
    }

    private fun writeReport(report: String) {
        appContext?.let {
            runCatching {
                File(it.filesDir, REPORT_FILE).writeText(report)
            }
        }
    }

    private fun readReport(): String? =
        appContext?.let {
            runCatching {
                File(it.filesDir, REPORT_FILE)
                    .takeIf(File::exists)
                    ?.readText()
            }.getOrNull()
        }

    private fun sanitizeUrl(raw: String): String {
        return runCatching {
            val uri = URI(raw)
            buildString {
                append(uri.scheme ?: "")
                append("://")
                append(uri.host ?: "")
                uri.path
                    ?.takeIf { it.isNotBlank() }
                    ?.let { append(it.take(200)) }
            }
        }.getOrElse { "URL masquée" }
    }

    private fun sanitizeText(value: String): String {
        return value
            .replace(
                Regex(
                    "(?i)(authorization|cookie|set-cookie|token|password|passwd|api[_-]?key)" +
                        "\\s*[:=]\\s*[^;\\s]+"
                ),
                "$1=[masqué]"
            )
            .replace(
                Regex("https?://[^\\s?]+\\?[^\\s]+"),
                "[URL avec paramètres masquée]"
            )
            .take(1500)
    }

    /**
     * Intercepteur OkHttp optionnel.
     *
     * Il observe uniquement les requêtes exécutées pendant une session active.
     * Il ne bloque, ne réécrit et ne relance aucune requête.
     *
     * Il enregistre :
     * - code HTTP ;
     * - Content-Type ;
     * - présence du User-Agent ;
     * - présence d'un header Cookie ;
     * - taille de la réponse ;
     * - causes déductibles (403, 404, timeout, MIME incompatible, etc.).
     *
     * Les valeurs des headers sensibles ne sont jamais enregistrées.
     */
    val okHttpInterceptor: Interceptor = Interceptor { chain ->
        if (!isActive()) {
            return@Interceptor chain.proceed(chain.request())
        }

        val request = chain.request()
        val userAgentPresent = request.header("User-Agent")?.isNotBlank() == true
        val cookiesPresent = request.header("Cookie")?.isNotBlank() == true

        try {
            val response = chain.proceed(request)

            recordHttp(
                area = "Réseau",
                action = "${request.method} ${request.url.encodedPath}",
                code = response.code,
                url = request.url.toString(),
                userAgentPresent = userAgentPresent,
                cookiesPresent = cookiesPresent,
                contentType = response.header("Content-Type"),
                contentLength = response.body?.contentLength()
            )

            response
        } catch (t: Throwable) {
            recordException(
                area = "Réseau",
                action = "${request.method} ${request.url.encodedPath}",
                throwable = t
            )
            throw t
        }
    }
}
