# Vidéo de démarrage remplacée + saut de l'intro

## Fait

- **`res/raw/splash.mp4`** : remplacée par la vidéo fournie ("voltige sur l'eau", 720p,
  H.264/AAC, 8 s) — compatible ExoPlayer directement, aucune conversion nécessaire.
- **`splash/SplashScreen.kt`** : ajout du saut manuel de l'intro, deux voies actives en
  même temps (une n'exclut pas l'autre) :
  - **TV** : touche OK/Entrée de la télécommande (`KEYCODE_DPAD_CENTER`/`KEYCODE_ENTER`)
    — la `PlayerView` prend le focus Android au démarrage (`requestFocus()`) pour recevoir
    la touche, même mécanique que le lecteur plein écran.
  - **Mobile** : un tap n'importe où sur la vidéo (`setOnClickListener`).
  - Les deux appellent la même fonction interne `finishOnce()`, qui garantit que
    `onSplashFinished` n'est jamais appelé deux fois — que ce soit un saut manuel suivi de
    la fin naturelle de la vidéo, ou un double tap/OK rapproché.

## Comment vérifier côté toi

1. Lance l'app (mobile ou TV) : la nouvelle vidéo doit démarrer en plein écran, son inclus.
2. TV : appuie sur OK avant la fin — doit passer immédiatement à l'écran suivant
   (onboarding ou accueil selon qu'une playlist est déjà active).
3. Mobile : tape n'importe où sur la vidéo — même comportement.
4. Laisse la vidéo aller jusqu'au bout sans toucher à rien — comportement inchangé (passe
   automatiquement à la fin).

## Limite assumée

Pas de compilation Gradle réelle possible ici — vérification par relecture ciblée +
équilibre accolades/parenthèses, et lecture des métadonnées de la vidéo (ffprobe) pour
confirmer la compatibilité du codec. À compiler et tester côté toi.
