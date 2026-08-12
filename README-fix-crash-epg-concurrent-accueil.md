# Correctif — crash au passage en plein écran, régression du branchement EPG à l'accueil (25 juillet 2026)

## Symptôme rapporté
Après le branchement du "programme en cours" sur le mini-lecteur de l'accueil, l'app se
ferme net dès qu'on touche l'écran pour passer en plein écran (retour direct à l'accueil
du téléphone, dialogue système "Une erreur s'est produite avec DP-Flix").

## Diagnostic
Deux appelants indépendants consomment `EpgRepository.getOrLoad` sans se coordonner :
- le mini-lecteur de l'accueil (`HomeViewModel.loadPreviewProgramTitle`, nouveau,
  déclenché à l'ouverture de l'aperçu — 1er tap sur une chaîne) ;
- l'OSD du lecteur plein écran (`PlayerScreen`, déjà existant, déclenché à l'entrée en
  plein écran — 2e tap sur la même chaîne).

`getOrLoad` ne consultait le cache qu'AVANT de lancer le chargement. Si le 2e tap arrive
avant la fin du 1er chargement (cas courant avec un guide XMLTV de 11 000+ chaînes, qui
prend plusieurs secondes à télécharger/parser), les deux appels constatent chacun un cache
vide et lancent chacun leur propre téléchargement + parsing du même guide, **en même
temps**. Le pic mémoire que le fix du 25/07 (fenêtre de rétention EPG 3h passé/48h futur)
avait borné à un seul chargement se retrouve doublé — repassant au-dessus de ce qu'un
mobile bas/moyen de gamme peut allouer, avec le même symptôme que les crashs déjà corrigés
ce jour-là (fermeture nette, sans lien de cause à effet visible avec l'EPG du point de vue
de l'utilisateur, qui ne voit qu'un tap pour passer en plein écran).

Avant le branchement du programme en cours à l'accueil, seul le lecteur plein écran
appelait `getOrLoad` : un seul appelant à la fois, donc jamais de chargement concurrent.
Le nouvel appel côté accueil a rendu ce cas — déjà noté comme faiblesse mineure
("EpgRepository.cache non synchronisé, accès concurrent rare") — courant au lieu de rare.

## Correctif
Un `Mutex` par playlist (`EpgRepository.loadMutexes`, `getOrPut` à la demande) sérialise
désormais les chargements :
- `getOrLoad` vérifie le cache, puis acquiert le verrou de la playlist, **revérifie le
  cache une seconde fois** (un autre appelant a pu terminer entre-temps) avant de charger
  — un second appelant sur la même playlist attend simplement la fin du premier au lieu de
  le dupliquer, et récupère son résultat.
- `refresh` (bouton "Rafraîchir l'EPG" de Réglages) passe aussi par ce verrou — attend la
  fin d'un chargement en cours plutôt que de le doubler, mais recharge malgré tout à son
  tour ensuite (pas de second contrôle du cache ici, cohérent avec le sens de "rafraîchir").
- `invalidate`/`clearAll` nettoient aussi `loadMutexes`, comme ils le faisaient déjà pour
  `cache`.

## Fichier modifié
- `app/src/main/kotlin/com/dpflix/android/repository/EpgRepository.kt`

## Non modifié
`HomeViewModel`, `PlayerScreen`, `SettingsViewModel` (les trois appelants de
`getOrLoad`/`refresh`) : aucun n'a besoin de changer, la sérialisation est entièrement
interne à `EpgRepository`.

## Limite assumée
Pas de compilation Gradle réelle possible dans cet environnement (pas d'accès réseau) :
vérification faite par relecture ciblée + comptage d'accolades/parenthèses, pas par un
build. À tester en conditions réelles (tap sur une chaîne à l'accueil puis tap immédiat
pour passer en plein écran, avec le même Xtream 11 000+ chaînes) avant de considérer ce
correctif définitivement clos.
