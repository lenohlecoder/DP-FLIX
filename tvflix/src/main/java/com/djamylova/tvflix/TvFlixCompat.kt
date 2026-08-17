package com.djamylova.tvflix

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.View

/**
 * Helpers de compatibilité pour les Android TV / stick anciens (API basses,
 * WebView système vieillissant, GPU limité).
 *
 * Étape 9 – Compat vieilles TV.
 */
object TvFlixCompat {

    private const val TAG = "TvFlixCompat"

    /**
     * Indique si on est probablement sur un device TV bas de gamme
     * (API basse ou peu de RAM).
     */
    fun isLowEndDevice(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return true // API < 22
        }
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                am.isLowRamDevice
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Applique le layer type le plus adapté.
     * Sur certains vieux SoC TV, HARDWARE provoque des artefacts ou des freezes.
     *
     * @return true si HARDWARE a été appliqué, false si SOFTWARE
     */
    fun applyBestLayerType(view: View, preferHardware: Boolean = true): Boolean {
        return try {
            if (preferHardware && !isLowEndDevice(view.context)) {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                Log.d(TAG, "LAYER_TYPE_HARDWARE applied")
                true
            } else {
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                Log.d(TAG, "LAYER_TYPE_SOFTWARE applied (low-end or preferHardware=false)")
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "setLayerType failed, trying SOFTWARE", e)
            try {
                view.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            } catch (e2: Exception) {
                Log.e(TAG, "Even SOFTWARE layer failed", e2)
            }
            false
        }
    }

    /**
     * Vitesse de curseur recommandée selon le device.
     * Sur bas de gamme on réduit un peu pour garder de la fluidité.
     */
    fun recommendedMaxSpeedPercent(context: Context): Int {
        return if (isLowEndDevice(context)) 70 else 100
    }

    fun recommendedAccelerationPercent(context: Context): Int {
        return if (isLowEndDevice(context)) 80 else 100
    }

    /**
     * Vérifie si le WebView système semble utilisable.
     * Sur de très vieux firmwares le package WebView peut être cassé.
     */
    fun isWebViewAvailable(context: Context): Boolean {
        return try {
            // Simple test : instancier un WebView
            val wv = android.webkit.WebView(context)
            wv.destroy()
            true
        } catch (e: Exception) {
            Log.e(TAG, "WebView not available on this device", e)
            false
        } catch (e: Error) {
            // Certaines ROM plantent en Error (MissingWebViewPackageException etc.)
            Log.e(TAG, "WebView Error on this device", e)
            false
        }
    }

    /**
     * Version du WebView système si disponible (pour logs / diagnostic).
     */
    fun getWebViewPackageName(context: Context): String? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.webkit.WebView.getCurrentWebViewPackage()?.packageName
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
