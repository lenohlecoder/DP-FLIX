package com.djamylova.tvflix.cursor

import android.content.Context
import android.graphics.Canvas
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

/**
 * FrameLayout custom qui :
 * - intercepte les événements D-pad / motion
 * - dessine un curseur virtuel par-dessus le contenu (WebView)
 * - force l’accélération matérielle (étape 2b)
 * - gère le déplacement continu du curseur (étape 3)
 * - injecte les clics souris (étape 4)
 *
 * Inspiré de TV Bro (CursorLayout + CursorDrawerDelegate).
 */
class CursorLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    /** Active / désactive le curseur (et le dessin) */
    var cursorEnabled: Boolean
        get() = !willNotDraw()
        set(value) {
            setWillNotDraw(!value)
            if (value) {
                cursorDrawer?.show()
            } else {
                cursorDrawer?.hide()
            }
            invalidate()
        }

    /** Délégué responsable du dessin, de la position et du déplacement */
    var cursorDrawer: CursorDrawer? = null
        private set

    /** Callback optionnel pour le host (clic long, etc.) – sera enrichi plus tard */
    var cursorCallback: CursorCallback? = null

    init {
        setWillNotDraw(false)

        // Étape 2b – Accélération matérielle forcée
        applyHardwareAcceleration()

        // Important pour recevoir les KeyEvent D-pad
        isFocusable = true
        isFocusableInTouchMode = true
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS

        if (!isInEditMode) {
            cursorDrawer = CursorDrawer(context, this).also { drawer ->
                drawer.onLongPress = { x, y ->
                    cursorCallback?.onCursorLongPress(x, y)
                }
                // Étape 9 : vitesses adaptées aux devices bas de gamme
                drawer.maxSpeedPercent =
                    com.djamylova.tvflix.TvFlixCompat.recommendedMaxSpeedPercent(context)
                drawer.accelerationPercent =
                    com.djamylova.tvflix.TvFlixCompat.recommendedAccelerationPercent(context)
            }
        }
    }

    private fun applyHardwareAcceleration() {
        // Étape 9 : choix intelligent HARDWARE vs SOFTWARE
        com.djamylova.tvflix.TvFlixCompat.applyBestLayerType(this, preferHardware = true)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (!isInEditMode && cursorEnabled) {
            cursorDrawer?.onSizeChanged(w, h)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Prend le focus pour recevoir les touches D-pad
        if (cursorEnabled) {
            requestFocus()
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!cursorEnabled || cursorDrawer == null) {
            return super.dispatchKeyEvent(event)
        }

        val handled = cursorDrawer!!.dispatchKeyEvent(event)
        return if (handled) true else super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        return super.dispatchGenericMotionEvent(event)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        if (!isInEditMode && cursorEnabled) {
            cursorDrawer?.dispatchDraw(canvas)
        }
    }

    // ——— API publique ———

    fun getCursorX(): Float = cursorDrawer?.cursorX ?: (width / 2f)
    fun getCursorY(): Float = cursorDrawer?.cursorY ?: (height / 2f)

    fun setCursorPosition(x: Float, y: Float) {
        cursorDrawer?.setPosition(x, y)
        invalidate()
    }

    /** Ajuste la vitesse max du curseur (100 = défaut) */
    fun setCursorMaxSpeedPercent(percent: Int) {
        cursorDrawer?.maxSpeedPercent = percent.coerceIn(20, 300)
    }

    /** Ajuste l’accélération (100 = défaut) */
    fun setCursorAccelerationPercent(percent: Int) {
        cursorDrawer?.accelerationPercent = percent.coerceIn(20, 300)
    }

    /** Zoom avant (pinch-to-zoom synthétique) */
    fun zoomIn() {
        cursorDrawer?.tryZoomIn()
    }

    /** Zoom arrière */
    fun zoomOut() {
        cursorDrawer?.tryZoomOut()
    }

    companion object {
        private const val TAG = "CursorLayout"
    }

    interface CursorCallback {
        fun onCursorLongPress(x: Float, y: Float) {}
    }
}
