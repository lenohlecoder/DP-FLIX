# DP-Flix — Étape 8b : OSD — infos direct (§4.5)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8a inchangées + les
ajouts de cette sous-étape 8b). Rien n'a été retiré.

## Ce qui a été fait
- Heure courante (`HH:mm`), affichée à l'opposé du bloc logo+nom dans le bandeau OSD.
- Écart au direct (§5.5/§6), affiché sous le bandeau — "Direct" si l'écart est quasi nul,
  "Écart au direct : indisponible" tant qu'il n'est pas encore connu (juste après un
  zapping, avant `STATE_READY`).
- Programme en cours (§4.6), affiché à côté de l'écart au direct si l'EPG est disponible
  pour la chaîne — absent silencieusement sinon (pas d'EPG, chaîne sans `tvgId`, source
  injoignable).

## Le vrai sujet de cette sous-étape : relier l'OSD au Diagnostic sans réécrire tout `DiagnosticState`
Le brief demandait l'écart au direct "relié au Diagnostic (§5.5)". Jusqu'ici,
`DiagnosticState.liveEdgeOffsetSeconds` restait `null` faute de lien entre un
`PlayerController` réellement ouvert (écran lecteur) et l'écran Réglages, qui n'ont ni
ViewModel ni navigation en commun. Deux options :
1. Câbler un vrai `AnalyticsListener` sur l'`ExoPlayer` pour l'ensemble des métriques de
   `DiagnosticState` (débit, bitrate, segments...) — la sous-étape d'instrumentation
   complète évoquée après 6g-4, toujours pas planifiée.
2. Ne câbler que l'écart au direct, seule métrique qu'ExoPlayer expose déjà nativement
   sans instrumentation (`Player.getCurrentLiveOffset()`), suffisant pour ce que 8b
   demande.

Choix retenu : l'option 2. `PlayerController.currentLiveEdgeOffsetSeconds()` lit cette
valeur native ; `PlayerMetricsBridge` (nouveau, `player/PlayerMetricsBridge.kt`) est un
pont à une seule métrique — un simple `StateFlow<Float?>` que le lecteur plein écran met
à jour toutes les secondes et que `SettingsViewModel.diagnosticState()` relit à chaque
rafraîchissement Diagnostic (6g-4, inchangé sinon). Les autres champs de
`DiagnosticState` restent `null`, toujours en attente de la sous-étape d'instrumentation
plus large — non couverte ici, périmètre volontairement limité à ce que 8b demandait
explicitement.

`PlayerMetricsBridge.clear()` est appelé à la sortie du lecteur plein écran (même
`DisposableEffect` que `controller?.release()`), pour que Diagnostic ne continue pas
d'afficher un écart au direct périmé une fois qu'aucune lecture n'est plus en cours. Le
mini-lecteur de l'accueil (`osdEnabled = false`) n'écrit ni ne nettoie jamais ce pont —
un seul plein écran actif à la fois en pratique, pas besoin de distinguer plusieurs
sources.

## Programme en cours : nouveau chargement à la demande (`EpgNowLookup`)
Aucune brique existante ne chargeait encore un guide EPG en mémoire pour le consulter
(le chargement manuel de Réglages, 6g-2, ne fait que valider puis jeter le résultat du
parsing). `EpgNowLookup` (nouveau, `player/EpgNowLookup.kt`) télécharge/lit la source EPG
effective de la playlist (fichier local manuel en priorité, sinon URL — mêmes règles que
`SettingsViewModel.refreshEpg`) et cherche, parmi les `EpgProgram` obtenus, celui qui
couvre l'instant présent pour le `tvgId` de la chaîne.

**Aucun cache** : un seul appel par prise d'antenne (pas par tick de la boucle heure/écart
au direct), donc le télécharger/reparser à chaque fois reste acceptable — cohérent avec
le choix déjà pris en 6g-2 de ne stocker aucun `EpgProgram` nulle part (voir sa doc :
aucun autre consommateur n'existait encore pour justifier une couche de cache).
Tolérant aux échecs comme le reste du projet : URL injoignable, fichier introuvable ou
contenu illisible renvoient simplement `null`, l'OSD affiche alors le reste (nom, logo,
heure, écart au direct) sans rien de plus.

`PlayerScreen` reçoit maintenant un paramètre `appRepository: AppRepository? = null`,
utilisé uniquement pour résoudre `channel.playlistId` → `Playlist` avant d'appeler
`EpgNowLookup`. Les deux points d'entrée plein écran (`DpFlixNavHost`/`DpFlixTvNavHost`)
le passent, l'ayant déjà sous la main pour résoudre `channelId` → `Channel`. Le
mini-lecteur (`HomeScreen`/`HomeScreenTv`) ne le passe pas (reste `null`) : cohérent avec
`osdEnabled = false`, l'OSD n'y est de toute façon jamais rendu.

## Volontairement hors périmètre à cette sous-étape
- Rafraîchissement du programme en cours pendant le visionnage (un programme qui se
  termine n'est jamais réévalué sans zapper) : `EpgNowLookup` n'est appelé qu'une fois par
  prise d'antenne. À revoir si ça s'avère gênant à l'usage.
- Instrumentation complète du lecteur (débit, bitrate, segments réussis/échoués,
  résolution du flux — reste de `DiagnosticState`) : sous-étape distincte, toujours non
  planifiée. `PlayerMetricsBridge` est conçu pour accueillir d'autres métriques le jour où
  cette instrumentation existera, sans reprendre son principe.
- "Programme suivant" (souvent affiché à côté du programme en cours sur un vrai boîtier
  IPTV) : non demandé par le brief 8b ("programme en cours" seulement), non ajouté par
  anticipation.

## Validation de fin de sous-étape 8b
- `./gradlew compileDebugKotlin` doit réussir.
- Plein écran (mobile et TV) : l'heure affichée correspond à l'heure de l'appareil et
  avance ; sur une chaîne dont la source XMLTV est jointe, "Écart au direct" affiche une
  valeur qui converge vers le retard cible (`PlayerSettings.liveDelaySeconds`, Réglages →
  Lecteur) après quelques secondes ; sur une chaîne dont `tvgId` correspond à un guide EPG
  valide couvrant l'instant présent, le programme en cours apparaît à côté.
- Réglages → Diagnostic, **pendant qu'une lecture plein écran est active** (garder l'app en
  multi-fenêtres ou vérifier juste après avoir quitté le lecteur) : "Écart au direct"
  affiche la même valeur que l'OSD plutôt que "Non disponible". Après avoir fermé le
  lecteur, la valeur revient à "Non disponible" au rafraîchissement suivant (1,5 s).
- Mini-lecteur de l'accueil : toujours cliquable/focusable pour agrandir (non affecté par
  cette sous-étape, aucun changement à `osdEnabled = false`).

Prochaine sous-étape (8c) : zapping — navigation séquentielle (D-pad haut/bas TV, swipe
vertical mobile) + saisie numérique directe avec overlay de frappe (décisions actées dans
le message de cadrage précédent).
