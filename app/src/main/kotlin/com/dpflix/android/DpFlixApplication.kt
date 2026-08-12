package com.dpflix.android

import android.app.Application
import com.dpflix.android.di.AppContainer

/**
 * Point d'entrée process (§7 étape 6a). Le seul rôle de cette classe est de construire
 * [AppContainer] une fois pour toute la durée de vie de l'app, avant que la première
 * `Activity` ([MainActivity] ou [com.dpflix.android.tv.TvMainActivity]) ne démarre.
 *
 * Déclarée dans `AndroidManifest.xml` via `android:name=".DpFlixApplication"`.
 */
class DpFlixApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
