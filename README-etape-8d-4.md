# DP-Flix — Étape 8d-4 : Volume — décision et branchement réel (§4.5/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-3 inchangées + les
ajouts de cette sous-étape 8d-4). Rien n'a été retiré.

## Décision tranchée (annoncée "pas neutre" dans le découpage de 8d)
**`AudioManager` (volume système, `STREAM_MUSIC`)**, plutôt que `ExoPlayer.volume`.

Sur la quasi-totalité des box IPTV et apps de streaming, le curseur affiché à l'écran
*est* le volume système — celui des boutons physiques de l'appareil. C'est ce qu'un
utilisateur attend en priorité. Un volume interne au lecteur (`ExoPlayer.volume`)
créerait deux réglages distincts et déroutants (curseur OSD ≠ boutons physiques du
téléphone/de la TV) ; il resterait pertinent pour du mixage multi-lecteurs (pas le cas
ici, un seul flux joue à la fois) ou un mute scope à un composant précis — hors besoin
de 8d.

**Conséquence directe et positive de ce choix, qui recadre la sous-étape 8d-5** : la
persistance du volume entre deux plein écrans, annoncée dans le découpage de 8d comme une
sous-étape à part, est déjà assurée **gratuitement** par le système lui-même —
`AudioManager.getStreamVolume` reste vrai tant que l'utilisateur n'a pas changé le volume
ailleurs, pas de `DataStore` à écrire. 8d-5 se concentrera donc sur la cohérence
*inverse* : suivre les changements de volume externes (boutons physiques pressés pendant
que l'OSD est visible) pour que le curseur ne se désynchronise pas — pas traité ici.

Aucune permission supplémentaire requise : `setStreamVolume(STREAM_MUSIC, index, 0)`
(flags à `0`, pas d'UI système superposée) ne nécessite pas de déclaration manifeste
particulière.

## Ce qui a été fait

### `PlayerScreen` : état et branchement `AudioManager`
- `audioManager` (`remember`, `Context.AUDIO_SERVICE`) et `maxStreamVolume`
  (`getStreamMaxVolume(STREAM_MUSIC)`, borné à 1 minimum par précaution division).
- `volumeFraction` (`0f..1f`) : initialisé une fois depuis `getStreamVolume` réel au
  montage de l'écran — répond de fait au besoin "valeur initiale au démarrage" du
  découpage de 8d, sans sous-étape séparée (conséquence du choix ci-dessus).
- `setSystemVolume(fraction)` : met à jour `volumeFraction` (retour visuel immédiat du
  curseur) **et** appelle `audioManager.setStreamVolume` (effet réel).
- Pas `remember(channel.id)` (contrairement à `currentChannel`/`nowMillis`/...) : le
  volume système n'a aucun rapport avec la chaîne affichée, un zap ne doit pas
  réinitialiser le curseur.

### `PlayerOsd`/`VolumeSlider` (modifié)
`VolumeSlider` redevient un composable de pur rendu, cohérent avec le reste du fichier :
le `remember` interne de 8d-3 disparaît, remplacé par deux paramètres pilotés par
`PlayerScreen` — `volumeFraction: Float` et `onVolumeChange: (Float) -> Unit` — même
schéma que `isPlaying`/`onTogglePlayPause` (8d-1).

## Volontairement hors périmètre à cette sous-étape
- Suivi des changements de volume externes (boutons physiques) pendant que l'OSD est
  affiché : sous-étape 8d-5.
- D-pad TV sur le curseur : toujours pas de focus posé dessus (8d-10).
- Qualité manuelle : sous-étapes suivantes (8d-6 à 8d-8).

## Validation de fin de sous-étape 8d-4
- `./gradlew compileDebugKotlin` doit réussir.
- Ouvrir le plein écran avec un volume système donné : le curseur OSD reflète bien ce
  volume dès l'ouverture (pas de saut visuel).
- Glisser le curseur : le volume réel de l'appareil change en direct (vérifiable via les
  boutons physiques, qui doivent alors afficher/piloter la même valeur).
- Zapper (8c) pendant que l'OSD est visible : le curseur ne se réinitialise pas.
- Mini-lecteur : comportement inchangé, `PlayerOsd` n'y est toujours jamais rendu.

Prochaine sous-étape (8d-5) : suivi des changements de volume externes (boutons
physiques) pour garder le curseur synchronisé.
