# Diagnostic système global — session temporaire 10 minutes

## Ce qui est ajouté

- Réglages → Diagnostic ouvre maintenant deux catégories :
  - Diagnostic lecture : métriques et erreurs du lecteur existantes ;
  - Diagnostic système : analyse temporaire globale.
- Diagnostic système est **désactivé par défaut** et ne collecte rien lorsqu'il est arrêté.
- Activation manuelle : session de 10 minutes maximum.
- Compteur temps réel + actions observées + avertissements + erreurs.
- Arrêt manuel possible à tout moment.
- Arrêt automatique à 00:00.
- Génération automatique d'un rapport local après l'arrêt.
- Rapport consultable ultérieurement et supprimable depuis Réglages.

## Sources actuellement instrumentées

- Réponses HTTP du client de lecture : code HTTP, Content-Type, présence d'un User-Agent/cookie, sans enregistrer les valeurs sensibles.
- Erreurs du lecteur Media3/ExoPlayer.
- Navigation et erreurs principales de la WebView Films & Séries.
- Détection de flux vidéo par le sniffer.
- Mise en file et échecs des téléchargements.

## Analyse des causes

Le moteur classe les causes lorsqu'elles sont objectivement déductibles : HTTP 403/401/404/5xx, timeout, DNS, erreurs d'E/S, format/MIME incompatible, etc. Les autres cas sont marqués comme cause à confirmer plutôt que présentés comme certains.

## Confidentialité

Le rapport masque les cookies, tokens, mots de passe, clés API et paramètres de requête d'URL. Les cookies ne sont enregistrés que sous forme d'indicateur « disponibles/absents ».

## Validation

Le ZIP source ne contient pas `gradlew`, donc aucun build Gradle complet n'a été exécuté dans cet environnement. Une vérification syntaxique Kotlin des fichiers modifiés a été effectuée ; les erreurs restantes de cette vérification proviennent de l'absence du classpath Android/AndroidX, pas d'une erreur de parsing Kotlin.
