# Fix — Latence liste des chaînes (accueil) sur grosses playlists

## Constat

`README-retrait-ecran-guide-tv.md` affirmait que le correctif
`flowOn(Dispatchers.Default)` sur `ChannelRepository.observeGroupedByCategory`
était "indépendant du retrait [de l'écran Guide TV] et reste en place tel
quel". **Ce correctif n'avait en réalité jamais été écrit** dans le code —
vérifié par relecture directe de `ChannelRepository.kt` : `observeGroupedByCategory`
ne portait aucun `flowOn`, contrairement à ce que le README affirmait.

## Cause

`observeGroupedByCategory` applique un `groupBy` + `map` sur la liste complète
des chaînes de la playlist active à chaque émission du `Flow`. Sur une playlist
de 20000+ chaînes (voir `README-diagnostic-complet-23-juillet.md`), ce
regroupement est un travail CPU non négligeable. Sans `flowOn`, cet opérateur
s'exécute sur le dispatcher du collecteur — `Main.immediate` côté UI (accueil)
— ce qui peut geler brièvement l'interface à chaque mise à jour de la liste
(ajout/suppression de chaînes, changement de playlist active, etc.).

## Correctif

`flowOn(Dispatchers.Default)` ajouté à la fin de la chaîne de `observeGroupedByCategory`.
`Dispatchers.Default` (pas `IO`) car c'est du calcul pur (groupBy/map en mémoire),
pas une attente réseau/disque. `observeByPlaylist` et le reste de `ChannelRepository`
restent inchangés.

## Fichier modifié

- `app/src/main/kotlin/com/dpflix/android/repository/ChannelRepository.kt`
