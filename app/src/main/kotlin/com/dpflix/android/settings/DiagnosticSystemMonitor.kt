package com.dpflix.android.settings

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.Window
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.djamylova.tvflix.cursor.CursorLayout
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
 * Diagnostic système DP-FLIX.
 *
 * Cette version remplace le moniteur précédent sans demander de modification au reste
 * de l'application : DpFlixApplication appelle déjà [initialize], et SettingsScreenTv
 * utilise déjà [state], [start], [stop] et [clearReport].
 *
 * Pendant une session :
 * - Curseur : mesure réelle du rythme des frames, dt, position, vitesse et saccades.
 * - Focus/IME : observe le focus réel, les changements de focus, la visibilité de l'IME
 *   et les vues éditables/focusables au moment des transitions.
 * - Réseau : conserve les réponses HTTP déjà remontées par OkHttp/WebView et distingue
 *   succès, redirection et erreurs.
 * - WebView : conserve les erreurs de chargement/HTTP déjà remontées par FilmsSeriesScreen
 *   et expose des méthodes explicites pour onRenderProcessGone / renderer unresponsive.
 *
 * Le moniteur reste passif : il n'injecte aucune touche, ne modifie aucun WebView et
 * n'intercepte aucun trafic. Il observe uniquement lorsque l'utilisateur a activé le diagnostic.
 *
 * Confidentialité :
 * - pas de query string ;
 * - pas de Cookie/Authorization/token/mot de passe ;
 * - les URL sont réduites à scheme + host + path ;
 * - les événements sont limités à [MAX_EVENTS].
 */
object DiagnosticSystemMonitor {

    private const val SESSION_DURATION_MS = 10 * 60 * 1000L
    private const val TICK_MS = 1_000L
    private const val CURSOR_SAMPLE_MS = 250L
    private const val MAX_EVENTS = 2_000
    private const val REPORT_FILE = "diagnostic_system_report.txt"
    private const val NS_PER_MS = 1_000_000L

    // Pont découplé : tvbro ne dépend pas de :app.
    const val ACTION_TVBRO_DIAGNOSTIC = "com.dpflix.android.DIAGNOSTIC_TVBRO"
    const val EXTRA_AREA = "area"
    const val EXTRA_ACTION = "action"
    const val EXTRA_STATUS = "status"
    const val EXTRA_DETAIL = "detail"
    const val EXTRA_CAUSE = "cause"
    const val EXTRA_SEVERITY = "severity"

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

    private data class CursorSample(
        val x: Float,
        val y: Float,
        val timeNs: Long
    )

