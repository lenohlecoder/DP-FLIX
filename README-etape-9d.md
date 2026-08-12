# DP-Flix — Étape 9d : lien avec le reste de l'app (Guide TV)

Cette livraison contient **l'intégralité du projet** (étapes 2a→9c inchangées + les
ajouts de cette étape 9d). Rien n'a été retiré.

## Découpage de l'étape 9 (rappel)
9a (couche data) → 9b (squelette d'écran + accès depuis l'accueil) → 9c (navigation
temporelle + détail programme) → **9d (lien avec le reste de l'app — cette livraison,
dernière sous-étape de l'écran EPG)**.

## Ce qui a été fait

### Zapper depuis la grille
Le nom de chaîne, à gauche de chaque ligne, est désormais cliquable/validable et navigue
directement vers la lecture plein écran de cette chaîne — même route que le zapping
depuis l'accueil (`DpFlixDestination.PlayerFullscreen`), sans étape intermédiaire de
mini-aperçu (l'écran Guide TV n'embarque pas de mini-lecteur, contrairement à l'accueil).
Les cellules de programme gardent leur propre clic (ouverture du détail, inchangé depuis
9c) : seul le nom de chaîne zappe.

- `epg/EpgGuideScreen.kt` (mobile) : nom de chaîne rendu `clickable`.
- `epg/EpgGuideScreenTv.kt` (TV) : nom de chaîne converti en `Button` `tv-material3`
  (même famille que les cellules de programme et `ChannelCardTv` de l'accueil TV), pour
  le focus/clic D-pad natif.
- `EpgGuideScreen`/`EpgGuideScreenTv` gagnent un nouveau paramètre
  `onNavigateToPlayerFullscreen: (channelId: String) -> Unit`, branché dans
  `DpFlixNavHost`/`DpFlixTvNavHost` sur la même route que celle déjà utilisée par
  `HomeScreen`/`HomeScreenTv`.

### Message "Aucun guide TV disponible" harmonisé
Jusqu'ici (9b1/9c) l'écran affichait uniquement la raison technique brute
(`EpgLoadResult.Unavailable.reason`, ex. "Erreur réseau", "Aucune source EPG disponible
pour cette playlist"), sans le libellé standard utilisé partout ailleurs dans l'app
(Réglages → Guide TV → Statut, `EpgStatusSetting`/`EpgStatus.NONE`). Le libellé standard
**"Aucun guide TV disponible"** (nouvelle constante `EPG_UNAVAILABLE_LABEL`,
`epg/EpgGuideModels.kt`, réutilisée par les deux écrans) est maintenant affiché en tête,
avec la raison technique précise juste en dessous — même structure titre/sous-texte que
Réglages.

- `epg/EpgGuideScreen.kt` : nouveau composable `EpgUnavailableNotice`.
- `epg/EpgGuideScreenTv.kt` : nouveau composable `EpgUnavailableNoticeTv`.

## Volontairement hors périmètre
- **Mini-lecteur de l'accueil (§4.4)** : n'affiche toujours pas le programme en cours
  (aucune couche EPG branchée sur `HomeViewModel`/`HomeScreen`/`HomeScreenTv`) — ce
  n'était pas dans le périmètre de l'étape 9 (§4.6, écran Guide TV dédié), qui reste une
  extension séparée à évaluer plus tard si le besoin se confirme à l'usage. "Aucun guide
  TV disponible" n'y est donc pas non plus affiché littéralement : il n'y a rien à
  harmoniser sur un écran qui n'affiche aucune information EPG pour l'instant.
- **Bandeau d'heures synchronisé entre lignes** : toujours hors périmètre, voir 9c.

## Vérification faite ici
Fichiers Kotlin modifiés relus (imports, accolades/parenthèses/crochets équilibrés —
vérifié automatiquement, aucun déséquilibre cette fois). Seuls appelants
d'`EpgGuideScreen`/`EpgGuideScreenTv` (`DpFlixNavHost`/`DpFlixTvNavHost`) mis à jour avec
le nouveau paramètre `onNavigateToPlayerFullscreen`, sur le même modèle que
`onNavigateToPlayerFullscreen` déjà branché pour `HomeScreen`/`HomeScreenTv` (réutilise
`DpFlixDestination.PlayerFullscreen.createRoute`, aucune nouvelle destination). La
compilation réelle sera confirmée via Codemagic (pas de réseau disponible dans cet
environnement pour un build Gradle local).

## Étape 9 terminée
Avec 9d, l'écran Guide TV (§4.6) est complet selon le périmètre défini par le cahier des
charges : chargement EPG (9a), écran accessible depuis l'accueil (9b), navigation
temporelle + détail programme (9c), zapping + message harmonisé (9d). Restent hors
périmètre du §4.6 lui-même : le bandeau d'heures synchronisé (raffinement visuel possible)
et l'affichage du programme en cours dans le mini-lecteur de l'accueil (extension
séparée, §4.4).

## Prochaine étape
Retour à la feuille de route générale (§7) : étape 10, diagnostic temps réel.
