package com.djamylova.tvflix.cursor

import android.content.Context
import android.graphics.Canvas
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.KeyCharacterMap
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.djamylova.tvflix.TvFlixCompat

/**
 * Surface TV unique : WebView + éventuel lecteur plein écran + curseur.
 *
 * Le point important est que le lecteur HTML5 plein écran reste DANS ce
 * CursorLayout. Le curseur conserve donc exactement le même repère de
 * coordonnées en mode normal, mini-player et plein écran.
 */
class CursorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var cursorEnabled: Boolean
        get() = !willNotDraw()
        set(value) {
            setWillNotDraw(!value)
            if (value) cursorDrawer?.show() else cursorDrawer?.hide()
            invalidate()
        }

    var cursorDrawer: CursorDrawer? = null
        private set

    var cursorCallback: CursorCallback? = null

    /** Callback du bouton Menu DP-FLIX situé éventuellement hors de cette surface. */
    var onMenuIconClicked: (() -> Unit)? = null

    private var menuIconBoundsInWindow: RectF? = null

    /** Vue vidéo HTML5 actuellement en plein écran, si présente. */
    private var fullscreenView: View? = null
    private var fullscreenCallback: (() -> Unit)? = null
    private var fullscreenChildIndex: Int = -1

    // Compatibilité télécommandes anciennes / box génériques :
    // certaines télécommandes n'envoient pas de KeyEvent DPAD mais des axes
    // joystick/hat via dispatchGenericMotionEvent(). On conserve un état par axe
    // et on transforme uniquement les transitions en vrais KeyEvent DPAD pour
    // réutiliser exactement le moteur d'accélération/clic du CursorDrawer.
    private var genericAxisXDirection = 0
    private var genericAxisYDirection = 0
    private val genericAxisPressThreshold = 0.35f
    private val genericAxisReleaseThreshold = 0.20f

    init {
        setWillNotDraw(false)
        TvFlixCompat.applyBestLayerType(this, preferHardware = true)
        isFocusable = true
        isFocusableInTouchMode = true
        // Le parent intercepte le D-pad dans dispatchKeyEvent(), mais le WebView
        // doit pouvoir donner le focus à un champ HTML pour afficher le clavier TV.
        descendantFocusability = FOCUS_AFTER_DESCENDANTS

        if (!isInEditMode) {
            cursorDrawer = CursorDrawer(context, this).also { drawer ->
                drawer.onLongPress = { x, y -> cursorCallback?.onCursorLongPress(x, y) }
                drawer.maxSpeedPercent = TvFlixCompat.recommendedMaxSpeedPercent(context)
                drawer.accelerationPercent = TvFlixCompat.recommendedAccelerationPercent(context)
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isInEditMode && cursorEnabled) cursorDrawer?.onSizeChanged(w, h)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (cursorEnabled) requestFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cursorEnabled || cursorDrawer == null) return super.dispatchKeyEvent(event)

        // Échappatoire garantie (28/08/2026) : le bouton Menu DP-FLIX doit rester
        // utilisable dans TOUS les cas, y compris quand isSoftwareKeyboardVisible()
        // pense (à tort ou à raison) qu'un clavier est affiché — sinon un champ caché/
        // auto-focus sur une page (voir README-cursor-ime-guard-2-4-5.md) peut bloquer
        // le curseur ET le menu en même temps, sans aucun moyen de s'en sortir.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) onMenuIconClicked?.invoke()
            return true
        }

        // Inspiré de TV Bro (DPADNavigationEventsAdapter.isSoftwareKeyboardVisible) :
        // tant que le clavier système est affiché (barre d'adresse/recherche en cours de
        // saisie), le D-pad ne doit PAS être capté par le curseur virtuel. Sans ce garde-fou,
        // les flèches gauche/droite dans le champ de texte ou la sélection des suggestions du
        // clavier étaient à la place interprétées comme un déplacement du curseur — la barre
        // d'adresse semblait "ne pas réagir" alors que le D-pad ne lui arrivait jamais.
        if (isSoftwareKeyboardVisible()) {
            return super.dispatchKeyEvent(event)
        }

        if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0 && isCursorOverMenuIcon()) {
                onMenuIconClicked?.invoke()
                return true
            }
            if (isCursorOverMenuIcon()) return true
        }

        return if (cursorDrawer!!.dispatchKeyEvent(event)) true else super.dispatchKeyEvent(event)
    }

    /** Vrai si l'IME (clavier système) est actuellement affiché au-dessus de cette surface. */
    private fun isSoftwareKeyboardVisible(): Boolean {
        return try {
            ViewCompat.getRootWindowInsets(rootView)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        } catch (e: Exception) {
            false
        }
    }

    fun setMenuIconBoundsInWindow(bounds: RectF?) {
        menuIconBoundsInWindow = bounds?.let(::RectF)
    }

    private fun isCursorOverMenuIcon(): Boolean {
        val bounds = menuIconBoundsInWindow ?: return false
        if (width <= 0 || height <= 0) return false
        val location = IntArray(2)
        getLocationInWindow(location)
        val x = getCursorX()
        val y = getCursorY()
        return x >= bounds.left - location[0] && x <= bounds.right - location[0] &&
            y >= bounds.top - location[1] && y <= bounds.bottom - location[1]
    }

    /**
     * Affiche une vue plein écran dans la même surface que le WebView.
     * La vue est ajoutée juste avant le calque du curseur (dessiné par dispatchDraw).
     */
    fun showFullscreenView(view: View, onExit: () -> Unit = {}) {
        if (fullscreenView === view) return
        hideFullscreenView(notify = false)

        (view.parent as? ViewGroup)?.removeView(view)
        fullscreenView = view
        fullscreenCallback = onExit
        fullscreenChildIndex = childCount

        addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        bringChildToFront(view)
        cursorDrawer?.onSizeChanged(width, height)
        requestFocus()
        invalidate()
    }

    /** Masque le lecteur plein écran et rend la WebView visible sans recréer le curseur. */
    fun hideFullscreenView(notify: Boolean = true) {
        val view = fullscreenView ?: return
        fullscreenView = null
        val callback = fullscreenCallback
        fullscreenCallback = null
        fullscreenChildIndex = -1
        removeView(view)
        cursorDrawer?.onSizeChanged(width, height)
        requestFocus()
        invalidate()
        if (notify) callback?.invoke()
    }

    fun isFullscreenViewShown(): Boolean = fullscreenView != null

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (!cursorEnabled || cursorDrawer == null) {
            return super.dispatchGenericMotionEvent(event)
        }

        // Même garde-fou clavier système que dispatchKeyEvent ci-dessus.
        if (isSoftwareKeyboardVisible()) {
            return super.dispatchGenericMotionEvent(event)
        }

        // Ne jamais détourner les MotionEvent tactiles/souris ordinaires.
        // Les vieilles box concernées exposent généralement la télécommande comme
        // JOYSTICK/DPAD et envoient AXIS_HAT_* ou AXIS_X/Y.
        val source = event.source
        val isRemoteAxisSource =
            (source and InputDevice.SOURCE_CLASS_JOYSTICK) != 0 ||
                (source and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD

        if (!isRemoteAxisSource || event.action != MotionEvent.ACTION_MOVE) {
            return super.dispatchGenericMotionEvent(event)
        }

        val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        val rawX = if (kotlin.math.abs(hatX) > 0.01f) hatX
                   else event.getAxisValue(MotionEvent.AXIS_X)
        val rawY = if (kotlin.math.abs(hatY) > 0.01f) hatY
                   else event.getAxisValue(MotionEvent.AXIS_Y)

        genericAxisXDirection = updateAxisDirection(rawX, genericAxisXDirection)
        genericAxisYDirection = updateAxisDirection(rawY, genericAxisYDirection)

        dispatchGenericDirection(
            genericAxisXDirection,
            genericAxisYDirection
        )
        return true
    }

    private fun updateAxisDirection(value: Float, previous: Int): Int {
        val abs = kotlin.math.abs(value)
        if (previous != 0 && abs < genericAxisReleaseThreshold) return 0
        if (abs < genericAxisPressThreshold) return 0
        return if (value > 0f) 1 else -1
    }

    private fun dispatchGenericDirection(x: Int, y: Int) {
        val oldX = genericDispatchedX
        val oldY = genericDispatchedY

        if (x == oldX && y == oldY) return

        // Libération des axes précédemment actifs.
        if (oldX != 0) dispatchSyntheticDpad(
            if (oldX < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.ACTION_UP
        )
        if (oldY != 0) dispatchSyntheticDpad(
            if (oldY < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.ACTION_UP
        )

        genericDispatchedX = x
        genericDispatchedY = y

        // Activation des nouveaux axes. Deux axes simultanés donnent naturellement
        // une diagonale puisque CursorDrawer maintient les deux directions.
        if (x != 0) dispatchSyntheticDpad(
            if (x < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.ACTION_DOWN
        )
        if (y != 0) dispatchSyntheticDpad(
            if (y < 0) KeyEvent.KEYCODE_DPAD_UP else KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.ACTION_DOWN
        )
    }

    private var genericDispatchedX = 0
    private var genericDispatchedY = 0

    private fun dispatchSyntheticDpad(keyCode: Int, action: Int) {
        val now = android.os.SystemClock.uptimeMillis()
        val event = KeyEvent(
            now,
            now,
            action,
            keyCode,
            0,
            0,
            KeyCharacterMap.VIRTUAL_KEYBOARD,
            0,
            KeyEvent.FLAG_VIRTUAL_HARD_KEY,
            InputDevice.SOURCE_DPAD
        )
        cursorDrawer?.dispatchKeyEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isInEditMode && cursorEnabled) cursorDrawer?.dispatchDraw(canvas)
    }

    fun getCursorX(): Float = cursorDrawer?.cursorX ?: width / 2f
    fun getCursorY(): Float = cursorDrawer?.cursorY ?: height / 2f

    fun setCursorPosition(x: Float, y: Float) {
        cursorDrawer?.setPosition(x, y)
        invalidate()
    }

    fun setCursorMaxSpeedPercent(percent: Int) {
        cursorDrawer?.maxSpeedPercent = percent.coerceIn(20, 300)
    }

    fun setCursorAccelerationPercent(percent: Int) {
        cursorDrawer?.accelerationPercent = percent.coerceIn(20, 300)
    }

    fun zoomIn() { cursorDrawer?.tryZoomIn() }
    fun zoomOut() { cursorDrawer?.tryZoomOut() }

    override fun onDetachedFromWindow() {
        genericAxisXDirection = 0
        genericAxisYDirection = 0
        genericDispatchedX = 0
        genericDispatchedY = 0
        hideFullscreenView(notify = false)
        super.onDetachedFromWindow()
    }

    interface CursorCallback {
        fun onCursorLongPress(x: Float, y: Float) {}
    }
}
