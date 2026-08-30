package com.dpflix.android

import android.util.Log
import com.dpflix.android.di.AppContainer
import com.phlox.tvwebbrowser.TVBro
import com.dpflix.android.dreaming.DreamingNotificationManager
import com.dpflix.android.settings.DiagnosticSystemMonitor
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Point d'entrée process (§7 étape 6a). Construit [AppContainer] une fois pour la durée
 * de vie de l'app, avant la première Activity.
 *
 * ## Logging local des plantages
 * [Thread.setDefaultUncaughtExceptionHandler] écrit la stack dans
 * `filesDir/last_crash.txt` puis délègue à l'handler système (app 100 % offline).
 */
class DpFlixApplication : TVBro() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
        // Créé avant AppContainer (dont l'init démarre déjà le poller Dreaming) pour que
        // le canal existe dans les réglages système dès le premier lancement, pas
        // seulement à la première notification effectivement affichée.
        DreamingNotificationManager.ensureChannel(this)
        container = AppContainer(this)
        DiagnosticSystemMonitor.initialize(this)
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
                val body = buildString {
                    appendLine("timestamp=$stamp")
                    appendLine("thread=${thread.name}")
                    appendLine(sw.toString())
                }
                File(filesDir, CRASH_FILE_NAME).writeText(body)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write local crash log", e)
            }
            previous?.uncaughtException(thread, throwable)
        }
    }

    companion object {
        private const val TAG = "DpFlixApplication"
        const val CRASH_FILE_NAME = "last_crash.txt"
    }
}
