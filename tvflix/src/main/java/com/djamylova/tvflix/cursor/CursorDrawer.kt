package com.djamylova.tvflix.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PointF
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager

/**
 * Gère le dessin, la position, le déplacement, l’injection de clics
 * et le scroll automatique aux bords d’écran.
 *
 * Étape 3 – Navigation D-pad
 * Étape 4 – Injection de clics (SOURCE_MOUSE)
 * Étape 5 – Scroll bord d’écran (« scroll hack »)
 * Étape 6 – Pinch-to-zoom synthétique
 *
 * Inspiré de CursorDrawerDelegate de TV Bro.
 */
class CursorDrawer(
    private val context: Context,
    private val surface: View
) {
    // ——— Position & vitesse ———
    var cursorX: Float = 0f
        private set
    var cursorY: Float = 0f
        private set

    private val cursorDirection = Point(0, 0)
    private val cursorSpeed = PointF(0f, 0f)

    // ——— Apparence ———
    private var cursorRadius: Float = 24f
    private var cursorRadiusPressed: Float = 18f
    private var strokeWidth: Float = 3f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ——— État clic ———
    private var isPressed = false
    private var downTime: Long = 0L
    private var isVisible = true
    private var lastCursorUpdate = SystemClock.uptimeMillis()

    // TV Bro : après 5 s sans mouvement, le curseur disparaît. La première touche
    // suivante ne doit PAS cliquer/déplacer : elle réveille uniquement le curseur.
    private var wakeOnlyKeyCode: Int? = null

    // Clic / appui long — même flux tactile que TV Bro.
    // ACTION_DOWN -> ACTION_UP avec TOOL_TYPE_FINGER.
    private var longPressTriggered = false
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout() + 100L

    private val longPressRunnable = Runnable {
        if (isPressed && !longPressTriggered) {
            longPressTriggered = true
            dispatchMotionEvent(cursorX, cursorY, MotionEvent.ACTION_CANCEL)
            isPressed = false
            surface.invalidate()
            onLongPress?.invoke(cursorX, cursorY)
        }
    }

    // ——— Scroll bord d’écran (Étape 5) ———
    private var scrollStartPadding = 100
    private var scrollHackStarted = false
    private val scrollHackCoords = PointF()
    private val scrollHackActiveRect = Rect()
    private val SCROLL_HACK_PADDING = 300
    /** Active le scroll hack (recommandé pour WebView) */
    var useScrollHack: Boolean = true

    // ——— Config vitesse ———
    private var maxSpeedBaselinePx: Float = 52f
    var maxSpeedPercent: Int = 100
    var accelerationPercent: Int = 100

    // ——— Callbacks ———
    var onLongPress: ((x: Float, y: Float) -> Unit)? = null
    /** Callback optionnel si le host veut gérer le scroll lui-même */
    var onCustomScroll: ((scrollX: Int, scrollY: Int) -> Boolean)? = null

    // ——— Handlers ———
    private val hideHandler = Handler(Looper.getMainLooper())
    private val longPressHandler = Handler(Looper.getMainLooper())

    private val hideRunnable = Runnable {
        isVisible = false
        surface.invalidate()
    }

    // Délai TV Bro : 5 secondes d'inactivité.
    private val UNCHANGED = Integer.MIN_VALUE

    // Boucle de déplacement
    //
    // Fix diagnostic « zigzag / désynchronisation télécommande » :
    // 1. Cadence FIXE via postDelayed(FRAME_INTERVAL_MS) au lieu de post() sans délai
    //    — avant, la boucle tournait aussi vite que le thread UI le permettait
    //    (beaucoup plus vite qu'un taux d'affichage réel sur device rapide, ou figée
    //    plusieurs dizaines de ms sur box lente pendant un rendu WebView/GC).
    // 2. lastCursorUpdate n'est plus écrit QUE par cette boucle (voir handleDirection,
    //    qui ne le touche plus). Avant, chaque KeyEvent de répétition matérielle
    //    (télécommande maintenue) réinitialisait lastCursorUpdate en parallèle de la
    //    boucle → dTime artificiellement proche de zéro à l'itération suivante →
    //    accélération cassée sur cette frame → à-coup visible.
    // 3. dTime est désormais plafonné (MAX_DTIME_MS), pas seulement plancher à 1L —
    //    après un décrochage du thread UI, un dTime démesuré faisait sauter la
    //    vitesse instantanément à speedCap au lieu de monter progressivement : le
    //    curseur « sautait » au lieu de glisser. C'est la cause la plus probable du
    //    zigzag rapporté, et elle empire avec la durée de l'appui (plus d'itérations
    //    = plus d'occasions de décrochage).
    private val cursorUpdateRunnable = object : Runnable {
        override fun run() {
            hideHandler.removeCallbacks(hideRunnable)

            val now = SystemClock.uptimeMillis()
            val dTime = (now - lastCursorUpdate).coerceIn(1L, MAX_DTIME_MS)
            lastCursorUpdate = now

            val speedCap = maxSpeedBaselinePx * (maxSpeedPercent / 100f)
            // Coefficient de montée en vitesse relevé (0.065 → 0.11) : avec l'ancienne
            // valeur, il fallait ~1,5s d'appui continu pour atteindre speedCap, ce qui
            // donnait une impression de lenteur/mollesse au début de chaque mouvement.
            // Avec 0.11 le plein régime est atteint en ~0,8-0,9s, sans sauter d'un coup
            // (la progression reste linéaire frame par frame, donc pas de à-coup).
            val accelerationFactor = 0.11f * (accelerationPercent / 100f) * dTime

            cursorSpeed.x = bound(
                cursorSpeed.x + bound(cursorDirection.x.toFloat(), 1f) * accelerationFactor,
                speedCap
            )
            cursorSpeed.y = bound(
                cursorSpeed.y + bound(cursorDirection.y.toFloat(), 1f) * accelerationFactor,
                speedCap
            )

            if (Math.abs(cursorSpeed.x) < 0.15f) cursorSpeed.x = 0f
            if (Math.abs(cursorSpeed.y) < 0.15f) cursorSpeed.y = 0f

            if (cursorDirection.x == 0 && cursorDirection.y == 0 &&
                cursorSpeed.x == 0f && cursorSpeed.y == 0f
            ) {
                // Fin de mouvement → arrête le scroll hack si actif
                if (scrollHackStarted) {
                    dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
                    scrollHackStarted = false
                }
                scheduleHide()
                return
            }

            val prevX = cursorX
            val prevY = cursorY

            val maxX = (surface.width - 1).toFloat().coerceAtLeast(0f)
            val maxY = (surface.height - 1).toFloat().coerceAtLeast(0f)
            if (maxX <= 0f || maxY <= 0f) {
                // Layout pas encore prêt (vieilles TV parfois lentes)
                surface.postDelayed(this, FRAME_INTERVAL_MS)
                return
            }
            cursorX = (cursorX + cursorSpeed.x).coerceIn(0f, maxX)
            cursorY = (cursorY + cursorSpeed.y).coerceIn(0f, maxY)

            // ——— Étape 5 : Scroll bord d’écran ———
            var dx = 0
            var dy = 0

            if (cursorY > surface.height - scrollStartPadding && cursorSpeed.y > 0) {
                dy = cursorSpeed.y.toInt()
            } else if (cursorY < scrollStartPadding && cursorSpeed.y < 0) {
                dy = cursorSpeed.y.toInt()
            }

            if (cursorX > surface.width - scrollStartPadding && cursorSpeed.x > 0) {
                dx = cursorSpeed.x.toInt()
            } else if (cursorX < scrollStartPadding && cursorSpeed.x < 0) {
                dx = cursorSpeed.x.toInt()
            }

            if (dx != 0 || dy != 0) {
                scrollContentBy(dx, dy)
            }

            // Même comportement que TV Bro : un MOVE n'est injecté que pendant
            // un clic maintenu. En dehors d'un clic, le curseur est uniquement
            // dessiné ; aucun flux HOVER synthétique n'est envoyé à la WebView.
            if (isPressed && (prevX != cursorX || prevY != cursorY)) {
                dispatchMotionEvent(cursorX, cursorY, MotionEvent.ACTION_MOVE)
            }

            isVisible = true
            surface.invalidate()
            surface.postDelayed(this, FRAME_INTERVAL_MS)
        }
    }

    init {
        initMetrics()
    }

    private fun initMetrics() {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val display = wm.defaultDisplay
        val size = android.graphics.Point()
        display.getSize(size)

        cursorRadius = (size.x / 100f).coerceIn(16f, 40f)
        cursorRadiusPressed = cursorRadius * 0.75f
        strokeWidth = (size.x / 400f).coerceIn(2f, 5f)
        // Vitesse de croisière relevée (ex : /20 → /14, plafond 96 → 140) : le
        // curseur atteignait sa vitesse max trop tôt en pratique (surtout sur
        // écrans larges où le calcul plafonnait déjà à 96px), ce qui le rendait
        // perceptiblement lent pour traverser l'écran. Le plancher est aussi
        // relevé (28 → 40) pour les petits écrans.
        maxSpeedBaselinePx = (size.x / 14f).coerceIn(40f, 140f)
        scrollStartPadding = size.x / 15
    }

    fun onSizeChanged(w: Int, h: Int) {
        if (cursorX == 0f && cursorY == 0f) {
            cursorX = w / 2f
            cursorY = h / 2f
        } else {
            cursorX = cursorX.coerceIn(0f, (w - 1).toFloat())
            cursorY = cursorY.coerceIn(0f, (h - 1).toFloat())
        }
        // Zone active pour le scroll hack (marge intérieure)
        scrollHackActiveRect.set(0, 0, w, h)
        scrollHackActiveRect.inset(SCROLL_HACK_PADDING, SCROLL_HACK_PADDING)
        show()
    }

    fun setPosition(x: Float, y: Float) {
        cursorX = x.coerceIn(0f, (surface.width - 1).toFloat().coerceAtLeast(0f))
        cursorY = y.coerceIn(0f, (surface.height - 1).toFloat().coerceAtLeast(0f))
        show()
    }

    fun show() {
        wakeOnlyKeyCode = null
        isVisible = true
        lastCursorUpdate = SystemClock.uptimeMillis()
        scheduleHide()
        surface.invalidate()
    }

    fun hide() {
        wakeOnlyKeyCode = null
        if (isPressed) {
            dispatchMotionEvent(cursorX, cursorY, MotionEvent.ACTION_UP)
            isPressed = false
        }
        if (scrollHackStarted) {
            dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
            scrollHackStarted = false
        }
        isVisible = false
        stopMovement()
        hideHandler.removeCallbacks(hideRunnable)
        longPressHandler.removeCallbacks(longPressRunnable)
        surface.invalidate()
    }

    private fun scheduleHide() {
        hideHandler.removeCallbacks(hideRunnable)
        hideHandler.postDelayed(hideRunnable, CURSOR_HIDE_DELAY)
    }

    private fun stopMovement() {
        surface.removeCallbacks(cursorUpdateRunnable)
        cursorDirection.set(0, 0)
        cursorSpeed.set(0f, 0f)
    }

    // ——————————————————————————————————————————————
    // Étape 5 – Scroll bord d’écran
    // ——————————————————————————————————————————————

    /**
     * Tente de faire défiler le contenu.
     * 1. Callback custom du host
     * 2. scroll natif de la View (si possible)
     * 3. Scroll hack (injection de MOVE) pour WebView
     */
    private fun scrollContentBy(scrollX: Int, scrollY: Int) {
        if (scrollX == 0 && scrollY == 0) return

        // 1. Host custom ?
        if (onCustomScroll?.invoke(scrollX, scrollY) == true) {
            return
        }

        // 2. Scroll natif de la surface (fonctionne pour certains contenus)
        val canH = scrollX != 0 && surface.canScrollHorizontally(scrollX)
        val canV = scrollY != 0 && surface.canScrollVertically(scrollY)
        if (canH || canV) {
            surface.scrollBy(
                if (canH) scrollX else 0,
                if (canV) scrollY else 0
            )
            return
        }

        // 3. Scroll hack – simule un drag tactile pour faire défiler le WebView
        if (useScrollHack && !isPressed) {
            performScrollHack(scrollX, scrollY)
        }
    }

    /**
     * « Scroll hack » inspiré de TV Bro :
     * On injecte une séquence DOWN → MOVE pour faire croire au WebView
     * qu’on fait glisser le doigt, ce qui déclenche le scroll interne.
     */
    private fun performScrollHack(scrollX: Int, scrollY: Int) {
        var justStarted = false

        if (!scrollHackStarted) {
            // Point de départ = position du curseur clampée dans la zone active
            scrollHackCoords.set(
                cursorX.coerceIn(
                    scrollHackActiveRect.left.toFloat(),
                    scrollHackActiveRect.right.toFloat()
                ),
                cursorY.coerceIn(
                    scrollHackActiveRect.top.toFloat(),
                    scrollHackActiveRect.bottom.toFloat()
                )
            )
            dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_DOWN)
            scrollHackStarted = true
            justStarted = true
        }

        // On déplace le point de contact dans le sens opposé au scroll voulu
        scrollHackCoords.x -= scrollX
        scrollHackCoords.y -= scrollY

        // Si on sort de la zone active → on annule et on recommence
        if (scrollHackCoords.x < scrollHackActiveRect.left ||
            scrollHackCoords.x >= scrollHackActiveRect.right ||
            scrollHackCoords.y < scrollHackActiveRect.top ||
            scrollHackCoords.y >= scrollHackActiveRect.bottom
        ) {
            // Remet la position précédente
            scrollHackCoords.x += scrollX
            scrollHackCoords.y += scrollY
            dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
            scrollHackStarted = false

            // Relance immédiatement si ce n’est pas le tout premier frame
            if (!justStarted) {
                performScrollHack(scrollX, scrollY)
            }
            return
        }

        dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_MOVE)
    }

    // ——————————————————————————————————————————————
    // Dessin
    // ——————————————————————————————————————————————

    fun dispatchDraw(canvas: Canvas) {
        if (!isVisible) return

        val radius = if (isPressed) cursorRadiusPressed else cursorRadius

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(140, 255, 255, 255)
        canvas.drawCircle(cursorX, cursorY, radius, paint)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidth
        paint.color = Color.argb(220, 80, 80, 80)
        canvas.drawCircle(cursorX, cursorY, radius, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.argb(200, 50, 50, 50)
        canvas.drawCircle(cursorX, cursorY, radius * 0.15f, paint)
    }

    // ——————————————————————————————————————————————
    // Navigation D-pad + Clic
    // ——————————————————————————————————————————————

    private fun isCursorDisappeared(): Boolean {
        return !isVisible ||
            SystemClock.uptimeMillis() - lastCursorUpdate >= CURSOR_HIDE_DELAY
    }

    private fun wakeFromHiddenState(event: KeyEvent): Boolean {
        if (!isCursorDisappeared()) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            isVisible = true
            lastCursorUpdate = SystemClock.uptimeMillis()
            scheduleHide()
            wakeOnlyKeyCode = event.keyCode
            surface.invalidate()
            return true
        }

        if (event.action == KeyEvent.ACTION_UP && wakeOnlyKeyCode == event.keyCode) {
            wakeOnlyKeyCode = null
            lastCursorUpdate = SystemClock.uptimeMillis()
            scheduleHide()
            surface.invalidate()
            return true
        }

        return wakeOnlyKeyCode == event.keyCode
    }

    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Important : le premier appui après disparition est consommé comme
        // réveil uniquement, exactement comme TV Bro. Cela évite un clic fantôme.
        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN_LEFT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN_RIGHT ||
            event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_BUTTON_A
        ) {
            if (wakeFromHiddenState(event)) return true
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> {
                handleDirection(event, -1, UNCHANGED)
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                handleDirection(event, 1, UNCHANGED)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP -> {
                handleDirection(event, UNCHANGED, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN -> {
                handleDirection(event, UNCHANGED, 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP_LEFT -> {
                handleDirection(event, -1, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_UP_RIGHT -> {
                handleDirection(event, 1, -1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN_LEFT -> {
                handleDirection(event, -1, 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_DOWN_RIGHT -> {
                handleDirection(event, 1, 1)
                return true
            }
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                return handleClickKey(event)
            }
        }
        return false
    }

    private fun handleDirection(event: KeyEvent, dirX: Int, dirY: Int) {
        // lastCursorUpdate n'est PLUS touché ici (fix zigzag) : cursorUpdateRunnable
        // en est l'unique propriétaire pendant qu'il tourne. L'écrire aussi depuis ce
        // callback — appelé à chaque KeyEvent, y compris les répétitions matérielles
        // envoyées par la télécommande tant qu'une direction est maintenue — cassait
        // le calcul du delta-temps utilisé pour l'accélération.
        isVisible = true

        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount > 0 &&
                cursorDirection.x == (if (dirX == UNCHANGED) cursorDirection.x else dirX) &&
                cursorDirection.y == (if (dirY == UNCHANGED) cursorDirection.y else dirY)
            ) {
                return
            }

            longPressHandler.removeCallbacks(longPressRunnable)

            val wasIdle = cursorDirection.x == 0 && cursorDirection.y == 0

            if (dirX != UNCHANGED) cursorDirection.x = dirX
            if (dirY != UNCHANGED) cursorDirection.y = dirY

            if (wasIdle) {
                // Redémarrage propre : la boucle était arrêtée, donc personne d'autre
                // n'a pu écrire lastCursorUpdate récemment — c'est le seul moment où
                // handleDirection doit encore l'initialiser lui-même.
                lastCursorUpdate = SystemClock.uptimeMillis()
                surface.removeCallbacks(cursorUpdateRunnable)
                surface.post(cursorUpdateRunnable)
            }
            // Si la boucle tournait déjà (ex : on ajoute une deuxième direction pour
            // former une diagonale pendant que la première est encore maintenue), on
            // se contente de mettre à jour cursorDirection ci-dessus : la boucle en
            // vol reprendra la nouvelle direction à sa prochaine itération, sans
            // discontinuité de temps ni redémarrage inutile de son cadencement.

        } else if (event.action == KeyEvent.ACTION_UP) {
            // Bug principal (fix « le curseur continue après relâchement ») : le modèle
            // ci-dessus n'a AUCUN terme de freinage. Quand cursorDirection revient à 0,
            // le facteur d'accélération devient 0 * accelerationFactor = 0, donc
            // cursorSpeed reste tel quel — il ne redescend jamais tout seul vers 0. La
            // boucle ne s'arrête que quand cursorSpeed == 0f exactement (voir plus haut),
            // ce qui n'arrivait donc jamais après un appui ayant fait décoller la
            // vitesse : le curseur continuait indéfiniment à cette vitesse résiduelle,
            // même après un simple appui bref. On stoppe donc explicitement la vitesse
            // de l'axe relâché ici, plutôt que de compter sur une décélération
            // progressive qui n'existait pas.
            if (dirX != UNCHANGED) {
                cursorDirection.x = 0
                cursorSpeed.x = 0f
            }
            if (dirY != UNCHANGED) {
                cursorDirection.y = 0
                cursorSpeed.y = 0f
            }

            // Arrête le scroll hack dès qu’on relâche la direction
            if (cursorDirection.x == 0 && cursorDirection.y == 0 && scrollHackStarted) {
                dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
                scrollHackStarted = false
            }
        }
    }

    private fun handleClickKey(event: KeyEvent): Boolean {
        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // Même logique de suivi de touche que TV Bro. Cela évite les
                // doubles ACTION_DOWN lorsque la télécommande répète KEY_ENTER.
                if (event.repeatCount == 0 &&
                    !surface.keyDispatcherState.isTracking(event)
                ) {
                    surface.keyDispatcherState.startTracking(event, this)

                    if (!isPressed) {
                        if (scrollHackStarted) {
                            dispatchMotionEvent(
                                scrollHackCoords.x,
                                scrollHackCoords.y,
                                MotionEvent.ACTION_CANCEL
                            )
                            scrollHackStarted = false
                        }

                        isPressed = true
                        longPressTriggered = false
                        isVisible = true

                        // TV Bro : le clic commence exactement ici.
                        dispatchMotionEvent(cursorX, cursorY, MotionEvent.ACTION_DOWN)
                        longPressHandler.removeCallbacks(longPressRunnable)
                        longPressHandler.postDelayed(longPressRunnable, longPressTimeout)
                        surface.invalidate()
                    }
                }
                return true
            }

            KeyEvent.ACTION_UP -> {
                // Même gestion du cycle de vie de la touche que TV Bro.
                surface.keyDispatcherState.handleUpEvent(event)
                longPressHandler.removeCallbacks(longPressRunnable)

                if (isPressed && !longPressTriggered) {
                    // TV Bro : ACTION_UP sur le même point, avec le même downTime.
                    dispatchMotionEvent(cursorX, cursorY, MotionEvent.ACTION_UP)
                }

                isPressed = false
                longPressTriggered = false
                surface.invalidate()
                scheduleHide()
                return true
            }
        }
        return false
    }

    // ——————————————————————————————————————————————
    // Injection MotionEvent — même mécanisme que TV Bro
    // ——————————————————————————————————————————————

    private fun dispatchMotionEvent(x: Float, y: Float, action: Int, pointerId: Int = 0) {
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            downTime = SystemClock.uptimeMillis()
        }

        val eventTime = SystemClock.uptimeMillis()
        val properties = arrayOfNulls<MotionEvent.PointerProperties>(1)
        val pp1 = MotionEvent.PointerProperties()
        pp1.id = pointerId
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER
        properties[0] = pp1

        val pointerCoords = arrayOfNulls<MotionEvent.PointerCoords>(1)
        val pc1 = MotionEvent.PointerCoords()
        pc1.x = x
        pc1.y = y
        pc1.pressure = 1f
        pc1.size = 1f
        pointerCoords[0] = pc1

        val motionEvent = MotionEvent.obtain(
            downTime, eventTime, action, 1, properties, pointerCoords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )

        try {
            // C'est volontairement identique au mécanisme de TV Bro :
            // WebView reçoit un véritable flux tactile synthétique.
            surface.dispatchTouchEvent(motionEvent)
        } finally {
            motionEvent.recycle()
        }
    }

    // ——————————————————————————————————————————————
    // Utilitaires
    // ——————————————————————————————————————————————

    private fun bound(value: Float, max: Float): Float {
        return when {
            value > max -> max
            value < -max -> -max
            else -> value
        }
    }


    // ——————————————————————————————————————————————
    // Étape 6 – Pinch-to-zoom synthétique
    // ——————————————————————————————————————————————

    private var pinchZoomStartTime = 0L
    private val pinchZoomDuration = 1000L
    private var pinchZoomIn = true
    private val zoomFactor = 0.12f

    /** Zoom avant (pincer vers l'extérieur) */
    fun tryZoomIn() {
        generateZoomGesture(pinchIn = true)
    }

    /** Zoom arrière (pincer vers l'intérieur) */
    fun tryZoomOut() {
        generateZoomGesture(pinchIn = false)
    }

    /**
     * Génère une séquence de MotionEvent à deux pointeurs
     * pour simuler un geste de pinch-to-zoom.
     * Inspiré de TV Bro + technique StackOverflow classique.
     */
    private fun generateZoomGesture(pinchIn: Boolean) {
        if (pinchZoomStartTime != 0L) return  // déjà en cours

        // Annule un éventuel scroll hack / clic en cours
        if (scrollHackStarted) {
            dispatchMotionEvent(scrollHackCoords.x, scrollHackCoords.y, MotionEvent.ACTION_CANCEL)
            scrollHackStarted = false
        }
        if (isPressed) {
            dispatchMotionEvent(cursorX, cursorY, MotionEvent.ACTION_CANCEL)
            isPressed = false
        }

        pinchZoomIn = pinchIn
        pinchZoomStartTime = SystemClock.uptimeMillis()

        val cx = surface.width / 2f
        val cy = surface.height / 2f
        val delta = zoomFactor / 2f * surface.height
        val delta2 = delta / 2f

        val start1: PointF
        val start2: PointF
        if (pinchIn) {
            start1 = PointF(cx - delta2, cy - delta2)
            start2 = PointF(cx + delta2, cy + delta2)
        } else {
            start1 = PointF(cx - delta, cy - delta)
            start2 = PointF(cx + delta, cy + delta)
        }

        val properties = arrayOf(
            MotionEvent.PointerProperties().apply {
                id = 0
                toolType = MotionEvent.TOOL_TYPE_FINGER
            },
            MotionEvent.PointerProperties().apply {
                id = 1
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        )

        val coords = arrayOf(
            MotionEvent.PointerCoords().apply {
                x = start1.x; y = start1.y; pressure = 1f; size = 1f
            },
            MotionEvent.PointerCoords().apply {
                x = start2.x; y = start2.y; pressure = 1f; size = 1f
            }
        )

        // 1. ACTION_DOWN (premier doigt)
        var event = MotionEvent.obtain(
            pinchZoomStartTime, pinchZoomStartTime,
            MotionEvent.ACTION_DOWN, 1, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        surface.dispatchTouchEvent(event)
        event.recycle()

        // 2. ACTION_POINTER_DOWN (deuxième doigt)
        event = MotionEvent.obtain(
            pinchZoomStartTime, pinchZoomStartTime,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            2, properties, coords,
            0, 0, 1f, 1f, 0, 0, 0, 0
        )
        surface.dispatchTouchEvent(event)
        event.recycle()

        // 3. Animation des MOVE
        surface.post(pinchZoomRunnable)
    }

    private val pinchZoomRunnable = object : Runnable {
        override fun run() {
            if (pinchZoomStartTime == 0L) return

            val now = SystemClock.uptimeMillis()
            val cx = surface.width / 2f
            val cy = surface.height / 2f
            val delta = zoomFactor / 2f * surface.height
            val delta2 = delta / 2f

            val start1: PointF
            val start2: PointF
            val end1: PointF
            val end2: PointF

            if (pinchZoomIn) {
                start1 = PointF(cx - delta2, cy - delta2)
                start2 = PointF(cx + delta2, cy + delta2)
                end1 = PointF(cx - delta, cy - delta)
                end2 = PointF(cx + delta, cy + delta)
            } else {
                start1 = PointF(cx - delta, cy - delta)
                start2 = PointF(cx + delta, cy + delta)
                end1 = PointF(cx - delta2, cy - delta2)
                end2 = PointF(cx + delta2, cy + delta2)
            }

            val properties = arrayOf(
                MotionEvent.PointerProperties().apply {
                    id = 0; toolType = MotionEvent.TOOL_TYPE_FINGER
                },
                MotionEvent.PointerProperties().apply {
                    id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER
                }
            )

            val coords = arrayOf(
                MotionEvent.PointerCoords(),
                MotionEvent.PointerCoords()
            )

            if (now - pinchZoomStartTime < pinchZoomDuration) {
                val progress = (now - pinchZoomStartTime).toFloat() / pinchZoomDuration
                coords[0].apply {
                    x = start1.x + (end1.x - start1.x) * progress
                    y = start1.y + (end1.y - start1.y) * progress
                    pressure = 1f; size = 1f
                }
                coords[1].apply {
                    x = start2.x + (end2.x - start2.x) * progress
                    y = start2.y + (end2.y - start2.y) * progress
                    pressure = 1f; size = 1f
                }
                val event = MotionEvent.obtain(
                    pinchZoomStartTime, now,
                    MotionEvent.ACTION_MOVE, 2, properties, coords,
                    0, 0, 1f, 1f, 0, 0, 0, 0
                )
                surface.dispatchTouchEvent(event)
                event.recycle()
                surface.post(this)
            } else {
                coords[0].apply { x = end1.x; y = end1.y; pressure = 1f; size = 1f }
                coords[1].apply { x = end2.x; y = end2.y; pressure = 1f; size = 1f }

                var event = MotionEvent.obtain(
                    pinchZoomStartTime, now,
                    MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, properties, coords,
                    0, 0, 1f, 1f, 0, 0, 0, 0
                )
                surface.dispatchTouchEvent(event)
                event.recycle()

                event = MotionEvent.obtain(
                    pinchZoomStartTime, now,
                    MotionEvent.ACTION_UP, 1, properties, coords,
                    0, 0, 1f, 1f, 0, 0, 0, 0
                )
                surface.dispatchTouchEvent(event)
                event.recycle()

                pinchZoomStartTime = 0L
            }
        }
    }


    companion object {
        private const val CURSOR_HIDE_DELAY = 5000L
        private const val TAG = "CursorDrawer"

        /** Cadence fixe de la boucle de déplacement (~60 fps). Avant ce fix, la
         *  boucle tournait via post() sans délai, donc à une vitesse dépendant
         *  entièrement de la charge du thread UI — irrégulière selon le device. */
        private const val FRAME_INTERVAL_MS = 16L

        /** Plafond du delta-temps utilisé pour l'accélération. Sans lui, un
         *  décrochage du thread UI (rendu WebView, GC...) produisait un dTime
         *  démesuré à la reprise → saut instantané de vitesse au lieu d'une
         *  accélération progressive (le « zigzag » rapporté). */
        private const val MAX_DTIME_MS = 50L
    }
}
