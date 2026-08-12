# Fix (2026-07-23) — Robustesse "n'importe quel flux/panel/caractère spécial"

Trois failles de robustesse identifiées et corrigées, indépendantes du système de repli
de conteneur déjà en place (`PlayerController.containerFallbackQueue`) :

## 1. Encodage de texte forcé en UTF-8 (M3U et Xtream)

`response.body?.string()` (OkHttp) et `InputStream.bufferedReader()` décodent toujours
en UTF-8 par défaut quand aucun charset n'est déclaré — très courant chez les panels
IPTV. Un contenu réellement en Windows-1252/ISO-8859-1 (noms de chaîne accentués,
générés par des outils Windows) ressortait donc corrompu ("Ã©" au lieu de "é") sans
aucune erreur visible.

**Fix** : nouveau `RobustTextDecoder` (lecture des octets bruts + BOM UTF-8/UTF-16 en
priorité, sinon décodage UTF-8 strict, repli Windows-1252 si ce n'est pas de l'UTF-8
valide). Appliqué à :
- `OnboardingViewModel.downloadM3u` (téléchargement d'URL M3U)
- `OnboardingViewModel.copyLocalFileToPrivateStorage` (fichier M3U local importé)
- `XtreamClient.executeGet` (réponses `player_api.php`)

## 2. URLs de flux M3U non encodées

`M3uParser` prenait la ligne d'URL telle quelle. Un export contenant des espaces ou des
caractères Unicode non encodés dans le chemin faisait échouer OkHttp/ExoPlayer avant
même la requête réseau.

**Fix** : `M3uParser.sanitizeStreamUrl` — encode uniquement si nécessaire (URL déjà
propre inchangée), ne touche jamais aux séquences déjà percent-encodées ni aux
caractères structurels d'URL.

## 3. Adresse de serveur Xtream mal formée

`XtreamClient.baseUrl` ne faisait que `trim()` + retirer le `/` final. Une adresse
saisie sans schéma ("monpanel.com:8080") faisait lever `IllegalArgumentException`
("expected scheme") avant la moindre requête ; un lien complet collé avec
`/player_api.php` ou une query string produisait une URL d'API invalide (chemin/query
dupliqués).

**Fix** : `baseUrl` ajoute `http://` par défaut si aucun schéma n'est présent, et retire
un éventuel `/player_api.php`, `/get.php` ou query string collé par erreur avec l'hôte.

## Notes

- `username`/`password` Xtream contenant des caractères spéciaux étaient déjà bien
  gérés (`encodePathSegment` pour le chemin, `URLEncoder` pour la query string, fix du
  22/07) — non retouché.
- Ces trois correctifs sont indépendants du système de repli de conteneur
  (`ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED`) déjà en place, qui reste la ligne de
  défense pour les cas où le serveur sert un conteneur différent de ce que son
  extension/mimeType annonce.
- Reste hors de portée d'un correctif logiciel : un flux dont le serveur renvoie
  effectivement une page d'erreur HTML (mauvais identifiants, IP bloquée, compte
  expiré) plutôt qu'une vidéo — aucun parseur ne peut rendre lisible ce qui n'est pas
  un flux vidéo. Voir le README précédent pour la méthode de diagnostic (tester l'URL
  brute dans VLC/navigateur).
