# DP-Flix — Étape 9a : cache du guide EPG (§4.6)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-9 inchangées + les
ajouts de cette sous-étape 9a). Rien n'a été retiré.

## Découpage de l'étape 9 (rappel)
9a (cette livraison, couche data) → 9b (squelette d'écran + accès depuis l'accueil,
mobile + TV ensemble) → 9c (navigation temporelle + détail programme) → 9d (lien avec le
reste de l'app : zapper depuis la grille, message "Aucun guide TV disponible").

## Pourquoi commencer par la couche data
En reprenant l'existant avant de coder, deux orchestrations EPG dupliquées et
incohérentes entre elles :
- `SettingsViewModel.refreshEpg` (Réglages, 6g-2-2) téléchargeait/lisait et parsait déjà
  une source EPG, mais **ne gardait rien** du résultat une fois validé.
- `EpgNowLookup` (OSD "programme en cours", 8b) refaisait tout de zéro à chaque prise
  d'antenne, **sans aucun cache** — sa propre doc notait déjà "à revoir si un futur écran
  de grille EPG multiplie les appels sur le même guide", exactement la situation
  qu'ouvre l'étape 9.
- **Bug découvert au passage** : `EpgNowLookup` visait `Playlist.manualEpgLocalFilePath`,
  un champ qui **n'a jamais existé** (le vrai champ est `manualEpgLocalFileUri`, une `Uri`
  `content://` à lire via `ContentResolver`, pas un chemin de fichier lisible par
  `java.io.File`) — resté invisible tant qu'aucun appelant réel ne forçait à recompiler
  ce chemin de code, donc jamais détecté avant cette relecture.

Généraliser ces deux chemins en une seule couche partagée était donc un préalable, pas
une option, avant de construire un écran de grille qui aurait sinon hérité des mêmes
défauts (pas de cache) en pire (beaucoup plus d'appels).

## Ce qui a été fait

### `repository/EpgRepository.kt` (nouveau)
Cache en mémoire par playlist (`Map<playlistId, EpgLoadResult>`), invalidé uniquement par
appel explicite (`refresh`/`invalidate`) — pas de TTL automatique : un guide XMLTV change
rarement plus de quelques fois par jour, et le cache est de toute façon vidé à chaque
relance de l'app (rien de persisté sur disque, cohérent avec le choix déjà pris de ne
stocker aucun `EpgProgram` en base). API : `cached`, `getOrLoad` (cache s'il existe, sinon
recharge — le point d'entrée normal pour un simple affichage), `refresh` (forcé, y
compris sur échec — un guide périmé ne doit pas laisser croire à un contenu à jour),
`invalidate`. Ajouté à `AppRepository`/`AppContainer` comme les trois repositories
existants (playlists/channels/settings), même singleton process.

### `settings/SettingsViewModel.kt` (modifié)
`refreshEpg` délègue maintenant entièrement à `EpgRepository.refresh` — sa propre
orchestration réseau/SAF (téléchargement OkHttp, lecture `ContentResolver`,
`resolveEpgSource`/`EpgSource`) est supprimée, ainsi que le paramètre `httpClient` du
constructeur, devenu inutile. Comportement UI strictement inchangé
(`epgRefreshInProgress`/`epgRefreshError`, `PlaylistRepository.setLastEpgUpdateMillis` sur
succès). `setManualEpgUrl`/`setManualEpgLocalFile`/`clearManualEpgSource` invalident
maintenant aussi le cache de la playlist concernée : sans ça, changer de source EPG
manuelle aurait laissé l'ancien guide en cache jusqu'au prochain "Rafraîchir" explicite.

### `player/PlayerScreen.kt` (modifié) + suppression d'`EpgNowLookup.kt`
Le calcul du "programme en cours" (§4.6/8b) passe par `appRepository.epg.getOrLoad(playlist)`
au lieu d'`EpgNowLookup` (supprimé, entièrement remplacé — et son bug avec). Comportement
inchangé pour l'utilisateur (une seule résolution par prise d'antenne/zap, pas par tick),
simplement servi par le cache partagé désormais.

## Volontairement hors périmètre
- Tout écran de grille EPG (squelette 9b, navigation temporelle/détail 9c, lien zapping
  9d) : cette sous-étape ne construit que la couche data.
- Persistance disque du guide (Room) : toujours pas nécessaire, le cache mémoire suffit
  aux consommateurs actuels (OSD) et à ceux prévus par 9b-9d — à revisiter seulement si un
  besoin réel de survie inter-lancements apparaît à l'usage.
- Rafraîchissement périodique en arrière-plan : non demandé par le cahier des charges,
  qui ne prévoit qu'un bouton "Rafraîchir" manuel (§5.4) — cohérent avec l'absence de TTL
  ci-dessus.

## Vérification faite ici
Fichiers Kotlin relus (imports, accolades/parenthèses équilibrées — vérifié
automatiquement sur tous les fichiers touchés). Recherche explicite de toute référence
résiduelle à `EpgNowLookup` et au champ inexistant `manualEpgLocalFilePath` avant de
considérer cette sous-étape terminée : plus aucune occurrence en dehors des commentaires
qui documentent le remplacement. Tous les sites de construction d'`AppRepository` et de
`SettingsViewModel` vérifiés (un seul chacun : `AppContainer`, `SettingsViewModelFactory`)
pour confirmer qu'aucun autre appelant ne serait cassé par les changements de signature.
La compilation réelle sera confirmée via Codemagic.

## Prochaine étape
9b — Squelette d'écran EPG (mobile + TV) : nouvelle destination NavHost accessible depuis
l'accueil, grille brute avec données réelles (chaînes en lignes, créneaux horaires en
colonnes), pas encore de sélection/détail.
