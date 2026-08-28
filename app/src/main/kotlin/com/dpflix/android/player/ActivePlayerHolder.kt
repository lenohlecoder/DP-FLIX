package com.dpflix.android.player

import android.os.Handler
import android.os.Looper

/**
 * Point d'enregistrement process-scoped du [PlayerController] actuellement actif.
 *
 * Permet au NavHost (garde d'accès) d'appeler [releaseIfAny] **avant** de naviguer vers
 * l'écran de verrouillage, plutôt que de compter uniquement sur le `DisposableEffect` de
 * [PlayerScreen] (qui ne se déclenche qu'en réaction au démontage, donc en parallèle du
 * vidage du back stack — course possible avec ExoPlayer / scopes / purge).
 *
 * Contrat :
 * - [register] écrase l'instance précédente (un seul lecteur "actif" à la fois) ;
 * - [unregister] ne clear que si c'est la même instance (évite qu'un écran qui se démonte
 *   efface le controller du nouvel écran déjà enregistré) ;
 * - [releaseIfAny] est sûr à appeler plusieurs fois ; [PlayerController.release] doit
 *   rester idempotent.
 */
class ActivePlayerHolder {

    @Volatile
    private var active: PlayerController? = null

    fun register(controller: PlayerController) {
        active = controller
    }

    fun unregister(controller: PlayerController) {
        if (active === controller) {
            active = null
        }
    }

    /**
     * Libère le contrôleur actif s'il y en a un. S'assure d'exécuter [PlayerController.release]
     * sur le main looper (exigence ExoPlayer).
     */
    fun releaseIfAny() {
        val controller = active ?: return
        active = null
        val runRelease = { controller.release() }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runRelease()
        } else {
            Handler(Looper.getMainLooper()).post(runRelease)
        }
    }
}
