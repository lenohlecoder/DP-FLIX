package com.dpflix.android.player

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

/**
 * Cache disque partagé pour le "tampon hybride" (§5.1, étape 5c : "segments écrits sur
 * disque via ExoPlayer avant lecture", activable/désactivable).
 *
 * Un seul [SimpleCache] pour tout le process appli, en singleton : Media3 interdit
 * d'ouvrir deux `SimpleCache` simultanément sur le même dossier (ça lève une
 * exception), et un singleton supprime tout risque si deux `PlayerController`
 * venaient à exister brièvement en même temps (transition rapide entre deux écrans
 * lecteur). Réutilisé d'un `PlayerController` à l'autre plutôt que fermé/rouvert à
 * chaque écran.
 *
 * Stocké sous `context.cacheDir` (et non `filesDir`) délibérément : c'est un tampon
 * jetable, pas une donnée à préserver — le système peut le vider sous lui tout seul en
 * cas de stockage bas, ce qui est un comportement acceptable (voire souhaitable) pour
 * ce cache, sans qu'on ait à gérer ce cas nous-mêmes.
 *
 * Limite assumée : la taille limite ([LeastRecentlyUsedCacheEvictor]) est figée au
 * moment de la création de ce singleton (premier lecteur ouvert depuis le lancement de
 * l'appli), à partir de la valeur de `diskCacheMaxSizeMb` lue à cet instant précis.
 * Comme pour le tampon RAM (§7 étape 5b), un changement de ce réglage en cours
 * d'utilisation ne s'applique qu'au prochain lancement de l'application — et
 * contrairement au tampon RAM, il n'y a ici aucun moyen de contourner cette limite en
 * ouvrant simplement un nouveau `PlayerController` : changer la taille d'un cache déjà
 * ouvert nécessiterait de le fermer complètement, ce qui couperait toute lecture en
 * cours. Documenté ici ; à revisiter plus tard si besoin réel.
 */
object MediaCacheProvider {

    private const val CACHE_DIR_NAME = "hybrid_media_cache"

    @Volatile
    private var cache: SimpleCache? = null

    /**
     * Retourne le cache partagé, en le créant au premier appel.
     *
     * [maxSizeBytes] <= 0 signifie "pas de limite" (choix délibéré de l'utilisateur,
     * réglage à 0 = illimité) : dans ce cas on utilise [NoOpCacheEvictor] (aucune
     * éviction automatique) plutôt que de traiter 0 comme une erreur.
     */
    fun get(context: Context, maxSizeBytes: Long): SimpleCache {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val created = buildCache(context.applicationContext, maxSizeBytes)
            cache = created
            return created
        }
    }

    private fun buildCache(appContext: Context, maxSizeBytes: Long): SimpleCache {
        val cacheDir = File(appContext.cacheDir, CACHE_DIR_NAME)
        val evictor = if (maxSizeBytes > 0) {
            LeastRecentlyUsedCacheEvictor(maxSizeBytes)
        } else {
            NoOpCacheEvictor()
        }
        // StandaloneDatabaseProvider : index du cache tenu dans sa propre base SQLite,
        // indépendante de `AppDatabase` (Room, étape 4a) — Media3 gère ce fichier lui-même,
        // rien à brancher côté Room.
        val databaseProvider = StandaloneDatabaseProvider(appContext)
        return SimpleCache(cacheDir, evictor, databaseProvider)
    }

    /**
     * Vide entièrement le cache disque ("Vider le cache", §5.1 — le bouton lui-même
     * arrive avec l'écran Réglages → Lecteur, à une étape ultérieure ; cette fonction est
     * prête à être appelée dès que ce bouton existera).
     *
     * Sûr à appeler même si aucun lecteur n'a encore ouvert le cache (ouvre alors un
     * cache vide, ne fait rien de plus).
     */
    fun clear(context: Context, maxSizeBytes: Long) {
        val simpleCache = get(context, maxSizeBytes)
        for (key in ArrayList(simpleCache.keys)) {
            simpleCache.removeResource(key)
        }
    }

    /**
     * Occupation actuelle du cache disque, en octets (section Diagnostic, §5.5, 6g-3).
     *
     * Contrairement à [get]/[clear], ne force PAS la création du cache : lit uniquement
     * [cache] s'il existe déjà. Un premier lancement de l'appli sans jamais avoir activé
     * le tampon hybride ne doit pas créer un dossier/index de cache vide juste pour
     * afficher "0 octet" sur l'écran Diagnostic — `null` distingue explicitement "jamais
     * ouvert" de "ouvert et vide".
     */
    fun currentSizeBytesOrNull(): Long? = cache?.cacheSpace
}
