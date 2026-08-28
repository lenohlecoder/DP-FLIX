package com.dpflix.android.player

import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy

/**
 * Politique de retry Media3 pour la "tolérance aux micro-coupures" (§6 : "retries
 * automatiques sur segments/manifeste/niveaux avant tout arrêt visible").
 *
 * Media3 retente déjà par défaut ([DefaultLoadErrorHandlingPolicy]) les chargements en
 * échec — segments, manifeste HLS principal (master playlist), et playlists de niveau
 * (media playlists, une par palier de qualité) — avec un backoff exponentiel plafonné,
 * avant de remonter une [androidx.media3.common.PlaybackException] fatale au player. Le
 * nombre de tentatives par défaut est pensé pour un usage VOD/streaming classique ;
 * cette sous-classe l'augmente uniformément (§6 ne demande pas de distinction fine par
 * type de chargement) pour absorber des coupures réseau plus longues avant qu'une
 * erreur ne devienne visible côté utilisateur — au prix d'un délai un peu plus long
 * avant qu'une vraie panne réseau prolongée ne remonte comme erreur fatale, ce qui est
 * le compromis recherché ici : mieux vaut un tampon qui patiente qu'un arrêt prématuré
 * sur un simple accroc réseau.
 *
 * Le délai *entre* deux tentatives (backoff exponentiel plafonné) n'est volontairement
 * pas modifié : c'est le comportement par défaut de Media3, jugé suffisant, et le
 * réécrire sans pouvoir le valider sur un vrai flux serait plus risqué qu'utile.
 *
 * Branché sur [DefaultMediaSourceFactory][androidx.media3.exoplayer.source.DefaultMediaSourceFactory]
 * via `setLoadErrorHandlingPolicy` dans [PlayerController] — s'applique donc à tous les
 * chargements HLS (segments, manifeste, niveaux), pas seulement à un type particulier.
 */
class ResilientLoadErrorHandlingPolicy(
    private val minimumRetryCount: Int = DEFAULT_MINIMUM_RETRY_COUNT
) : DefaultLoadErrorHandlingPolicy() {

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = minimumRetryCount

    companion object {
        /**
         * Media3 par défaut retente un nombre de fois plus faible et variable selon le
         * type de chargement avant d'abandonner. On uniformise à une valeur plus haute
         * pour tous les types (segment, manifeste, niveau) — cf. doc de classe.
         */
        const val DEFAULT_MINIMUM_RETRY_COUNT = 10
    }
}
