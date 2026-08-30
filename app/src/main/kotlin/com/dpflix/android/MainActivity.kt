package com.dpflix.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.dpflix.android.nav.DpFlixNavHost

/**
 * Point d'entrée MOBILE (téléphone / tablette, interaction tactile).
 *
 * Étape 6a : branchée sur le vrai graphe de navigation ([DpFlixNavHost]) — remplace le
 * banc de test ad hoc de l'étape 5a, désormais intégré au graphe lui-même (voir la doc de
 * [DpFlixNavHost] pour le détail de cette transition). Indépendant du point d'entrée TV
 * ([com.dpflix.android.tv.TvMainActivity]), qui garde sa propre UI (Compose for TV,
 * étape 7) — seule la couche de données ([DpFlixApplication.container]) est partagée
 * entre les deux points d'entrée.
 *
 * Prérequis edge-to-edge : [WindowCompat.setDecorFitsSystemWindows] est appelé avec
 * `false` AVANT `setContent` — indispensable pour que le mode immersif du lecteur
 * plein écran ([com.dpflix.android.player.PlayerScreen]) fonctionne réellement : sans
 * cet appel, le système continue de réserver la place des barres système même une
 * fois celles-ci masquées via `WindowInsetsControllerCompat.hide`, laissant une bande
 * noire vide à leur emplacement.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val container = (application as DpFlixApplication).container
        // Branchement Dreaming (30 août 2026, § demande utilisateur) : tap sur la
        // notification système postée par DreamingNotificationPoller pendant que l'app
        // est fermée/en arrière-plan → relance cette Activity avec cet extra, lu une
        // seule fois par DpFlixNavHost pour ouvrir directement l'écran Notifications
        // plutôt que de retomber sur le flux normal (Splash → verrou → StartupVideo →
        // Home). Même mécanique que côté TV (voir TvMainActivity).
        val openDreaming = intent?.getBooleanExtra(EXTRA_OPEN_DREAMING, false) == true
        setContent {
            DpFlixNavHost(
                appRepository = container.appRepository,
                accessRepository = container.accessRepository,
                activePlayerHolder = container.activePlayerHolder,
                dreamingRepository = container.dreamingRepository,
                dreamingState = container.dreamingState,
                openDreamingOnStart = openDreaming
            )
        }
    }

    companion object {
        const val EXTRA_OPEN_DREAMING = "open_dreaming"
    }
}
