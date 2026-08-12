# Correctif : rejet systématique de TOUS les flux (2026-07-22, troisième puis quatrième passage)

## Constat
Après les deux premiers correctifs (User-Agent en cascade + TLS permissif, puis
fallback automatique m3u8/ts), la connexion Xtream fonctionnait bien — authentification,
catégories et liste des chaînes se chargeaient correctement — mais **la lecture
échouait systématiquement sur 100 % des chaînes**, avec `ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`,
y compris sur un panel Xtream réel (payant) et sur un lien M3U direct. Le fallback
d'extension à lui seul ne suffisait donc pas : le problème n'était pas (uniquement)
une extension mal annoncée par le panel.

## Cause réelle
`PlayerController.startPlayback()` forçait explicitement le type MIME du `MediaItem`
d'après l'extension de l'URL (`.m3u8` → HLS, `.ts` → TS brut), via `setMimeType()`.
Deux problèmes cumulés :

1. **Forcer le MIME empêchait tout sniffing tolérant.** Un panel qui annonce mal son
   format (`container_extension` trompeur) se retrouvait avec l'extracteur imposé de
   force, sans la moindre chance qu'ExoPlayer applique sa propre détection.
2. **L'extracteur TS de Media3 est strict par défaut sur l'image-clé de démarrage.**
   Beaucoup de panels Xtream (surtout gratuits/revendeurs) servent un flux MPEG-TS
   *live continu* : le lecteur "rejoint" un flux déjà en cours, sans redécoupage propre
   sur une image-clé (IDR) comme le ferait un vrai encodeur HLS. Par défaut,
   l'extracteur TS attend une IDR dans sa fenêtre d'inspection initiale et rejette le
   flux comme conteneur non supporté s'il n'en trouve pas — même quand le flux est un
   TS par ailleurs parfaitement valide. C'est cohérent avec un rejet **systématique**,
   sur toutes les chaînes et tous les panels testés, alors que la couche API
   (`get_live_streams`) fonctionnait déjà.

## Ce que fait ce correctif
Dans `PlayerController.kt` :

1. **Suppression du `setMimeType()` forcé** sur le `MediaItem` (fonction `mimeTypeForUri`
   retirée, plus utilisée nulle part). `DefaultMediaSourceFactory` reprend la main pour
   déterminer lui-même le type de flux, avec son propre sniffing tolérant plutôt qu'un
   choix imposé d'avance.
2. **Extracteur TS permissif** : un `DefaultExtractorsFactory` explicite, avec
   `setTsExtractorFlags(TsExtractor.FLAG_ALLOW_NON_IDR_KEYFRAMES)`, branché sur
   `DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)`. Ce drapeau lève
   l'exigence stricte d'image-clé de démarrage — le cas le plus probable pour expliquer
   un rejet uniforme sur tous les flux live testés.

Le fallback automatique m3u8/ts du correctif précédent (`alternateContainerUri`,
`containerFallbackAttempted`) reste en place tel quel : il continue à couvrir le cas
d'une extension réellement trompeuse, en complément de cette tolérance TS.

## Ce que ce correctif ne peut pas garantir
Sans accès réseau pour tester contre un vrai panel, je ne peux pas confirmer à 100 %
que `FLAG_ALLOW_NON_IDR_KEYFRAMES` seul suffit sur tous les flux — c'est l'explication
la plus cohérente avec les symptômes observés (rejet uniforme malgré une connexion API
qui fonctionne), mais si l'erreur persiste après ce correctif sur certaines chaînes
seulement, ce sera alors plus probablement des flux réellement morts côté panel plutôt
qu'un problème client — auquel cas regarder le détail de l'erreur dans Diagnostic
(§5.5, écran Réglages) pour voir si c'est bien encore `PARSING_CONTAINER_UNSUPPORTED`
ou un autre code.

## Ajout (quatrième passage) : cascade de variantes d'URL, pas une seule bascule

Le fallback `alternateContainerUri` (deuxième passage) ne tentait qu'**une seule**
bascule d'extension (`.m3u8` → `.ts` ou l'inverse). Remplacé par
`containerFallbackCandidates`, qui construit une vraie file de tentatives :

- URL se terminant par `.m3u8` → essaie `.ts`, puis l'URL **sans aucune extension**
- URL se terminant par `.ts` → essaie `.m3u8`, puis l'URL **sans aucune extension**
- URL sans extension reconnue → essaie `.m3u8`, puis `.ts`

Certains panels Xtream servent le flux directement à `/live/user/pass/id`, sans
qu'une extension n'ait jamais été nécessaire ni même acceptée — cas qu'une seule
bascule m3u8↔ts ne couvrait pas. `containerFallbackQueue` (remplace l'ancien booléen
`containerFallbackAttempted`) mémorise la file restante pour la chaîne en cours et
la consomme une variante à la fois à chaque nouvel échec `PARSING_CONTAINER_UNSUPPORTED`,
jusqu'à épuisement — toujours réarmée à zéro à chaque nouveau zap
(voir `playChannel`), comme avant.


## Fichier modifié
- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt`

## Dépendance Gradle
Aucune à ajouter : `DefaultExtractorsFactory` et `TsExtractor` viennent de
`media3-exoplayer` (dépendance transitive de `media3-extractor`), déjà sur le
classpath du projet.

## Intégration (Termux)
```bash
cd ~/dpflix-android
# extraire l'archive ici, en écrasant le fichier existant
git add -A
git commit -m "fix: tolérance TS (FLAG_ALLOW_NON_IDR_KEYFRAMES) + retrait du forçage MIME, cause du rejet systématique de tous les flux"
git push
```
