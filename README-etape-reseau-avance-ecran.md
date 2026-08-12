# Écran de saisie du réseau avancé (Réglages → Playlists)

## Contexte
Les 4 champs `customReferer`, `customUserAgent`, `proxyHost`, `proxyPort` de `Playlist`
(ajoutés le 24 juillet, déjà actifs en lecture côté `IptvHttpDataSourceFactory` /
`PlayerController`) n'avaient pas d'écran : il fallait les écrire directement en base.

## Ce qui change
- `SettingsViewModel.renamePlaylist` → `updatePlaylistEdits` : prend maintenant le nom
  **et** les 4 champs réseau avancés. Toujours pas de ré-édition de la source (URL,
  identifiants Xtream, fichier M3U) — seul « supprimer + ajouter » couvre ce cas, comme
  avant.
- Mobile (`SettingsScreen.kt`) et TV (`SettingsScreenTv.kt`) : le dialog « Renommer la
  playlist » devient « Modifier la playlist » (`EditPlaylistDialog` / `EditPlaylistDialogTv`),
  avec en plus une section repliable **« Réseau avancé (optionnel) »**, repliée par défaut,
  contenant les 4 champs :
  - Referer forcé
  - User-Agent forcé
  - Hôte du proxy
  - Port du proxy (clavier numérique, non numérique → ignoré)
- Chaîne vide = pas de valeur forcée (`null` en base, cascade automatique inchangée).
  Port invalide/vide = proxy dédié désactivé.

## Vérifications faites
- Accolades/parenthèses équilibrées sur les 3 fichiers touchés.
- Plus aucune référence à `renamePlaylist` / `RenamePlaylistDialog` dans le code (une
  mention dans un commentaire de `EpgGuideScreen.kt` corrigée).
- Signatures des callbacks alignées mobile ↔ TV ↔ ViewModel.

## Pas encore fait
- Aucun affichage dans `PlaylistRow`/`PlaylistRowTv` indiquant qu'un réseau avancé est
  déjà configuré (ex. un badge) — la section reste repliée sans indice visuel qu'elle
  contient des valeurs. À évaluer si ça manque à l'usage.
