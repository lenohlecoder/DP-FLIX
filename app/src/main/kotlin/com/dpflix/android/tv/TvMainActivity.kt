package com.dpflix.android.tv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import com.dpflix.android.DpFlixApplication
import com.dpflix.android.nav.DpFlixTvNavHost

/**
 * Point d'entrée TV (boîtier Android TV, télécommande / D-pad).
 *
 * Étape 7a : branchée sur le vrai graphe de navigation TV ([DpFlixTvNavHost]) — remplace
 * le banc de test ad hoc qui vivait ici depuis l'étape 2b/5a (écran "Hello DP-Flix" fixe,
 * boutons "Chaîne test 1/2" sans vraie navigation). Même transition que celle qu'avait
 * faite [com.dpflix.android.MainActivity] côté mobile à l'étape 6a — voir sa doc.
 *
 * Indépendant du point d'entrée mobile ([com.dpflix.android.MainActivity]), qui garde
 * son propre graphe de navigation ([com.dpflix.android.nav.DpFlixNavHost]) — seule la
 * couche de données ([DpFlixApplication.container]) est partagée entre les deux points
 * d'entrée, exactement comme avant cette sous-étape.
 *
 * Prérequis edge-to-edge : [WindowCompat.setDecorFitsSystemWindows] est appelé avec
 * `false` AVANT `setContent` — même raison que côté mobile (voir la doc de
 * [com.dpflix.android.MainActivity]) : sans cet appel, le système continuerait de
 * réserver la place des barres même une fois masquées par le mode immersif du
 * lecteur plein écran.
 */
class TvMainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val appRepository = (application as DpFlixApplication).container.appRepository
        setContent {
            DpFlixTvNavHost(appRepository = appRepository)
        }
    }
}
