# Section "Films et Séries" — livraison partielle (07/08)

Ce zip contient le projet complet (reçu de toi le 07/08, `dpflix-purge-epg-partielle-6-aout-1-corrigee.zip`)
avec deux passes appliquées dessus :

## 1. Purge EPG définitive (déjà livrée précédemment, réappliquée ici)
Le zip complet que tu m'as envoyé était un instantané antérieur à la purge EPG finale.
`HomeViewModel.kt`, `SettingsScreen.kt`, `SettingsScreenTv.kt`, `SettingsViewModel.kt` ont
été remplacés par leurs versions déjà nettoyées (plus aucun code EPG actif). Voir
`README-purge-epg-finale.md` (déjà présent dans le projet) pour le détail de cette passe.

## 2. Section "Films et Séries" — EN COURS, PAS TERMINÉE

### Fait
- **`settings/GeneralSettings.kt`** : nouveau champ `filmsSeriesUrl: String?` +
  `DEFAULT_FILMS_SERIES_URL = "https://purstream.store/"`.
- **`settings/SettingsKeys.kt`** / **`settings/SettingsMapper.kt`** : clé DataStore
  `FILMS_SERIES_URL`, lecture/écriture avec repli sur la valeur par défaut.
- **`settings/SettingsViewModel.kt`** : `setFilmsSeriesUrl(value: String?)` — une chaîne
  vide ou blanche est traitée comme `null` (repli sur la valeur par défaut codée en dur).
- **`settings/SettingsScreen.kt`** (mobile) et **`settings/SettingsScreenTv.kt`** (TV) :
  nouveau champ "Lien Films et Séries" dans la section Général (`OutlinedTextField` +
  bouton "Enregistrer", même pattern que les champs de playlist existants — pas
  d'écriture DataStore à chaque frappe). Accolades vérifiées équilibrées sur les deux
  fichiers.
- **Nouveau fichier `filmsseries/FilmsSeriesScreen.kt`** : l'écran WebView verrouillé
  lui-même.
  - Domaine autorisé = celui de l'URL configurée + ses sous-domaines (`*.host`) ; toute
    navigation vers un autre domaine (pub, lien tiers) est interceptée et bloquée dans
    `shouldOverrideUrlLoading`, la page reste sur son état courant.
  - Popups/nouvelles fenêtres bloquées par défaut (`setSupportMultipleWindows(false)`,
    pas de `WebChromeClient.onCreateWindow` custom).
  - Menu contextuel long-press désactivé (pas de "copier le lien"/"ouvrir dans un nouvel
    onglet").
  - Double-appui sur retour (`BackHandler`, fenêtre de 2 secondes, `Toast`
    d'avertissement au premier appui) → `onNavigateHome()`. Ne navigue jamais dans
    l'historique du site lui-même (`WebView.canGoBack()` volontairement ignoré).
  - Accolades/parenthèses vérifiées équilibrées.

### Pas fait — reste à faire au prochain tour
- **`filmsseries/FilmsSeriesScreenTv.kt`** : petit wrapper TV mentionné dans la doc de
  `FilmsSeriesScreen.kt` mais pas encore créé. `FilmsSeriesScreen` étant déjà partagé
  mobile/TV (simple WebView plein écran, pas de disposition D-pad spécifique), ce
  wrapper peut probablement n'être qu'un appel direct à `FilmsSeriesScreen` — à confirmer
  au moment de le coder plutôt que de dupliquer inutilement un fichier.
- **`nav/DpFlixDestination.kt`** : ajouter la destination `FilmsSeries`.
- **`nav/DpFlixNavHost.kt`** et **`nav/DpFlixTvNavHost.kt`** : brancher la nouvelle route
  vers `FilmsSeriesScreen`/`FilmsSeriesScreenTv`.
- **`home/HomeScreen.kt`** et **`home/HomeScreenTv.kt`** : bouton d'accès à la section
  depuis l'accueil (mobile : à côté de l'icône Réglages ; TV : à côté du bouton
  "Réglages", cohérent avec l'ancien emplacement du bouton "Guide TV" retiré le 25
  juillet).
- Compilation finale de contrôle (toujours impossible dans cet environnement — pas
  d'accès réseau pour Gradle ; vérification faite par relecture ciblée + équilibre
  accolades/parenthèses uniquement).

### Point resté sans réponse
Aucune whitelist de sous-domaines précise n'a été fournie pour purstream.store — le
verrouillage implémenté autorise par défaut tout `*.purstream.store` en plus du domaine
exact. À confirmer/ajuster si le site utilise des sous-domaines qui ne devraient PAS être
autorisés (ou, à l'inverse, un CDN/lecteur sur un domaine complètement différent qu'il
faudrait ajouter à la liste blanche).
