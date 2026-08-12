# Fix — Watchdog du lecteur : boucle de rechargement non bornée

## Symptôme

Diagnostic demandé : « aucune latence possible, aucune erreur de chargement
côté lecteur/EPG ». En creusant le watchdog de blocage (`PlayerController`,
§6 étape 5d), un cas limite subsistait : **un flux durablement injoignable**
(panel down, chaîne définitivement morte, coupure réseau prolongée...)
pouvait faire tourner le lecteur en boucle indéfiniment sur `Buffering`,
sans jamais atteindre un état `Error` visible par l'utilisateur.

## Cause

`performHardReload()` (dernier palier du watchdog, après la relance douce
`performSoftRetry()`) rappelle `playChannel()` pour reconstruire entièrement
la lecture. Mais `playChannel()` reschedule systématiquement un nouveau
`watchdogJob` (`scheduleWatchdog()`). Sur un flux mort, la séquence :

```
Buffering (15s) -> performSoftRetry() -> Buffering (20s de plus)
  -> performHardReload() -> playChannel() -> nouveau watchdog -> Buffering (15s) -> ...
```

se reproduit à l'infini. Aucune `PlaybackException` n'est jamais levée dans
ce chemin (contrairement aux erreurs réseau/parsing classiques déjà gérées
par `ResilientLoadErrorHandlingPolicy` et le fallback conteneur), donc rien
ne fait jamais basculer `_uiState` vers `PlayerUiState.Error`. Le lecteur
reste bloqué sur l'indicateur de chargement — la « latence infinie »
identifiée au diagnostic.

## Correctif

Compteur borné `hardReloadAttempts`, sur le même principe que
`behindLiveWindowRecoveries` (fix du 2026-07-23) :

- Incrémenté à chaque `performHardReload()`.
- Au-delà de `HARD_RELOAD_MAX_ATTEMPTS` (5, soit ~2 min 55 de tentatives
  automatiques cumulées avec `SOFT_RETRY_AFTER_STALL_MS` +
  `HARD_RELOAD_AFTER_SOFT_RETRY_MS`), le watchdog n'appelle plus
  `playChannel()` : il affiche directement `PlayerUiState.Error`, comme
  n'importe quelle erreur fatale classique.
- Remis à zéro sur une vraie reprise de lecture (`STATE_READY` dans
  `updateStateFromPlayer`) — **pas** dans `playChannel()`, puisque c'est
  justement l'appel rebouclé par le hard reload lui-même ; le remettre à
  zéro à cet endroit désamorcerait la borne en permanence.
- Également remis à zéro par une intervention manuelle explicite
  (`retry()`, bouton « Réessayer ») : un signal volontaire de l'utilisateur
  redonne au watchdog automatique un budget complet de tentatives, plutôt
  que de repartir avec un compteur déjà épuisé qui referait basculer sur
  `Error` dès le premier blocage suivant sans laisser le watchdog jouer son
  rôle.

## Fichiers modifiés

- `app/src/main/kotlin/com/dpflix/android/player/PlayerController.kt`
  - `hardReloadAttempts` (nouvelle propriété d'état, aux côtés de
    `behindLiveWindowRecoveries`)
  - `HARD_RELOAD_MAX_ATTEMPTS` (nouvelle constante, companion object)
  - `performHardReload()` — bascule sur `Error` au-delà de la borne
  - `updateStateFromPlayer()` — remise à zéro sur `STATE_READY`
  - `retry()` — remise à zéro sur intervention manuelle

## Portée du diagnostic

Le reste de la chaîne de chargement (chaîne + EPG) était déjà borné :
compteurs plafonnés (`BEHIND_LIVE_WINDOW_MAX_RECOVERIES`), file de repli
conteneur finie et dédupliquée (`containerFallbackQueue`,
`LinkedHashSet`), politique de retry réseau non infinie
(`ResilientLoadErrorHandlingPolicy`). Ce correctif couvre le seul point
resté non borné identifié à l'issue du diagnostic.
