# Étape R5a (3/4) — Zapping séquentiel bloqué en REPLAY

Troisième des quatre parties de R5a (état/logique). S'appuie sur `PlaybackMode` posé en
R5a-1 : dernier des trois mécanismes qui n'ont de sens qu'en direct (avec l'accumulation de
tampon avant démarrage et l'écart au direct, R5a-2) à être neutralisé en `REPLAY`.

## Fait

- **`player/PlayerScreen.kt`**
  - `zap(direction)` (zapping séquentiel D-pad haut/bas TV, glissement vertical mobile) :
    ne fait plus rien si `controller?.playbackMode?.value == PlaybackMode.REPLAY` — la
    garde intervient AVANT toute résolution (`PlayerZapping.neighbor` n'est même pas
    appelée), au même niveau que la garde déjà existante sur `appRepository` absent
    (mini-lecteur).
  - `validateTypedNumber()` (saisie numérique directe, télécommande ou clavier virtuel
    mobile) : même garde, ajoutée APRÈS la remise à zéro de `typedNumber`/`keypadVisible`
    — la saisie tapée est donc vidée/l'overlay refermé exactement comme le cas déjà
    existant "numéro sans correspondance", seule la résolution
    `PlayerZapping.byDisplayNumber` est sautée. Pas de nouveau code d'erreur ni de retour
    visuel à gérer côté UI pour ce cas : il se comporte comme un numéro qui n'existe pas.

## Hors périmètre (volontairement)

- Le menu de chaînes pendant la lecture (touche Menu, `PlayerZapping.sameCategory`,
  `PlayerChannelMenuOverlay`) n'est pas concerné : la consigne de cette partie ne visait
  que le zapping séquentiel (précédent/suivant) et la saisie numérique directe.
- L'ouverture du clavier virtuel (`openKeypad`) et l'accumulation des chiffres tapés
  (`appendDigit`) restent inchangées — seule la résolution finale
  (`validateTypedNumber`) est bloquée. Une saisie encore possible mais qui n'aboutit à
  rien en REPLAY reste cohérente avec le cas "numéro sans correspondance" déjà en place ;
  empêcher l'ouverture même du clavier est un choix d'affichage qui relève de l'OSD
  replay (R5b), pas de cette partie état/logique.
- Aucun retour visuel ("zapping indisponible en replay") : R5b (OSD replay) est le bon
  endroit pour ça, cette partie se limite à empêcher l'action réelle.

## Test de sortie (partiel, cohérent avec le découpage)

- Lecture d'un replay (`playReplay`) : D-pad haut/bas (TV) ou glissement vertical
  (mobile) ne changent plus de chaîne — la chaîne et le programme en cours restent
  affichés à l'identique.
- Taper un numéro de chaîne puis valider (OK télécommande ou minuteur écoulé) pendant un
  replay referme simplement la saisie sans zapper, comme un numéro introuvable.
- Un direct (`playChannel`, `PlaybackMode.LIVE`) zappe toujours normalement, séquentiel
  et par numéro — comportement inchangé.

## Vérifications faites

- Équilibre accolades/parenthèses vérifié sur `PlayerScreen.kt` (seul fichier touché).
- Aucun import supplémentaire nécessaire : `PlaybackMode` est déjà dans le même package
  (`com.dpflix.android.player`, défini dans `PlayerController.kt`).
- Relecture de `zap`/`validateTypedNumber` : les gardes existantes (`appRepository`
  absent, `repository`/`number` nuls) restent inchangées, seule une garde
  supplémentaire s'ajoute à chacune.
