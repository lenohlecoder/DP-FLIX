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

    private data class FrameStats(
        var frameCount: Long = 0L,
        var frameDtSumNs: Long = 0L,
        var frameDtMaxNs: Long = 0L,
        var slowFrames: Long = 0L
    )

    /**
     * Statistiques de vitesse pour UNE source de curseur (DP-FLIX ou TV Bro).
     * [lastView] permet de détecter qu'une référence de vue a changé (Activity
     * recréée/reprise, WebView recréé) afin de ne jamais calculer une vitesse
     * entre deux échantillons appartenant à des instances différentes.
     */
    private data class CursorSpeedStats(
        var last: CursorSample? = null,
        var lastView: View? = null,
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
    private var lastCursorRefreshMs = 0L

    private val frameStats = FrameStats()
    private val dpFlixCursorStats = CursorSpeedStats()
    private val tvBroCursorStats = CursorSpeedStats()
    private val cursorViews = ArrayList<CursorLayout>()
    private val tvBroCursorViews = ArrayList<View>()

    // Champs de réflexion TV Bro mis en cache par Class pour éviter de refaire une
    // recherche de champ à chaque frame. Une résolution ratée (champ renommé dans une
    // future version de TV Bro) reste mémorisée à null pour cette Class : pas de retry
    // coûteux à chaque frame, mais rien n'est jamais mis en cache de façon permanente
    // au niveau de l'instance (voir reflectTvBroCursorPosition).
    private var reflectedForClass: Class<*>? = null
    private var reflectedDelegateField: java.lang.reflect.Field? = null
    private var reflectedPositionField: java.lang.reflect.Field? = null

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
                    frameStats.frameCount++
                    frameStats.frameDtSumNs += dtNs
                    if (dtNs > frameStats.frameDtMaxNs) frameStats.frameDtMaxNs = dtNs

                    // > 1.5 frame interval sur une cible 60 Hz = frame possiblement ratée.
                    if (dtNs > 25_000_000L) frameStats.slowFrames++
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
        lastCursorRefreshMs = 0L

        frameStats.frameCount = 0L
        frameStats.frameDtSumNs = 0L
        frameStats.frameDtMaxNs = 0L
        frameStats.slowFrames = 0L

        resetCursorSpeedStats(dpFlixCursorStats)
        resetCursorSpeedStats(tvBroCursorStats)

        cursorViews.clear()
        tvBroCursorViews.clear()
    }

    private fun resetCursorSpeedStats(stats: CursorSpeedStats) {
        stats.last = null
        stats.lastView = null
        stats.cursorSpeedSum = 0.0
        stats.cursorSpeedMax = 0.0
        stats.cursorSamples = 0L
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

    private fun reflectBoolean(view: View, method: String): Boolean? =
        runCatching { view.javaClass.methods.firstOrNull { it.name == method && it.parameterTypes.isEmpty() }?.invoke(view) as? Boolean }
            .getOrNull()

    /**
     * Résout et met en cache les champs de réflexion TV Bro pour une Class donnée.
     * Appelé à chaque échantillonnage mais ne refait la recherche de champ que si la
     * Class a changé (jamais en pratique, une seule version de CursorLayout par build).
     * Un échec de résolution (champ absent) est mémorisé à null pour cette Class afin
     * de ne pas retenter une réflexion coûteuse à chaque frame — mais n'empêche jamais
     * une instance nouvellement initialisée d'être lue au prochain appel réussi.
     */
    private fun ensureTvBroReflection(cls: Class<*>) {
        if (reflectedForClass === cls) return

        reflectedForClass = cls
        reflectedDelegateField = null
        reflectedPositionField = null

        runCatching {
            val delegateField = cls.getDeclaredField("cursorDrawerDelegate")
                .apply { isAccessible = true }
            val positionField = delegateField.type.getDeclaredField("cursorPosition")
                .apply { isAccessible = true }
            reflectedDelegateField = delegateField
            reflectedPositionField = positionField
        }
    }

    /**
     * Lit la position réelle du curseur TV Bro : CursorLayout n'expose pas
     * getCursorX()/getCursorY(), la position vit dans
     * cursorDrawerDelegate.cursorPosition (PointF) sur CursorDrawerDelegate.
     *
     * Tolérant par construction :
     * - cursorDrawerDelegate est `lateinit` ; s'il n'est pas encore initialisé
     *   (Activity TV Bro tout juste créée), l'accès lève une exception attrapée par
     *   runCatching → retourne null pour cet appel, sans jamais planter le diagnostic.
     * - Rien n'est mis en cache au niveau de l'instance : chaque appel relit l'état
     *   réel de [view], donc un curseur initialisé entre deux échantillonnages est
     *   détecté dès l'échantillonnage suivant.
     */
    private fun reflectTvBroCursorPosition(view: View): Pair<Float, Float>? {
        ensureTvBroReflection(view.javaClass)

        val delegateField = reflectedDelegateField ?: return null
        val positionField = reflectedPositionField ?: return null

        return runCatching {
            val delegate = delegateField.get(view) ?: return@runCatching null
            val position = positionField.get(delegate) as? android.graphics.PointF
                ?: return@runCatching null
            position.x to position.y
        }.getOrNull()
    }

    private fun sampleCursors(frameTimeNs: Long) {
        val nowMs = SystemClock.elapsedRealtime()

        if (nowMs - lastCursorRefreshMs >= CURSOR_SAMPLE_MS) {
            lastCursorRefreshMs = nowMs
            refreshCursorViews()
        }

        // Curseur DP-FLIX (com.djamylova.tvflix) : jamais gaté par la présence du
        // curseur TV Bro, ni l'inverse.
        if (cursorViews.isNotEmpty()) {
            cursorViews.forEach { cursor ->
                if (!cursor.isAttachedToWindow) return@forEach
                accumulateCursorSample(
                    stats = dpFlixCursorStats,
                    view = cursor,
                    x = cursor.getCursorX(),
                    y = cursor.getCursorY(),
                    timeNs = frameTimeNs
                )
            }
        }

        // Curseur TV Bro (com.phlox.tvwebbrowser) : toujours échantillonné, indépendamment
        // de cursorViews — c'est le cas normal une fois que TV Bro pilote l'écran stream.
        tvBroCursorViews.firstOrNull()?.let { cursor ->
            if (!cursor.isAttachedToWindow) return@let
            val position = reflectTvBroCursorPosition(cursor) ?: return@let
            accumulateCursorSample(
                stats = tvBroCursorStats,
                view = cursor,
                x = position.first,
                y = position.second,
                timeNs = frameTimeNs
            )
        }
    }

    /**
     * Accumule un échantillon de vitesse pour une source de curseur donnée.
     * Si [view] diffère de la dernière vue connue pour ces stats (Activity recréée/
     * reprise, WebView recréé), l'échantillon précédent est ignoré : il ne sert jamais
     * de référence pour calculer une vitesse entre deux instances différentes.
     */
    private fun accumulateCursorSample(
        stats: CursorSpeedStats,
        view: View,
        x: Float,
        y: Float,
        timeNs: Long
    ) {
        val current = CursorSample(x, y, timeNs)
        val previous = if (stats.lastView === view) stats.last else null

        if (previous != null) {
            val dtNs = current.timeNs - previous.timeNs
            if (dtNs > 0L) {
                val dx = current.x - previous.x
                val dy = current.y - previous.y
                val distance = kotlin.math.sqrt((dx * dx + dy * dy).toDouble())
                val speed = distance * 1_000_000_000.0 / dtNs

                stats.cursorSpeedSum += speed
                stats.cursorSpeedMax = maxOf(stats.cursorSpeedMax, speed)
                stats.cursorSamples++
            }
        }

        stats.last = current
        stats.lastView = view
    }

    /**
     * Publie un échantillon agrégé toutes les secondes au lieu d'écrire un événement à chaque
     * frame. Cela garde le rapport exploitable tout en conservant le dt et les vitesses réelles.
     */
    private fun publishCursorWindow(nowMs: Long) {
        if (!running.get()) return
        if (frameStats.frameCount == 0L &&
            dpFlixCursorStats.cursorSamples == 0L &&
            tvBroCursorStats.cursorSamples == 0L
        ) {
            return
        }

        val frameAvgMs =
            if (frameStats.frameCount > 0L) {
                frameStats.frameDtSumNs.toDouble() /
                    frameStats.frameCount.toDouble() /
                    NS_PER_MS.toDouble()
            } else {
                0.0
            }
        val frameMaxMs = frameStats.frameDtMaxNs.toDouble() / NS_PER_MS.toDouble()
        val hasJank = frameStats.slowFrames > 0L
        val status = if (hasJank) Status.WARNING else Status.SUCCESS
        val jankCause = if (hasJank) {
            "Le thread UI a présenté au moins une frame > 25 ms pendant la fenêtre."
        } else {
            null
        }

        // dt/frames lentes décrivent le thread UI dans son ensemble : le même préfixe
        // apparaît dans les deux rapports, chacun étant ensuite complété par la vitesse
        // et la position propres à sa source.
        fun frameDetailPrefix() = buildString {
            append("frames=${frameStats.frameCount}")
            append(" · dt moyen=${format1(frameAvgMs)} ms")
            append(" · dt max=${format1(frameMaxMs)} ms")
            append(" · frames lentes=${frameStats.slowFrames}")
        }

        cursorViews.firstOrNull()?.let { cursor ->
            val avgSpeed =
                if (dpFlixCursorStats.cursorSamples > 0L) {
                    dpFlixCursorStats.cursorSpeedSum / dpFlixCursorStats.cursorSamples
                } else {
                    0.0
                }

            record(
                area = "Curseur / DP-FLIX",
                action = "Fenêtre de mesure 1 s",
                status = status,
                detail = buildString {
                    append(frameDetailPrefix())
                    append(" · vitesse moyenne=${format1(avgSpeed)} px/s")
                    append(" · vitesse max=${format1(dpFlixCursorStats.cursorSpeedMax)} px/s")
                    append(" · visible=${cursor.cursorEnabled}")
                    append(" · position=")
                    append(format1(cursor.getCursorX().toDouble()))
                    append(",")
                    append(format1(cursor.getCursorY().toDouble()))
                    append(" · focus=")
                    append(if (cursor.hasFocus()) "CursorLayout" else "autre")
                },
                cause = jankCause
            )
        }

        tvBroCursorViews.firstOrNull()?.let { cursor ->
            val avgSpeed =
                if (tvBroCursorStats.cursorSamples > 0L) {
                    tvBroCursorStats.cursorSpeedSum / tvBroCursorStats.cursorSamples
                } else {
                    0.0
                }

            record(
                area = "Curseur / TV Bro",
                action = "Fenêtre de mesure 1 s",
                status = status,
                detail = buildString {
                    append(frameDetailPrefix())
                    append(" · vitesse moyenne=${format1(avgSpeed)} px/s")
                    append(" · vitesse max=${format1(tvBroCursorStats.cursorSpeedMax)} px/s")
                    reflectBoolean(cursor, "getCursorEnabled")
                        ?.let { enabled -> append(" · visible=$enabled") }
                    reflectTvBroCursorPosition(cursor)?.let { (x, y) ->
                        append(" · position=")
                        append(format1(x.toDouble()))
                        append(",")
                        append(format1(y.toDouble()))
                    }
                    append(" · focus=")
                    append(if (cursor.hasFocus()) "CursorLayout" else "autre")
                },
                cause = jankCause
            )
        }

        frameStats.frameCount = 0L
        frameStats.frameDtSumNs = 0L
        frameStats.frameDtMaxNs = 0L
        frameStats.slowFrames = 0L

        dpFlixCursorStats.cursorSpeedSum = 0.0
        dpFlixCursorStats.cursorSpeedMax = 0.0
        dpFlixCursorStats.cursorSamples = 0L

        tvBroCursorStats.cursorSpeedSum = 0.0
        tvBroCursorStats.cursorSpeedMax = 0.0
        tvBroCursorStats.cursorSamples = 0L
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
