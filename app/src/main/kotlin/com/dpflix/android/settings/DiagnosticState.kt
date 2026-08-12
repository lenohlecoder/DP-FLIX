package com.dpflix.android.settings

/**
 * État de la section "Diagnostic" (§5.5). Toutes les métriques listées par le cahier des
 * charges ont une vraie valeur depuis l'étape 10 — cette classe ne pose plus qu'une
 * **structure de champs optionnels**, `null` signifiant "pas de lecture en cours" plutôt
 * que "pas encore câblé".
 *
 * ## D'où vient chaque champ
 * [networkThroughputKbps], [streamResolution], [streamBitrateKbps], [segmentsSucceeded],
 * [segmentsFailed] et [recentErrors] viennent d'un `AnalyticsListener` branché depuis
 * l'étape 10 sur l'`ExoPlayer` d'un `PlayerController` réellement ouvert (voir
 * `PlayerController`/`PlayerMetricsBridge`). [bufferedBytes] reste la seule métrique de
 * lecture non mesurée : ExoPlayer ne mesure son tampon qu'en durée, pas en octets
 * téléchargés restants à jouer — voir `PlayerMetricsBridge.bufferedSeconds` pour le
 * détail. `null` volontaire plutôt qu'une valeur inventée : afficher un débit, un
 * bitrate ou "0 erreur" qui a l'air réel sans l'être serait trompeur (voir
 * [com.dpflix.android.settings.SettingsScreen] pour le texte affiché dans ce cas, "Non
 * disponible").
 *
 * [liveEdgeOffsetSeconds] et [bufferedSeconds] sont nativement exposés par ExoPlayer pour
 * tout flux live (`Player.getCurrentLiveOffset()`/`Player.getTotalBufferedDuration()`),
 * sans avoir besoin d'un `AnalyticsListener` — voir `PlayerController.currentLiveEdgeOffsetSeconds`/
 * `currentBufferedSeconds` et `PlayerMetricsBridge`, le pont qui relie le lecteur plein
 * écran (aucun lien direct autrement avec cet écran Réglages) à ces champs. `null` tant
 * qu'aucun lecteur plein écran n'a été ouvert depuis le lancement de l'app, ou après en
 * être sorti (le pont se réinitialise, voir `PlayerScreen`).
 *
 * [diskCacheUsedBytes] est différent : `MediaCacheProvider` (5c) est un singleton qui
 * persiste sur disque indépendamment de toute lecture active, donc sa taille actuelle est
 * trivialement mesurable sans lecteur ouvert — voir `MediaCacheProvider.currentSizeBytesOrNull`.
 * Il n'y avait aucune raison de le simuler alors qu'il est réellement disponible.
 *
 * ## Rafraîchissement (6g-4)
 * `SettingsScreen.DiagnosticSectionBody` relit cet état toutes les 1,5 s tant que la
 * section est affichée (`SettingsViewModel.refreshDiagnostics`), pour que tous ces champs
 * restent à jour même sans qu'aucun réglage ne change entre-temps — voir la doc de
 * `refreshDiagnostics`.
 *
 * @property networkThroughputKbps Débit réseau actuel du flux en cours, en kbit/s.
 *   `null` si aucune lecture en cours ou pas encore de première mesure reçue (étape 10).
 * @property bufferedSeconds Niveau de tampon actuel, en secondes. `null` si aucune
 *   lecture en cours (étape 10).
 * @property bufferedBytes Niveau de tampon actuel occupé en mémoire/disque, en octets.
 *   Reste `null` en permanence, voir la doc de classe (aucun équivalent natif fiable).
 * @property streamResolution Résolution du flux en cours (ex. "1920×1080"). `null` si
 *   aucune lecture en cours ou pas encore de piste vidéo annoncée (étape 10).
 * @property streamBitrateKbps Bitrate du flux en cours, en kbit/s. Même statut que
 *   [streamResolution] (même producteur).
 * @property liveEdgeOffsetSeconds Écart actuel par rapport au direct, en secondes (§6 :
 *   retard volontaire ciblé, jamais de rattrapage forcé). Réel depuis 8b (voir plus haut).
 * @property segmentsSucceeded Nombre de segments chargés avec succès depuis le début de
 *   la lecture en cours (§5.5). `null` si aucune lecture en cours ; `0` dès l'ouverture
 *   d'une lecture, avant même le premier segment (étape 10).
 * @property segmentsFailed Nombre de segments dont le chargement a échoué (avant retry
 *   éventuel, §6) depuis le début de la lecture en cours. Même statut que [segmentsSucceeded].
 * @property recentErrors Dernières erreurs rencontrées par le lecteur (§5.5), les plus
 *   récentes en tête. `null` = aucune lecture en cours ; liste vide = lecture en cours
 *   sans erreur récente — distinction volontaire, comme pour [diskCacheUsedBytes] `null`
 *   vs `0` (câblé depuis l'étape 10).
 * @property diskCacheUsedBytes Occupation actuelle du cache disque ExoPlayer (octets) —
 *   vraie mesure dès 6g-3. `null` uniquement si le cache n'a encore jamais été ouvert
 *   (aucune lecture avec tampon hybride actif depuis le lancement de l'appli) : distinct
 *   de 0 octet, qui signifierait un cache ouvert mais vide.
 * @property diskCacheMaxBytes Taille maximale configurée du cache disque
 *   (§5.1, `PlayerSettings.diskCacheMaxSizeMb`). `null` = illimité (réglage à 0).
 */
data class DiagnosticState(
    val networkThroughputKbps: Long? = null,
    val bufferedSeconds: Float? = null,
    val bufferedBytes: Long? = null,
    val streamResolution: String? = null,
    val streamBitrateKbps: Long? = null,
    val liveEdgeOffsetSeconds: Float? = null,
    val segmentsSucceeded: Int? = null,
    val segmentsFailed: Int? = null,
    val recentErrors: List<DiagnosticErrorEntry>? = null,
    val diskCacheUsedBytes: Long? = null,
    val diskCacheMaxBytes: Long? = null
)

/**
 * Une entrée du journal d'erreurs Diagnostic (§5.5, structure posée en 6g-4, réellement
 * alimentée depuis l'étape 10 par `PlayerController` — voir [DiagnosticState.recentErrors]).
 *
 * @property timestampMillis Horodatage de l'erreur (`System.currentTimeMillis()`).
 * @property message Message d'erreur, technique à ce stade (même choix que
 *   `PlayerUiState.Error.message`, voir sa doc : traduction en message utilisateur
 *   lisible différée à une étape ultérieure).
 */
data class DiagnosticErrorEntry(
    val timestampMillis: Long,
    val message: String
)
