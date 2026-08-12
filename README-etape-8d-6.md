# DP-Flix — Étape 8d-6 : Qualité manuelle — décision et liste (§4.5/§5.1/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-5 inchangées + les
ajouts de cette sous-étape 8d-6). Rien n'a été retiré.

## Décision de principe (annoncée "à confirmer" dans le découpage de 8d)
**Retenue : oui**, on ajoute un choix manuel de qualité en plus de l'ABR automatique déjà
en place (§5.1, étape 5c). Le §4.5/§5.1 du cahier des charges ne l'imposait pas
explicitement — seule l'ABR y est mentionnée — mais c'est un contrôle standard sur la
quasi-totalité des lecteurs IPTV : utile quand l'ABR se trompe (reste en basse qualité sur
un réseau qui tiendrait mieux) ou pour économiser sciemment de la donnée. Approche
retenue : un **plafond de résolution** (l'ABR reste libre de descendre en dessous si le
réseau l'exige, mais ne dépasse jamais la hauteur choisie) plutôt qu'un ciblage figé
d'une piste précise — voir la doc de `QualityOption` pour la justification technique
complète, et 8d-8 pour l'implémentation de l'override lui-même.

## Ce qui a été fait

### `PlayerController` : `QualityOption` et `availableQualities` (nouveau)
- `data class QualityOption(val height: Int)` avec un `label` calculé (`"1080p"`, etc.).
  Volontairement réduit à la hauteur : suffisant pour l'affichage à venir (8d-7) et pour
  l'override par plafond de résolution (8d-8, `setMaxVideoSize`) — pas besoin du bitrate
  exact ni d'un identifiant de groupe/piste Media3 pour cette approche.
- `availableQualities: StateFlow<List<QualityOption>>` — recalculée à chaque
  `Player.Listener.onTracksChanged` (nouvelle fonction privée `updateAvailableQualities`) :
  ne garde que les pistes vidéo, déduplique par hauteur, trie du plus haut au plus bas.
  Remise à vide au début de chaque `playChannel` (nouvelle chaîne = anciennes pistes plus
  valides), le temps que le nouveau flux réannonce les siennes.
- Aucun filtre sur `isTrackSupported` : une piste que le décodeur ne sait pas jouer n'a de
  toute façon aucune chance d'être choisie par `DefaultTrackSelector`, l'exclure de la
  liste n'aurait changé aucun comportement perçu.

### `PlayerScreen` : plomberie backend seule, sans UI
`availableQualities` collecté (`collectAsState`) mais **pas encore transmis à
`PlayerOsd`** — son affichage réel arrive à 8d-7. Un `Log.d` temporaire journalise la
liste dès qu'elle devient non vide, comme témoin de validation en attendant le rendu
réel ; à retirer quand 8d-7 l'aura remplacé par un vrai affichage.

## Volontairement hors périmètre à cette sous-étape
- Affichage dans l'OSD : 8d-7.
- Override réel (`setMaxVideoSize`) et retour automatique en ABR : 8d-8.

## Validation de fin de sous-étape 8d-6
- `./gradlew compileDebugKotlin` doit réussir.
- Lecture d'une chaîne dont le flux HLS expose plusieurs variantes de débit : le logcat
  (filtre `PlayerScreen`) affiche `Qualites disponibles (8d6) : [QualityOption(height=...),
  ...]`, triées décroissantes, sans doublon de hauteur.
- Zapper vers une autre chaîne : une nouvelle ligne de log apparaît une fois les pistes du
  nouveau flux connues (léger délai, le temps que `onTracksChanged` se redéclenche).
- Chaîne mono-débit (une seule variante, ou flux non-HLS) : liste vide, aucun log (cas
  attendu, pas une erreur).
- Aucun changement de comportement visible dans l'OSD à ce stade.

Prochaine sous-étape (8d-7) : affichage de la liste de résolutions dans l'OSD.
