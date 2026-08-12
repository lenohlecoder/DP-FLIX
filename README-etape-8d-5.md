# DP-Flix — Étape 8d-5 : Volume — suivi des changements externes (§4.5/§8d)

Cette livraison contient **l'intégralité du projet** (étapes 2a→8d-4 inchangées + les
ajouts de cette sous-étape 8d-5). Rien n'a été retiré.

## Périmètre recadré par la décision de 8d-4
Le découpage initial de 8d-5 prévoyait "persistance de l'état (position du curseur au
prochain plein écran), valeur initiale au démarrage". Ces deux points sont en réalité
déjà couverts depuis 8d-4 (conséquence directe du choix `AudioManager` : le système
persiste et fournit lui-même la valeur initiale, voir son README). Cette sous-étape
traite donc le seul point réellement restant : la synchronisation **inverse**.

## Ce qui a été fait

### `PlayerScreen` : `ContentObserver` sur `Settings.System` (nouveau)
Sans ce mécanisme, appuyer sur les boutons physiques de volume pendant que le plein écran
est ouvert changerait le vrai volume sans jamais rafraîchir le curseur affiché dans
l'OSD — les deux sens (curseur → système, système → curseur) sont des mécanismes
distincts, tous les deux nécessaires.

Observation choisie : `ContentObserver` sur `Settings.System.CONTENT_URI`, plutôt que le
broadcast `"android.media.VOLUME_CHANGED_ACTION"` (fonctionne en pratique sur toutes les
versions d'Android, mais constante `@hide` d'`AudioManager` — pas une API publique
documentée). Le `ContentObserver` ne s'appuie que sur des API publiques (`Settings`,
`ContentObserver`). Contrepartie assumée : il se déclenche pour n'importe quel
changement dans `Settings.System`, pas seulement le volume — d'où une relecture directe
de la valeur réelle (`audioManager.getStreamVolume`) à chaque notification plutôt qu'une
hypothèse sur sa cause. Coût négligeable (un appel de plus), fiabilité gagnée.

Enregistré/désenregistré via un `DisposableEffect(channel.id)` dédié (même clé que celui
existant pour la libération du `PlayerController`, mais bloc séparé pour rester lisible),
uniquement quand `osdEnabled` — le mini-lecteur ne rend jamais le curseur, rien à
synchroniser dans ce contexte.

## Volontairement hors périmètre
- D-pad TV sur le curseur : toujours pas de focus posé dessus (8d-10).
- Qualité manuelle : sous-étapes suivantes (8d-6 à 8d-8).

## Validation de fin de sous-étape 8d-5
- `./gradlew compileDebugKotlin` doit réussir.
- Plein écran ouvert, OSD visible : appuyer sur les boutons physiques de volume de
  l'appareil → le curseur OSD se met à jour en direct, sans qu'il soit nécessaire de le
  toucher.
- Le sens inverse (glisser le curseur) reste inchangé depuis 8d-4.
- Mini-lecteur : comportement inchangé, aucun observer enregistré dans ce contexte
  (`osdEnabled = false`).
- Quitter le plein écran (retour à l'accueil) : pas de fuite — l'observer est bien
  désenregistré (`onDispose`), vérifiable en surveillant qu'aucune notification ne
  parvient plus après la sortie de l'écran.

Prochaine sous-étape (8d-6) : qualité manuelle — décision de principe (à confirmer,
non explicite au §4.5/§5.1) et récupération de la liste des résolutions disponibles pour
la chaîne courante.
