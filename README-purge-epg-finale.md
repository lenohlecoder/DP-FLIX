# Purge EPG — finalisation sur les 4 fichiers de la livraison du 06/08

Ce zip complète la livraison précédente ("purge EPG partielle, 2 fichiers modifiés")
en éliminant les derniers résidus du système EPG dans les 4 fichiers déjà fournis :

## home/HomeViewModel.kt
- Suppression du bloc de code mort commenté (résolution EPG du mini-lecteur,
  désactivée le 25/07 mais jamais retirée) et de sa doc associée.
- Suppression de `previewProgramJob` (Job devenu inutile) et de l'import `Job`.
- `dismissPreview()` ne référence plus ce job.
- `previewProgramTitle` reste à `null` par construction — le champ est **conservé**
  sur `HomeUiState` (fichier non fourni) car `HomeScreen.MiniPlayer` s'appuie dessus
  (`if (programTitle != null)`) ; le retirer casserait la compilation d'un fichier
  hors du périmètre de cette livraison.

## settings/SettingsScreen.kt et settings/SettingsScreenTv.kt
- `formatEpgTimestamp(Tv)` renommée en `formatDiagnosticTimestamp(Tv)` : cette
  fonction ne formatait déjà que des timestamps d'erreurs de diagnostic lecteur
  (§5.5), pas des données EPG — nom trompeur hérité de l'ancien code EPG.
- Commentaire de `PlaylistSelectorChips` mis à jour : ne mentionne plus la section
  EPG (§5.4), déjà retirée de l'écran Réglages ; seule la section Numérotation
  (§5.3) utilise encore ce sélecteur de playlist.
- Références résiduelles à `EpgXmlParser` dans les commentaires supprimées.

## settings/SettingsViewModel.kt
- Déjà propre (aucune référence EPG) — non modifié dans cette livraison.

## Vérification effectuée
- `grep -ri epg` sur les 4 fichiers : plus aucune occurrence, à l'exception de deux
  commentaires explicatifs volontaires signalant le retrait du système EPG.
- Accolades `{`/`}` comptées et équilibrées sur les 3 fichiers modifiés (contrôle
  syntaxique de base ; pas de compilation Gradle possible dans cet environnement,
  réseau désactivé — la compilation finale de contrôle reste à faire côté toi,
  ou lors d'un prochain tour avec les fichiers du dépôt complet).

## Toujours hors périmètre (fichiers non fournis)
- `player/PlayerScreen.kt`, `PlayerOsd.kt`, `PlayerZapping.kt`
- `nav/DpFlixDestination.kt`, `nav/DpFlixTvNavHost.kt`
