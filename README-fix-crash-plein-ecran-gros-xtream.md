# Correctif — crash au passage en plein écran sur un Xtream 11 000+ chaînes (2026-07-25)

## Symptôme rapporté
Avec un Xtream Code exposant 11 000+ chaînes, l'app se ferme net (retour direct à
l'accueil du téléphone, aucun dialogue de plantage) au moment de toucher l'écran pour
passer en plein écran. Le chargement de la playlist elle-même se passe bien ; le souci
n'apparaît qu'à l'ouverture du lecteur. Avec une petite playlist M3U (~600 chaînes),
aucun problème.

## Diagnostic
L'entrée en plein écran (`PlayerScreen`) déclenche un `LaunchedEffect(currentChannel.id)`
qui appelle `EpgRepository.getOrLoad(playlist)` pour résoudre le "programme en cours"
affiché dans l'OSD (§4.6/8b). Avant ce correctif, `EpgXmlParser.parse` gardait **tout**
le guide XMLTV téléchargé — tous les `<programme>`, pour toutes les chaînes, sur toute la
période couverte par le guide (généralement plusieurs jours) — dans une seule `List`
gardée en cache mémoire sans TTL (`EpgRepository`, pas de minuteur d'expiration).

Un panel à 11 000+ chaînes expose typiquement un XMLTV de plusieurs centaines de
milliers, voire millions de `<programme>` : autant d'objets `EpgProgram` retenus
d'un coup en mémoire, largement plus que ce qu'un mobile bas/moyen de gamme peut
allouer. Un correctif du 25/07 avait déjà ajouté un `catch (OutOfMemoryError)` autour de
ce chargement — utile, mais insuffisant seul : il ne rattrape que l'OOM du tas propre à
l'app (Dalvik/ART), pas le cas où c'est la mémoire **totale** de l'appareil qui sature et
où le système tue directement le process (low memory killer) — ce qui se manifeste
exactement comme décrit : disparition instantanée, sans dialogue, retour à l'accueil.

L'écran de grille EPG qui aurait pu justifier de garder plusieurs jours de guide a par
ailleurs été retiré le même jour (`README-retrait-ecran-guide-tv.md`) : seuls restent
l'OSD "programme en cours" du lecteur et le statut de Réglages, qui n'ont besoin que
d'une fenêtre étroite autour de l'instant présent.

## Correctif
Ajout d'une fenêtre de rétention temporelle au parsing EPG : tout `<programme>`
entièrement hors de `[maintenant - 3h, maintenant + 48h]` est désormais ignoré **avant**
même de lire son `<title>`/`<desc>` (pas seulement avant de l'ajouter à la liste finale) —
c'est ce qui borne réellement la mémoire, en évitant l'allocation des chaînes de texte
pour les entrées qu'on sait déjà ne jamais utiliser.

- `EpgXmlParser.parse(...)` accepte deux nouveaux paramètres `keepFromMillis`/
  `keepUntilMillis` (valeurs par défaut larges, pour les appelants existants qui n'en
  passeraient pas explicitement).
- `parseProgrammeElement` vérifie la fenêtre dès que `start`/`stop` sont lus (avant de
  descendre dans l'élément) ; hors fenêtre → `skipElementBody` avance le curseur sans
  rien lire.
- `EpgRepository.load` passe désormais une fenêtre resserrée (passé : 3h, futur : 48h —
  généreuse pour couvrir une session laissée ouverte plusieurs heures sans que le
  "programme en cours" cesse de se mettre à jour, vu l'absence de TTL sur le cache).

Le `catch (OutOfMemoryError)` déjà en place reste inchangé, en filet de sécurité pour un
cas encore plus extrême (guide anormalement massif même dans cette fenêtre, autre pic
mémoire concurrent) — mais ce correctif traite la cause plutôt que de se reposer dessus.

## Fichiers modifiés
- `app/src/main/kotlin/com/dpflix/android/parser/EpgXmlParser.kt`
- `app/src/main/kotlin/com/dpflix/android/repository/EpgRepository.kt`

## Non modifié
- `EpgProgram`, `EpgLoadResult`, tous les appelants (`PlayerScreen`, `SettingsViewModel`) :
  aucun ne dépendait de recevoir plus que la fenêtre resserrée (vérifié : seul
  `programsByChannel[tvgId]?.firstOrNull { it.isCurrentlyAiring(...) }` est utilisé côté
  lecteur ; Réglages ne regarde que succès/échec, jamais le contenu).

## Limite assumée
Pas de compilation Gradle réelle possible dans cet environnement (pas d'accès réseau pour
télécharger les dépendances) : vérification faite par relecture ciblée + comptage
accolades/parenthèses sur les deux fichiers modifiés, pas par un build. À compiler côté
utilisateur avant de considérer ce correctif définitivement clos — en particulier à
tester en conditions réelles avec le même Xtream Code 11 000+ chaînes qui déclenchait le
crash.