    private data class CursorStats(
        var last: CursorSample? = null,
        var lastSampleMs: Long = 0L,
        var lastReportMs: Long = 0L,
        var frameCount: Long = 0L,
        var frameDtSumNs: Long = 0L,
        var frameDtMaxNs: Long = 0L,
        var slowFrames: Long = 0L,
        var cursorSpeedSum: Double = 0.0,
        var cursorSpeedMax: Double = 0.0,
        var cursorSamples: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = CopyOnWriteArrayList<Event>()
    private val running = AtomicBoolean(false)

    private var timerJob: Job? = null
    private var appContext: android.content.Context? = null
    private var tvBroReceiverRegistered = false
    private var endAtElapsedRealtime = 0L

    private val tvBroDiagnosticReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_TVBRO_DIAGNOSTIC || !running.get()) return
            val status = runCatching {
                Status.valueOf(intent.getStringExtra(EXTRA_STATUS) ?: Status.WARNING.name)
            }.getOrDefault(Status.WARNING)
            val severity = runCatching {
                Severity.valueOf(intent.getStringExtra(EXTRA_SEVERITY) ?: severityFor(status).name)
            }.getOrDefault(severityFor(status))
            record(
                area = intent.getStringExtra(EXTRA_AREA) ?: "TV Bro",
                action = intent.getStringExtra(EXTRA_ACTION) ?: "Événement",
                status = status,
                detail = intent.getStringExtra(EXTRA_DETAIL) ?: "Événement TV Bro reçu",
                cause = intent.getStringExtra(EXTRA_CAUSE),
                severity = severity
            )
        }
    }

    private fun severityFor(status: Status): Severity = when (status) {
        Status.SUCCESS -> Severity.INFO
        Status.WARNING -> Severity.WARNING
        Status.ERROR -> Severity.CRITICAL
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile
    private var mainActivity: Activity? = null

    private var lifecycleRegistered = false
    private var frameCallbackInstalled = false
    private var frameLastNs = 0L
    private var lastUiSampleMs = 0L

    private val cursorStats = CursorStats()
    private val cursorViews = ArrayList<CursorLayout>()
    private val tvBroCursorViews = ArrayList<View>()

    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: android.os.Bundle?) {
            if (running.get()) attachActivity(activity)
        }

        override fun onActivityStarted(activity: Activity) = Unit

        override fun onActivityResumed(activity: Activity) {
            mainActivity = activity
            if (running.get()) attachActivity(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (mainActivity === activity) mainActivity = null
        }

        override fun onActivityStopped(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: android.os.Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) {
            if (mainActivity === activity) mainActivity = null
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running.get()) {
                frameCallbackInstalled = false
                return
            }

            val previous = frameLastNs
            frameLastNs = frameTimeNanos

            if (previous > 0L) {
                val dtNs = frameTimeNanos - previous
                if (dtNs > 0L) {
                    cursorStats.frameCount++
                    cursorStats.frameDtSumNs += dtNs
                    if (dtNs > cursorStats.frameDtMaxNs) cursorStats.frameDtMaxNs = dtNs

                    // > 1.5 frame interval sur une cible 60 Hz = frame possiblement ratée.
                    if (dtNs > 25_000_000L) cursorStats.slowFrames++
                }
            }

            sampleCursors(frameTimeNanos)

            val nowMs = SystemClock.elapsedRealtime()
            if (nowMs - lastUiSampleMs >= TICK_MS) {
                lastUiSampleMs = nowMs
                publishCursorWindow(nowMs)
            }

            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Synchronized
    fun initialize(context: android.content.Context) {
        if (appContext == null) appContext = context.applicationContext

        if (!lifecycleRegistered) {
            (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(activityCallbacks)
            lifecycleRegistered = true
        }

        if (!tvBroReceiverRegistered) {
            runCatching {
                val app = context.applicationContext
                app.registerReceiver(
                    tvBroDiagnosticReceiver,
                    IntentFilter(ACTION_TVBRO_DIAGNOSTIC),
                    Context.RECEIVER_NOT_EXPORTED
                )
                tvBroReceiverRegistered = true
            }
        }

        if (_state.value.report == null) {
            _state.value = _state.value.copy(report = readReport())
        }
    }

    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return false

        events.clear()
        resetCursorStats()

        endAtElapsedRealtime =
            SystemClock.elapsedRealtime() + SESSION_DURATION_MS

        _state.value = State(
            active = true,
            remainingMillis = SESSION_DURATION_MS
        )

        record(
            "Diagnostic système",
            "Démarrage",
            Status.SUCCESS,
            "Analyse de 10 minutes activée : curseur + focus/IME + réseau + WebView"
        )

        mainActivity?.let(::attachActivity)
        installFrameCallback()

        timerJob?.cancel()
        timerJob = scope.launch {
            while (running.get()) {
                val remaining =
                    (endAtElapsedRealtime - SystemClock.elapsedRealtime()).coerceAtLeast(0L)

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
        if (!running.get()) return

        // Publier la dernière fenêtre alors que la session est encore considérée active.
        publishCursorWindow(SystemClock.elapsedRealtime())

        if (!running.compareAndSet(true, false)) return

        timerJob?.cancel()
        timerJob = null
        uninstallFrameCallback()

        finalizeSession("Analyse arrêtée manuellement")
    }

    fun isRunning(): Boolean = running.get()

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

        addEvent(event)
    }

    /**
     * À appeler par les couches HTTP déjà instrumentées.
     */
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
            301, 302, 303, 307, 308 -> "Redirection HTTP observée."
            401 -> "Authentification ou session requise/refusée."
            403 -> "Requête refusée par le serveur (HTTP 403)."
            404 -> "Ressource introuvable (HTTP 404)."
            408, 504 -> "Délai d'attente réseau dépassé."
            in 500..599 -> "Erreur côté serveur (HTTP $code)."
            else -> null
        }

        val details = buildString {
            append("HTTP $code · ${sanitizeUrl(url)}")
            contentType?.let { append(" · Content-Type=${sanitizeText(it).take(120)}") }
            userAgentPresent?.let {
                append(if (it) " · User-Agent présent" else " · User-Agent absent")
            }
            cookiesPresent?.let {
                append(if (it) " · cookies présents" else " · cookies absents")
            }
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
            detail = buildString {
                append(request.method)
                append(" ")
                append(sanitizeUrl(request.url.toString()))
                append(" · mainFrame=")
                append(request.isForMainFrame)
                append(" · User-Agent ")
                append(if (userAgentPresent == true) "présent" else "absent/non observé")
                append(" · cookies ")
                append(if (cookieHeaderPresent == true) "présents" else "absents/non observés")
            }
        )
    }

    /**
     * Journalise explicitement un blocage effectué par la politique DP-FLIX.
     *
     * Cette méthode est volontairement distincte de [recordWebViewRequest] afin que le rapport
     * puisse différencier "requête observée" et "requête réellement bloquée par l'application".
     */
    fun recordWebViewBlocked(
        url: String,
        reason: String,
        mainFrame: Boolean? = null,
        resourceType: String? = null
    ) {
        record(
            area = "Réseau / Blocage",
            action = "Requête WebView bloquée",
            status = Status.WARNING,
            detail = buildString {
                append(sanitizeUrl(url))
                mainFrame?.let { append(" · mainFrame=$it") }
                resourceType?.let { append(" · type=${sanitizeText(it).take(80)}") }
                append(" · raison=${sanitizeText(reason).take(240)}")
            },
            cause = "Blocage appliqué par la politique réseau DP-FLIX."
        )
    }

    /**
     * Événement explicite pour onRenderProcessGone.
     * Le code WebView actuel peut l'appeler sans modifier le comportement du renderer.
     */
    fun recordWebViewRenderProcessGone(
        didCrash: Boolean?,
        detail: String? = null
    ) {
        record(
            area = "Films & Séries / WebView",
            action = "Renderer WebView terminé",
            status = Status.ERROR,
            detail = buildString {
                append("didCrash=${didCrash ?: "inconnu"}")
                detail?.let { append(" · ${sanitizeText(it).take(500)}") }
            },
            cause = if (didCrash == true) {
                "Le processus de rendu Chromium a signalé un crash."
            } else {
                "Le processus de rendu WebView a été arrêté ou perdu."
            }
        )
    }

    fun recordWebViewRendererUnresponsive(
        rendererPriority: Int? = null
    ) {
        record(
            area = "Films & Séries / WebView",
            action = "Renderer WebView non réactif",
            status = Status.ERROR,
            detail = "Renderer Chromium non réactif" +
                (rendererPriority?.let { " · priorité=$it" } ?: ""),
            cause = "Le renderer WebView n'a pas répondu dans le délai attendu."
        )
    }

    fun recordWebViewRendererResponsive() {
        record(
            area = "Films & Séries / WebView",
            action = "Renderer WebView à nouveau réactif",
            status = Status.SUCCESS,
            detail = "Renderer Chromium de nouveau réactif"
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
            is IOException -> "Erreur d'entrée/sortie réseau ou fichier."
            else -> throwable.javaClass.simpleName
        }

        record(
            area,
            action,
            Status.ERROR,
            throwable.message?.take(500) ?: cause,
            cause
        )
    }

    /**
     * Intercepteur OkHttp existant : aucune modification de trafic.
     */
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
        appContext?.let {
            runCatching {
                File(it.filesDir, REPORT_FILE).delete()
            }
        }

        _state.value = State()
        resetCursorStats()
        tvBroCursorViews.clear()
    }

    private fun addEvent(event: Event) {
        if (events.size >= MAX_EVENTS) {
            val firstNonError = events.indexOfFirst { it.status == Status.SUCCESS }
            events.removeAt(if (firstNonError >= 0) firstNonError else 0)
        }

        events.add(event)

        _state.value = buildState(
            remaining = (
                endAtElapsedRealtime - SystemClock.elapsedRealtime()
                ).coerceAtLeast(0L),
            last = event
        )
    }

    private fun installFrameCallback() {
        if (frameCallbackInstalled) return

        frameCallbackInstalled = true
        frameLastNs = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun uninstallFrameCallback() {
        if (!frameCallbackInstalled) return

        Choreographer.getInstance().removeFrameCallback(frameCallback)
        frameCallbackInstalled = false
        frameLastNs = 0L
    }

    private fun resetCursorStats() {
        cursorStats.last = null
        cursorStats.lastSampleMs = 0L
        cursorStats.lastReportMs = 0L
        cursorStats.frameCount = 0L
        cursorStats.frameDtSumNs = 0L
        cursorStats.frameDtMaxNs = 0L
        cursorStats.slowFrames = 0L
        cursorStats.cursorSpeedSum = 0.0
        cursorStats.cursorSpeedMax = 0.0
        cursorStats.cursorSamples = 0L
        cursorViews.clear()
    }

    /**
     * Découvre les CursorLayout présents dans l'arbre de l'Activity.
     * La découverte est périodique, car FilmsSeriesScreen crée le WebView/AndroidView
     * après la composition initiale.
     */
    private fun refreshCursorViews() {
        val activity = mainActivity ?: return
        val root = activity.window.decorView

        cursorViews.clear()
        tvBroCursorViews.clear()
        findCursorLayouts(root, cursorViews)
        findTvBroCursorLayouts(root, tvBroCursorViews)
    }

    private fun findCursorLayouts(view: View, result: MutableList<CursorLayout>) {
        if (view is CursorLayout) {
            result += view
            return
        }

        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findCursorLayouts(view.getChildAt(i), result)
            }
        }
    }

    private fun findTvBroCursorLayouts(view: View, result: MutableList<View>) {
        if (view.javaClass.name == "com.phlox.tvwebbrowser.widgets.cursor.CursorLayout") {
            result += view
            return
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) findTvBroCursorLayouts(view.getChildAt(i), result)
        }
    }

    private fun reflectFloat(view: View, method: String): Float? =
        runCatching { view.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }?.invoke(view) as? Number }
            .getOrNull()?.toFloat()

    private fun reflectBoolean(view: View, method: String): Boolean? =
        runCatching { view.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }?.invoke(view) as? Boolean }
            .getOrNull()

    private fun sampleCursors(frameTimeNs: Long) {
        val nowMs = SystemClock.elapsedRealtime()

        if (nowMs - cursorStats.lastSampleMs >= CURSOR_SAMPLE_MS) {
            cursorStats.lastSampleMs = nowMs
            refreshCursorViews()
        }

        if (cursorViews.isEmpty()) return

        cursorViews.forEachIndexed { index, cursor ->
            if (!cursor.isAttachedToWindow) return@forEachIndexed

            val x = cursor.getCursorX()
            val y = cursor.getCursorY()
            val previous = cursorStats.last
            val current = CursorSample(x, y, frameTimeNs)

            if (previous != null) {
                val dtNs = current.timeNs - previous.timeNs
                if (dtNs > 0L) {
                    val dx = current.x - previous.x
                    val dy = current.y - previous.y
                    val distance = kotlin.math.sqrt(
                        (dx * dx + dy * dy).toDouble()
                    )
                    val speedPxPerSecond = distance * 1_000_000_000.0 / dtNs

                    cursorStats.cursorSpeedSum += speedPxPerSecond
                    cursorStats.cursorSpeedMax =
                        maxOf(cursorStats.cursorSpeedMax, speedPxPerSecond)
                    cursorStats.cursorSamples++

                    // Si plusieurs CursorLayout existent, on ne mélange pas leurs vitesses
                    // dans le même événement. L'écran TV attendu en possède normalement un.
                    if (index == 0 && nowMs - cursorStats.lastReportMs >= 1_000L) {
                        cursorStats.lastReportMs = nowMs
                    }
                }
            }

            cursorStats.last = current
        }

        tvBroCursorViews.firstOrNull()?.let { cursor ->
            val x = reflectFloat(cursor, "getCursorX")
            val y = reflectFloat(cursor, "getCursorY")
            if (x != null && y != null) {
                val previous = cursorStats.last
                val current = CursorSample(x, y, frameTimeNs)
                if (previous != null) {
                    val dtNs = current.timeNs - previous.timeNs
                    if (dtNs > 0L) {
                        val dx = current.x - previous.x
                        val dy = current.y - previous.y
                        val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                        val speed = distance * 1_000_000_000.0 / dtNs
                        cursorStats.cursorSpeedSum += speed
                        cursorStats.cursorSpeedMax = maxOf(cursorStats.cursorSpeedMax, speed)
                        cursorStats.cursorSamples++
                    }
                }
                cursorStats.last = current
            }
        }
    }

    /**
     * Publie un échantillon agrégé toutes les secondes au lieu d'écrire un événement à chaque
     * frame. Cela garde le rapport exploitable tout en conservant le dt et les vitesses réelles.
     */
    private fun publishCursorWindow(nowMs: Long) {
        if (!running.get()) return
        if (cursorStats.frameCount == 0L && cursorStats.cursorSamples == 0L) return

        val frameAvgMs =
            if (cursorStats.frameCount > 0L) {
                cursorStats.frameDtSumNs.toDouble() /
                    cursorStats.frameCount.toDouble() /
                    NS_PER_MS.toDouble()
            } else {
                0.0
            }

        val frameMaxMs = cursorStats.frameDtMaxNs.toDouble() / NS_PER_MS.toDouble()

        val cursorAvgSpeed =
            if (cursorStats.cursorSamples > 0L) {
                cursorStats.cursorSpeedSum / cursorStats.cursorSamples
            } else {
                0.0
            }

        val cursorMaxSpeed = cursorStats.cursorSpeedMax

        val hasJank = cursorStats.slowFrames > 0L
        val status = if (hasJank) Status.WARNING else Status.SUCCESS

        record(
            area = "Curseur / Fluidité",
            action = "Fenêtre de mesure 1 s",
            status = status,
            detail = buildString {
                append("frames=${cursorStats.frameCount}")
                append(" · dt moyen=${format1(frameAvgMs)} ms")
                append(" · dt max=${format1(frameMaxMs)} ms")
                append(" · frames lentes=${cursorStats.slowFrames}")
                append(" · vitesse moyenne=${format1(cursorAvgSpeed)} px/s")
                append(" · vitesse max=${format1(cursorMaxSpeed)} px/s")

                cursorViews.firstOrNull()?.let {
                    append(" · visible=${it.cursorEnabled}")
                    append(" · position=")
                    append(format1(it.getCursorX().toDouble()))
                    append(",")
                    append(format1(it.getCursorY().toDouble()))
                    append(" · focus=")
                    append(if (it.hasFocus()) "CursorLayout" else "autre")
                }
                tvBroCursorViews.firstOrNull()?.let {
                    append(" · TVBroCursor=true")
                    reflectBoolean(it, "isCursorEnabled")?.let { enabled -> append(" · visible=$enabled") }
                    reflectFloat(it, "getCursorX")?.let { x -> append(" · x=${format1(x.toDouble())}") }
                    reflectFloat(it, "getCursorY")?.let { y -> append(" · y=${format1(y.toDouble())}") }
                    append(" · focus=")
                    append(if (it.hasFocus()) "CursorLayout" else "autre")
                }
            },
            cause = if (hasJank) {
                "Le thread UI a présenté au moins une frame > 25 ms pendant la fenêtre."
            } else {
                null
            }
        )

        cursorStats.frameCount = 0L
        cursorStats.frameDtSumNs = 0L
        cursorStats.frameDtMaxNs = 0L
        cursorStats.slowFrames = 0L
        cursorStats.cursorSpeedSum = 0.0
        cursorStats.cursorSpeedMax = 0.0
        cursorStats.cursorSamples = 0L
    }

    private fun attachActivity(activity: Activity) {
        val root = activity.window.decorView

        root.viewTreeObserver.addOnGlobalFocusChangeListener(
            object : ViewTreeObserver.OnGlobalFocusChangeListener {
                override fun onGlobalFocusChanged(oldFocus: View?, newFocus: View?) {
                    if (!running.get()) return

                    record(
                        area = "Focus / Clavier",
                        action = "Changement de focus",
                        status = if (newFocus != null) Status.SUCCESS else Status.WARNING,
                        detail = buildString {
                            append("ancien=")
                            append(describeView(oldFocus))
                            append(" · nouveau=")
                            append(describeView(newFocus))
                            append(" · ime=")
                            append(if (isImeVisible(root)) "visible" else "masqué")
                        },
                        cause = if (newFocus == null) {
                            "Aucune vue n'a le focus après la transition."
                        } else {
                            null
                        }
                    )
                }
            }
        )

        root.viewTreeObserver.addOnGlobalLayoutListener(
            object : ViewTreeObserver.OnGlobalLayoutListener {
                private var previousImeVisible: Boolean? = null
                private var previousFocused: String? = null

                override fun onGlobalLayout() {
                    if (!running.get()) return

                    val imeVisible = isImeVisible(root)
                    val focused = describeView(root.findFocus())

                    if (previousImeVisible != imeVisible) {
                        previousImeVisible = imeVisible

                        record(
                            area = "Focus / Clavier",
                            action = if (imeVisible) "IME visible" else "IME masqué",
                            status = if (imeVisible) Status.SUCCESS else Status.WARNING,
                            detail = buildString {
                                append("focus=")
                                append(focused)
                                append(" · hauteurVisible=")
                                append(visibleHeight(root))
                                append(" · hauteurRoot=")
                                append(root.height)
                            },
                            cause = if (imeVisible) {
                                "Le clavier système est réellement visible selon WindowInsets."
                            } else {
                                "Le clavier système n'est plus visible selon WindowInsets."
                            }
                        )
                    }

                    if (previousFocused != focused) {
                        previousFocused = focused

                        if (focused != "aucun") {
                            val focusedView = root.findFocus()

                            record(
                                area = "Focus / Clavier",
                                action = "Vue focalisée",
                                status = Status.SUCCESS,
                                detail = buildString {
                                    append(describeView(focusedView))
                                    append(" · focusable=")
                                    append(focusedView?.isFocusable)
                                    append(" · focusableTouch=")
                                    append(focusedView?.isFocusableInTouchMode)
                                    append(" · ime=")
                                    append(if (imeVisible) "visible" else "masqué")
                                }
                            )
                        }
                    }
                }
            }
        )

        record(
            "Focus / Clavier",
            "Activity observée",
            Status.SUCCESS,
            "${activity.javaClass.name} · root=${root.width}x${root.height}"
        )
    }

    private fun isImeVisible(root: View): Boolean {
        return try {
            androidx.core.view.ViewCompat.getRootWindowInsets(root)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        } catch (_: Throwable) {
            false
        }
    }

    private fun visibleHeight(root: View): Int {
        return try {
            val rect = android.graphics.Rect()
            root.getWindowVisibleDisplayFrame(rect)
            rect.height()
        } catch (_: Throwable) {
            -1
        }
    }

    private fun describeView(view: View?): String {
        if (view == null) return "aucun"

        val cls = view.javaClass.name
            .removePrefix("androidx.compose.ui.platform.")
            .takeLast(100)

        val editable = view is android.widget.EditText ||
            view.isFocusable ||
            view.isFocusableInTouchMode

        return buildString {
            append(cls)
            append(" id=")
            append(if (view.id != View.NO_ID) view.id else "none")
            append(" editable/focusable=")
            append(editable)
        }
    }

    private fun stopInternal(reason: String) {
        if (!running.get()) return

        // La dernière fenêtre doit être enregistrée avant de fermer la session.
        publishCursorWindow(SystemClock.elapsedRealtime())

        if (!running.compareAndSet(true, false)) return

        timerJob?.cancel()
        timerJob = null
        uninstallFrameCallback()

        finalizeSession(reason)
    }

    private fun buildState(
        remaining: Long,
        last: Event? = events.lastOrNull()
    ): State = State(
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

    private fun generateReport(
        reason: String,
        snapshot: List<Event>
    ): String {
        val formatter = SimpleDateFormat(
            "dd/MM/yyyy HH:mm:ss.SSS",
            Locale.FRANCE
        )

        val started =
            snapshot.firstOrNull()?.timestampMillis
                ?: System.currentTimeMillis()

        val byArea = snapshot.groupingBy { it.area }.eachCount()

        return buildString {
            appendLine("DP-FLIX — DIAGNOSTIC SYSTÈME")
            appendLine("Durée maximale : 10 minutes")
            appendLine("Début : ${formatter.format(Date(started))}")
            appendLine("Fin : ${formatter.format(Date())}")
            appendLine("Motif d'arrêt : $reason")
            appendLine()
            appendLine("RÉSUMÉ")
            appendLine("Actions observées : ${snapshot.size}")
            appendLine("Réussites : ${snapshot.count { it.status == Status.SUCCESS }}")
            appendLine("Avertissements : ${snapshot.count { it.status == Status.WARNING }}")
            appendLine("Erreurs : ${snapshot.count { it.status == Status.ERROR }}")
            appendLine()
            appendLine("ÉVÉNEMENTS PAR CATÉGORIE")
            byArea.entries
                .sortedByDescending { it.value }
                .forEach { (area, count) ->
                    appendLine("- $area : $count")
                }
            appendLine()
            appendLine("JOURNAL CHRONOLOGIQUE")

            snapshot.forEachIndexed { index, event ->
                appendLine(
                    "${index + 1}. [${formatter.format(Date(event.timestampMillis))}] " +
                        "${event.area}"
                )
                appendLine("   Action : ${event.action}")
                appendLine("   État : ${event.status}")
                appendLine("   Gravité : ${event.severity}")
                appendLine("   Détails : ${event.detail}")
                event.cause?.let {
                    appendLine("   Cause identifiée/probable : $it")
                }
                appendLine()
            }

            appendLine("INTERPRÉTATION")
            appendLine(
                "- Une frame > 25 ms est signalée comme frame lente ; elle indique " +
                    "une charge UI ou un ralentissement du thread principal, mais ne " +
                    "prouve pas à elle seule que CursorLayout est le responsable."
            )
            appendLine(
                "- Une vitesse de curseur élevée ou faible est une mesure de déplacement " +
                    "réel entre deux échantillons ; elle ne constitue pas à elle seule " +
                    "un diagnostic de panne."
            )
            appendLine(
                "- Les événements IME/focus permettent de distinguer perte de focus, " +
                    "IME visible et simple changement de vue focalisée."
            )
            appendLine(
                "- Les événements HTTP/WebView permettent de distinguer une erreur serveur, " +
                    "une ressource introuvable, une redirection et une erreur de chargement."
            )
            appendLine(
                "- Un événement 'Renderer WebView terminé' identifie explicitement un " +
                    "problème du processus Chromium lorsqu'il est remonté par WebView."
            )
            appendLine()
            appendLine(
                "Confidentialité : les valeurs complètes de cookies, tokens, mots de passe, " +
                    "Authorization, clés API et query strings ne sont pas conservées."
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

    private fun readReport(): String? = appContext?.let {
        runCatching {
            File(it.filesDir, REPORT_FILE)
                .takeIf(File::exists)
                ?.readText()
        }.getOrNull()
    }

    private fun sanitizeUrl(raw: String): String = runCatching {
        val uri = URI(raw)

        buildString {
            append(uri.scheme ?: "")
            append("://")
            append(uri.host ?: "")

            uri.path
                ?.takeIf { it.isNotBlank() }
                ?.let { append(it.take(240)) }
        }
    }.getOrElse {
        "URL masquée"
    }

    private fun sanitizeText(value: String): String = value
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

    private fun format1(value: Double): String =
        String.format(Locale.US, "%.1f", value)
}
