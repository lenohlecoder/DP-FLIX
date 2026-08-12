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
        val appRepository = (application as DpFlixApplication).container.appRepository
        setContent {
            DpFlixNavHost(appRepository = appRepository)
        }
    }
}
