package com.dpflix.android.player

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.ui.PlayerView
import com.dpflix.android.settings.SettingsScreen
import com.dpflix.android.model.Channel
import com.dpflix.android.model.EpgLoadResult
import com.dpflix.android.repository.AppRepository
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Delai avant masquage automatique de l'OSD sans nouvelle interaction (8a). */
private const val OSD_AUTO_HIDE_MILLIS = 5_000L

/** Cadence de rafraichissement de l'heure/ecart au direct affiches par l'OSD (8b). */
private const val OSD_CLOCK_TICK_MILLIS = 1_000L

/** Delai sans nouvelle frappe avant validation automatique d'un numero en cours de saisie
 *  (8c, "court delai sans nouvelle frappe" acte dans le cadrage de cette sous-etape). */
private const val NUMERIC_ENTRY_AUTO_VALIDATE_MILLIS = 2_000L

/** Distance minimale (dp) d'un glissement vertical pour qu'il soit interprete comme un
 *  zapping plutot qu'un tap (8c, swipe mobile). Convertie en pixels dans la factory de
 *  l'AndroidView (densite de l'appareil), voir plus bas. */
private const val SWIPE_MIN_DISTANCE_DP = 32f

/**
 * Ecran plein cadre du lecteur (paragraphe 7 etape 5a/5b, focus D-pad affine en 7d, OSD depuis
 * 8a/8b/8c).
 *
 * ## OSD (paragraphe 4.5, etape 8a)
 * PlayerView.useController est desormais desactive (false) : jusqu'a l'etape 7g,
 * cet ecran s'appuyait sur la barre de controle integree de Media3 (PlayerControlView)
 * pour tout affichage/interaction. A partir de 8a, ce role est repris par PlayerOsd, un
 * calque Compose propre a ce projet - necessaire pour pouvoir y ajouter les infos direct
 * (8b), le zapping (8c) et des controles personnalises (8d), qu'une barre Media3 generique
 * ne permet pas. Consequence assumee pour 8a-8c : le controle integre de Media3
 * (lecture/pause, volume) disparait en meme temps que la barre qui le portait, et n'est
 * pas encore remplace - les controles personnalises arrivent a 8d, comme prevu par le
 * decoupage meme de l'etape 8. Entre-temps, l'app lit simplement le direct en continu,
 * ce qui reste l'usage normal d'un lecteur IPTV.
 *
 * osdVisible + osdShowToken pilotent l'apparition/disparition et le minuteur
 * d'auto-masquage : osdShowToken est incremente a chaque nouvelle interaction (tap/D-pad)
 * pour que le LaunchedEffect du minuteur redemarre son delai meme si l'OSD etait deja
 * visible (un simple LaunchedEffect(osdVisible) ne se redeclencherait pas dans ce cas,
 * true -> true n'etant pas un changement de cle).
 *
 * - Mobile (tap / swipe, 8a/8c) : PlayerView.setOnTouchListener + un GestureDetector
 *   distinguent tap simple (bascule show/hide) et glissement vertical (zapping) - un
 *   clickable/pointerInput Compose pose sur ce Box ne suffirait pas : PlayerView est
 *   une vraie View Android integree via AndroidView, elle intercepte le toucher avant
 *   qu'il n'atteigne un modifier Compose porte par un composable englobant.
 * - TV (D-pad, 8a/8c) : PlayerView.setOnKeyListener distingue desormais plusieurs cas
 *   (voir buildPlayerViewKeyListener) - DPAD_UP/DOWN zappent (suivant/precedent),
 *   DPAD_CENTER/ENTER valident une saisie numerique en cours (8c) ou, sinon, basculent
 *   play/pause (8d2 - priorite tranchee ainsi, la saisie l'emporte toujours), les touches
 *   numeriques alimentent la saisie, et DPAD_LEFT/RIGHT se contentent d'afficher
 *   l'OSD comme avant. Pas de geste "appuyer a nouveau pour cacher" au D-pad - seul le
 *   minuteur masque l'OSD, comme sur un vrai boitier IPTV. Consequence assumee de 8d2 :
 *   DPAD_CENTER/ENTER ne rappelle plus showOsd() par lui-meme (togglePlayPause n'a aucun
 *   effet sur la visibilite du bandeau) - comme sur une vraie telecommande IPTV, ou OK agit
 *   sur la lecture qu'on voie ou non le bandeau a l'instant T.
 *
 * Etant donne useController desactive, PlayerView ne consomme plus lui-meme les
 * touches D-pad pour ses propres controles (voir l'ancien commentaire, retire) : elle doit
 * toujours avoir le focus Android pour que setOnKeyListener recoive quoi que ce soit,
 * d'ou la meme logique requestFocus() qu'avant, desormais a SON seul benefice.
 *
 * Le bouton "Retour" de la telecommande continue de remonter tel quel jusqu'au NavHost
 * (DpFlixTvNavHost/DpFlixNavHost) : ni l'ancienne barre Media3 ni setOnKeyListener
 * ci-dessous n'interceptent KEYCODE_BACK.
 *
 * En cas d'erreur (PlayerUiState.Error), le focus est explicitement redirige vers le
 * texte "Reessayer" (autre FocusRequester, distinct de celui de PlayerView) : sans ce
 * transfert, le focus Android resterait sur PlayerView, dont setOnKeyListener ne fait
 * plus qu'afficher un OSD qui n'a alors rien d'utile a proposer non plus.
 *
 * ## osdEnabled : ce meme ecran sert aussi de mini-lecteur (paragraphe 4.4)
 * PlayerScreen est reutilise tel quel par MiniPlayer/MiniPlayerTv (accueil, 4.4,
 * etapes 6c/7c) pour l'apercu en tete d'ecran - un Box englobant y porte deja son propre
 * clickable(onClick = onExpand) (mobile) / focusable().clickable(onClick = onExpand)
 * (TV) pour agrandir vers le plein ecran. Si l'OSD (tap/D-pad, requestFocus()) restait
 * actif dans ce contexte, PlayerView intercepterait le tap/la touche OK AVANT qu'il
 * n'atteigne ce Box englobant (une vraie View Android consomme le toucher avant qu'il
 * ne remonte a un modifier Compose parent - voir plus haut), cassant "taper pour agrandir".
 * osdEnabled = false desactive alors entierement le tap listener, le key listener ET la
 * demande de focus Android, restaurant le comportement deja en place avant cette
 * sous-etape : un PlayerView purement passif, le Box englobant gerant seul le tap/D-pad.
 * Le zapping (8c) herite naturellement de cette meme garde : pas d'appRepository en
 * mini-lecteur (voir plus bas), donc zap et la resolution par numero n'y font jamais rien.
 *
 * ## appRepository : programme en cours (paragraphe 4.6, 8b) ET zapping (8c)
 * null par defaut (mini-lecteur) ; les deux points d'entree plein ecran
 * (DpFlixNavHost/DpFlixTvNavHost) le passent, l'ayant deja sous la main pour resoudre
 * channelId -> Channel avant meme d'atteindre cet ecran. Sert a retrouver la Playlist
 * de la chaine (source EPG effective, 4.6, voir com.dpflix.android.repository.EpgRepository,
 * etape 9a) et, depuis 8c, a resoudre
 * le voisin sequentiel ou la chaine correspondant a un numero tape (voir PlayerZapping).
 *
 * ## Zapping (paragraphe 4.5/5.3, etape 8c)
 * currentChannel est l'etat interne qui reflete la chaine reellement affichee - distinct
 * du parametre channel (l'entree de navigation, celle que le NavHost a resolue). Un zap
 * change currentChannel et redemande la lecture au PlayerController deja existant via
 * controller.playChannel(...) SANS le recreer : PlayerController.playChannel
 * est explicitement pense pour "remplacer juste le MediaItem en cours" d'une chaine a
 * l'autre (voir sa doc) - recreer un ExoPlayer a chaque zap serait plus lent et inutile.
 * Tous les etats qui dependent de la chaine affichee (infos direct, OSD, saisie numerique)
 * sont donc remember(channel.id) (identite de navigation stable pendant tout le zapping,
 * ne se reinitialise qu'en arrivant sur cet ecran depuis l'accueil) plutot que
 * remember(currentChannel.id) (qui les aurait reinitialises a CHAQUE zap, y compris ceux
 * qu'on veut justement piloter nous-memes, comme la remise a null explicite de
 * liveEdgeOffsetSeconds dans applyZap).
 *
 * Deux entrees, resolues par PlayerZapping dans le meme ordre que l'accueil (categorie
 * puis numero affiche, 4.4) :
 * - Sequentielle : DPAD haut/bas (TV) ou glissement vertical (mobile). Convention retenue
 *   (aucune n'etait imposee par le cadrage) : haut / glissement vers le haut -> chaine
 *   SUIVANTE (comme "CH+" sur une telecommande classique), bas / glissement vers le bas
 *   -> chaine PRECEDENTE.
 * - Numerique directe : touches numeriques telecommande (TV) ou clavier virtuel mobile
 *   (PlayerZapEntryOverlay, ouvert en tapant le numero affiche dans l'OSD - voir
 *   PlayerOsd.onRequestNumericEntry). typedNumber accumule les chiffres ;
 *   numericEntryToken pilote le meme mecanisme de minuteur redemarrable que
 *   osdShowToken (voir plus haut) pour la validation automatique apres
 *   NUMERIC_ENTRY_AUTO_VALIDATE_MILLIS sans nouvelle frappe. Validation aussi possible
 *   immediatement via OK (DPAD_CENTER/ENTER cote TV, "check" du clavier virtuel cote mobile).
 *   Numero sans correspondance -> validateTypedNumber vide simplement la saisie, pas
 *   d'erreur bloquante (decision actee dans le cadrage de 8c).
 */
@Composable
fun PlayerScreen(
    channel: Channel,
    modifier: Modifier = Modifier,
    osdEnabled: Boolean = true,
    appRepository: AppRepository? = null,
    onRequestFullReset: () -> Unit = {}
) {
    val context = LocalContext.current
    val view = LocalView.current
    val coroutineScope = rememberCoroutineScope()
    var controller by remember(channel.id) { mutableStateOf<PlayerController?>(null) }
    var playerView by remember(channel.id) { mutableStateOf<PlayerView?>(null) }

    // Réglages en incrustation (§4.6) : PAS remember(channel.id), volontairement —
    // contrairement aux DisposableEffect(channel.id) du contrôleur/de la vue ci-dessus,
    // qui doivent bien se recréer à chaque zap. L'incrustation, elle, doit justement
    // NE JAMAIS faire quitter la composition de cet écran (c'est tout son but : garder
    // controller/playerView ci-dessus vivants pendant que Réglages est affiché) — un zap
    // pendant que Réglages est ouvert (cas limite improbable, aucun bouton de zap n'est
    // visible derrière l'incrustation) ne doit donc pas la refermer.
    var settingsOverlayVisible by remember { mutableStateOf(false) }
    BackHandler(enabled = settingsOverlayVisible) { settingsOverlayVisible = false }

    // Chaine reellement affichee (8c) : distincte de [channel], voir la doc de la fonction.
    var currentChannel by remember(channel.id) { mutableStateOf(channel) }

    // OSD (8a) : visible par defaut a la prise d'antenne d'une chaine (comme un vrai
    // boitier IPTV affiche le nom de la chaine au zapping), puis masque par le minuteur.
    var osdVisible by remember(channel.id) { mutableStateOf(true) }
    var osdShowToken by remember(channel.id) { mutableStateOf(0) }

    // Infos direct (4.5/8b) : recalculees par la boucle ci-dessous, pas dans PlayerOsd
    // (qui reste un pur composable de rendu - voir sa doc).
    var nowMillis by remember(channel.id) { mutableStateOf(System.currentTimeMillis()) }
    var liveEdgeOffsetSeconds by remember(channel.id) { mutableStateOf<Float?>(null) }
    var currentProgramTitle by remember(channel.id) { mutableStateOf<String?>(null) }

    // Saisie numerique directe (5.3/8c) : voir la doc de la fonction pour le detail du
    // mecanisme de validation automatique et du clavier virtuel mobile.
    var typedNumber by remember(channel.id) { mutableStateOf("") }
    var numericEntryToken by remember(channel.id) { mutableStateOf(0) }
    var keypadVisible by remember(channel.id) { mutableStateOf(false) }

    // Volume (8d4) : decision tranchee - AudioManager (volume systeme, STREAM_MUSIC)
    // plutot qu'ExoPlayer.volume. Sur la quasi-totalite des box IPTV/apps de streaming,
    // le curseur affiche est le volume systeme (celui des boutons physiques de
    // l'appareil) - c'est ce qu'un utilisateur attend en priorite, contrairement a un
    // volume interne au lecteur qui creerait deux reglages distincts et deroutants
    // (curseur OSD vs boutons physiques). ExoPlayer.volume resterait pertinent pour du
    // mixage multi-lecteurs (pas le cas ici, un seul flux joue a la fois) ou un mute
    // ponctuel scope a un composant precis - hors besoin de 8d.
    //
    // Consequence directe et positive de ce choix : la persistance "gratuite" du volume
    // entre deux plein ecrans est deja assuree par le systeme lui-meme,
    // AudioManager.getStreamVolume reste vrai tant que l'utilisateur n'a pas touche le
    // volume ailleurs - pas de DataStore a ecrire ici. 8d5 (plus bas dans cette fonction,
    // ContentObserver) gere la cohesion inverse : suivre les changements de volume
    // externes (boutons physiques presses pendant que l'OSD est visible).
    //
    // Pas remember(channel.id) : le volume systeme n'a aucun rapport avec la chaine
    // affichee (contrairement a currentChannel/nowMillis/... ci-dessus) - un zap ne doit
    // surtout pas reinitialiser le curseur.
    val audioManager = remember {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    val maxStreamVolume = remember {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
    }
    var volumeFraction by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxStreamVolume.toFloat())
    }

    fun setSystemVolume(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        volumeFraction = clamped
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (clamped * maxStreamVolume).roundToInt(), 0)
    }

    fun showOsd() {
        osdVisible = true
        osdShowToken++
    }

    fun toggleOsd() {
        if (osdVisible) osdVisible = false else showOsd()
    }

    /**
     * Applique un changement de chaine deja resolu (voisin sequentiel ou numero trouve) :
     * redemande la lecture au meme PlayerController (voir la doc de la fonction sur
     * pourquoi il n'est pas recree), remet a null l'ecart au direct (etat transitoire deja
     * gere par PlayerOsd comme "indisponible", exactement prevu pour ce cas - voir sa doc)
     * et reaffiche l'OSD (comme un vrai boitier IPTV, qui montre le nom de la nouvelle
     * chaine a chaque zap).
     */
    fun applyZap(target: Channel) {
        currentChannel = target
        liveEdgeOffsetSeconds = null
        // Garde-fou (25 juillet 2026, vague 1 "stop crash", diagnostic point 3/8a) : reset
        // synchrone au même endroit que liveEdgeOffsetSeconds ci-dessus, PAS seulement dans
        // le LaunchedEffect(currentChannel.id) plus bas (aujourd'hui commenté). currentProgramTitle
        // vaut déjà toujours null tant que ce bloc reste désactivé (aucun effet visible ici
        // pour l'instant), mais si quelqu'un le réactive un jour sans y penser, ce reset
        // synchrone évite le flash du titre de l'ancienne chaîne juste après un zap, le temps
        // que la coroutine du LaunchedEffect démarre après la recomposition.
        currentProgramTitle = null
        controller?.playChannel(target)
        showOsd()
    }

    /** Zapping sequentiel (8c) : sans effet si appRepository est absent (mini-lecteur,
     *  voir la doc de la fonction) ou si aucun voisin n'a pu etre resolu. */
    fun zap(direction: ZapDirection) {
        val repository = appRepository ?: return
        val fromChannel = currentChannel
        coroutineScope.launch {
            val neighbor = PlayerZapping.neighbor(repository, fromChannel, direction)
            if (neighbor != null) applyZap(neighbor)
        }
    }

    /** Ajoute un chiffre a la saisie en cours (telecommande numerique ou clavier virtuel
     *  mobile) et relance le minuteur de validation automatique via numericEntryToken. */
    fun appendDigit(digit: Int) {
        typedNumber += digit.toString()
        numericEntryToken++
    }

    /** Ferme le clavier virtuel et abandonne la saisie en cours sans rien changer (bouton
     *  "croix" du clavier mobile - pas d'equivalent telecommande, la TV n'affiche jamais ce
     *  clavier, voir PlayerOsd.onRequestNumericEntry). */
    fun cancelNumericEntry() {
        typedNumber = ""
        keypadVisible = false
    }

    /** Valide la saisie en cours (minuteur ecoule ou "OK" explicite) : resout le numero dans
     *  la playlist de currentChannel via PlayerZapping.byDisplayNumber et zappe si trouve
     *  - sinon referme simplement l'overlay, pas d'erreur bloquante (decision de cadrage). */
    fun validateTypedNumber() {
        val repository = appRepository
        val number = typedNumber.toIntOrNull()
        val playlistId = currentChannel.playlistId
        typedNumber = ""
        keypadVisible = false
        if (repository == null || number == null) return
        coroutineScope.launch {
            val match = PlayerZapping.byDisplayNumber(repository, playlistId, number)
            if (match != null) applyZap(match)
        }
    }

    /** Ouvre le clavier virtuel mobile (tap sur le numero affiche dans l'OSD) - voir la doc
     *  de PlayerOsd.onRequestNumericEntry. */
    fun openKeypad() {
        keypadVisible = true
        showOsd()
    }

    LaunchedEffect(channel.id) {
        // Fix (2026-07-24) : récupère la playlist propriétaire de cette chaîne pour que
        // son éventuel forçage réseau (Referer/User-Agent/proxy, voir Playlist) soit
        // appliqué — `null` si absente (chaîne orpheline) ou si aucun appRepository
        // n'est fourni (mini-lecteur), même comportement automatique qu'avant dans ce cas.
        val playlist = appRepository?.playlists?.getById(channel.playlistId)
        val created = PlayerController.create(context, playlist = playlist)
        created.playChannel(channel)
        controller = created
    }

    // Fix (2026-08-04) : Réglages → Lecteur (tampon, cache RAM, tampon hybride, retard
    // cible) s'ouvre en incrustation PAR-DESSUS ce même écran (voir settingsOverlayVisible
    // plus haut) sans jamais recréer controller ([remember(channel.id)]) — jusqu'ici, un
    // changement de réglage pendant que la même chaîne jouait n'avait donc AUCUN effet
    // avant le prochain zap ou le prochain lancement de l'appli, puisque
    // `PlayerController` lisait ces réglages une seule fois à sa création
    // (`PlayerController.create`, juste au-dessus). On observe ici en continu le
    // `SettingsRepository` réellement modifié par l'incrustation Réglages et on répercute
    // chaque changement sur le contrôleur déjà en place via `updateSettings` (no-op si la
    // valeur émise est identique à celle déjà appliquée, voir sa doc) plutôt que d'en
    // recréer un.
    if (appRepository != null) {
        LaunchedEffect(controller) {
            val activeController = controller ?: return@LaunchedEffect
            appRepository.settings.playerSettings.collect { newSettings ->
                activeController.updateSettings(newSettings)
            }
        }
    }

    // Minuteur d'auto-masquage (8a) : redemarre a chaque nouvelle interaction grace a
    // osdShowToken (voir la doc de la fonction). Si l'OSD a ete masque manuellement
    // entre-temps (toggleOsd), osdVisible est deja false et ce delai n'a plus rien a
    // faire - d'ou la verification avant d'ecrire. Inutile en mini-lecteur (osdEnabled
    // = false -> osdVisible reste toujours false, rien a masquer).
    if (osdEnabled) {
        LaunchedEffect(osdShowToken, channel.id) {
            delay(OSD_AUTO_HIDE_MILLIS)
            if (osdVisible) osdVisible = false
        }
    }

    // Validation automatique de la saisie numerique (8c) : meme mecanique de minuteur
    // redemarrable que le masquage de l'OSD ci-dessus (numericEntryToken joue le role
    // d'osdShowToken). snapshot capture la saisie au moment ou CE delai a demarre ; si
    // elle a change entre-temps (nouvelle frappe -> nouveau LaunchedEffect, celui-ci
    // annule), on ne valide pas une saisie deja obsolete.
    if (osdEnabled) {
        LaunchedEffect(numericEntryToken, channel.id) {
            if (typedNumber.isEmpty()) return@LaunchedEffect
            val snapshot = typedNumber
            delay(NUMERIC_ENTRY_AUTO_VALIDATE_MILLIS)
            if (typedNumber == snapshot) validateTypedNumber()
        }
    }

    // Demande le focus Android (D-pad TV / clic mobile) des que la View existe, UNIQUEMENT
    // en plein ecran (voir la doc de osdEnabled) - sinon la View reste passive et
    // laisse le Box englobant du mini-lecteur gerer seul le tap/D-pad ("agrandir").
    // playerView change d'identite a chaque nouvelle chaine (nouvelle instance de cet
    // ecran dans le NavHost), donc ce LaunchedEffect se redeclenche naturellement au
    // zapping plutot qu'une seule fois pour toute la duree de vie du composable.
    if (osdEnabled) {
        LaunchedEffect(playerView) {
            playerView?.requestFocus()
        }
    }

    // Heure courante + ecart au direct (4.5/8b) : rafraichis toutes les OSD_CLOCK_TICK_MILLIS
    // tant qu'un controller existe, independamment de la visibilite de l'OSD (l'ecart au
    // direct alimente aussi PlayerMetricsBridge, lu par Diagnostic/Reglages meme quand
    // l'OSD est masque par le minuteur). Uniquement en plein ecran (osdEnabled) : le
    // mini-lecteur de l'accueil ne doit jamais ecrire dans ce pont partage.
    if (osdEnabled) {
        LaunchedEffect(controller) {
            val activeController = controller ?: return@LaunchedEffect
            while (true) {
                nowMillis = System.currentTimeMillis()
                val offset = activeController.currentLiveEdgeOffsetSeconds()
                liveEdgeOffsetSeconds = offset
                PlayerMetricsBridge.updateLiveEdgeOffsetSeconds(offset)
                // Étape 10 (§5.5) : niveau de tampon, natif comme l'écart au direct
                // ci-dessus - même cadence, pas besoin d'un tick dédié.
                PlayerMetricsBridge.updateBufferedSeconds(activeController.currentBufferedSeconds())
                delay(OSD_CLOCK_TICK_MILLIS)
            }
        }
    }

    // Étape 10 (§5.5) : métriques Diagnostic évènementielles (débit, résolution/bitrate,
    // segments, erreurs), alimentées par l'AnalyticsListener de PlayerController - simple
    // relais vers PlayerMetricsBridge, une coroutine de collecte par flux plutôt qu'un
    // polling (contrairement à l'écart au direct/tampon ci-dessus, natifs mais sans
    // évènement Media3 associé). Un seul LaunchedEffect, les quatre `collect` tournent en
    // parallèle via launch() dans la même portée - toutes annulées ensemble en sortant de
    // composition.
    if (osdEnabled) {
        LaunchedEffect(controller) {
            val activeController = controller ?: return@LaunchedEffect
            launch { activeController.networkThroughputKbps.collect { PlayerMetricsBridge.updateNetworkThroughputKbps(it) } }
            launch {
                combine(activeController.streamResolution, activeController.streamBitrateKbps) { resolution, bitrate -> resolution to bitrate }
                    .collect { (resolution, bitrate) -> PlayerMetricsBridge.updateStreamFormat(resolution, bitrate) }
            }
            launch {
                combine(activeController.segmentsSucceeded, activeController.segmentsFailed) { succeeded, failed -> succeeded to failed }
                    .collect { (succeeded, failed) -> PlayerMetricsBridge.updateSegmentCounts(succeeded, failed) }
            }
            launch { activeController.recentErrors.collect { PlayerMetricsBridge.updateRecentErrors(it) } }
        }
    }

    // Programme en cours (4.6/8b) : une seule resolution par prise d'antenne OU par zap
    // (cle currentChannel.id, pas la chaine de navigation figee channel.id - sinon un
    // zap ne rafraichirait jamais ce titre), pas a chaque tick ci-dessus. appRepository
    // est null pour le mini-lecteur (osdEnabled = false, voir les appelants de cette
    // fonction) : aucune tentative de resolution EPG dans ce cas, coherent avec le fait
    // que l'OSD n'y est de toute facon jamais rendu.
    //
    // getOrLoad (EpgRepository, etape 9a) plutot qu'un rechargement systematique a chaque
    // prise d'antenne : le cache partage avec Reglages (bouton "Rafraichir l'EPG") evite
    // de retelecharger tout le guide XMLTV a chaque zap sur la meme playlist - remplace
    // l'ancien EpgNowLookup (sans cache, et qui visait un champ Playlist inexistant).
    // Désactivé le 25 juillet 2026 à la demande de l'utilisateur : cette résolution EPG se
    // déclenchait à l'entrée en plein écran (tap sur le mini-lecteur) en même temps que
    // HomeViewModel.loadPreviewProgramTitle (encore en cours côté mini-lecteur à ce
    // moment-là), créant un conflit perçu pendant la transition. Le Mutex par playlist
    // (EpgRepository, fix du même jour) empêche déjà les deux appels de télécharger/parser
    // en double, mais le second attend quand même la fin du premier - ce blocage au moment
    // précis du passage plein écran restait perceptible. currentProgramTitle reste donc
    // toujours `null` (cas déjà géré nativement partout où il est lu, voir PlayerOsd) :
    // plus aucune requête EPG n'est émise pendant la lecture plein écran.
    // Pour réactiver : décommenter le bloc ci-dessous.
    // LaunchedEffect(currentChannel.id) {
    //     currentProgramTitle = null
    //     val repository = appRepository ?: return@LaunchedEffect
    //     val activeChannel = currentChannel
    //     val tvgId = activeChannel.tvgId
    //     if (tvgId.isNullOrBlank()) return@LaunchedEffect
    //     val playlist = repository.playlists.getById(activeChannel.playlistId) ?: return@LaunchedEffect
    //     val result = repository.epg.getOrLoad(playlist)
    //     currentProgramTitle = (result as? EpgLoadResult.Success)
    //         ?.programsByChannel
    //         ?.get(tvgId)
    //         ?.firstOrNull { it.isCurrentlyAiring(System.currentTimeMillis()) }
    //         ?.title
    // }

    // Volume (8d5) : synchronisation inverse par rapport a 8d4 - suit les changements de
    // volume DECLENCHES AILLEURS (boutons physiques de l'appareil pendant que le plein
    // ecran est ouvert), pour que le curseur OSD ne se desynchronise pas. Le sens
    // "curseur -> systeme" (setSystemVolume, 8d4) et celui-ci ("systeme -> curseur") sont
    // deux mecanismes distincts et necessaires : sans celui-ci, appuyer sur les boutons
    // physiques ferait bouger le VRAI volume sans jamais rafraichir le curseur affiche.
    //
    // ContentObserver sur Settings.System.CONTENT_URI plutot que le broadcast
    // "android.media.VOLUME_CHANGED_ACTION" : ce dernier fonctionne en pratique sur
    // toutes les versions d'Android mais n'est pas une API publique documentee
    // (constante @hide de AudioManager) - le ContentObserver s'appuie uniquement sur des
    // API publiques (Settings, ContentObserver). Contrepartie assumee : cet observer se
    // declenche pour N'IMPORTE QUEL changement dans Settings.System (pas seulement le
    // volume), d'ou la relecture de la valeur reelle a chaque notification plutot qu'une
    // hypothese sur sa cause - cout negligeable (un getStreamVolume() de plus) face a la
    // fiabilite gagnee.
    //
    // Uniquement en plein ecran (osdEnabled) : le mini-lecteur ne rend jamais le curseur
    // (PlayerOsd n'y est jamais rendu), rien a synchroniser dans ce contexte.
    if (osdEnabled) {
        DisposableEffect(channel.id) {
            val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val currentFraction = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / maxStreamVolume.toFloat()
                    if (currentFraction != volumeFraction) volumeFraction = currentFraction
                }
            }
            context.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
            onDispose {
                context.contentResolver.unregisterContentObserver(observer)
            }
        }
    }

    // Mode immersif reel (paragraphe 4.5) : uniquement en plein ecran (osdEnabled),
    // jamais pour le mini-lecteur (voir la doc de la fonction sur osdEnabled - y activer
    // ceci masquerait les barres systeme derriere l'accueil, sans rapport avec un simple
    // apercu en tete d'ecran). Cle Unit plutot que channel.id : le param channel (entree
    // de navigation) ne change pas pendant le zapping (voir la doc de la fonction), donc
    // ce DisposableEffect s'installe une seule fois a l'entree en plein ecran et se
    // demonte a la sortie - pas besoin de le relancer a chaque zap.
    //
    // BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE (plutot que BEHAVIOR_DEFAULT) : un balayage
    // depuis le bord fait reapparaitre temporairement les barres, comme sur un lecteur
    // video standard, sans qu'un tap ailleurs ne les fasse revenir "en dur" a la place de
    // toggleOsd() ci-dessus.
    //
    // Necessite le prerequis edge-to-edge pose par MainActivity/TvMainActivity
    // (WindowCompat.setDecorFitsSystemWindows(window, false) avant setContent) : sans
    // lui, le systeme continue de reserver la place des barres meme une fois masquees.
    if (osdEnabled) {
        DisposableEffect(Unit) {
            val window = context.findActivity()?.window
            val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
            insetsController?.let {
                it.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            }
            onDispose {
                // Sortie du plein ecran (retour arriere ou navigation ailleurs) : les
                // barres systeme redeviennent visibles, comme avant l'entree en immersion.
                insetsController?.show(WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.navigationBars())
            }
        }
    }

    DisposableEffect(channel.id) {
        onDispose {
            controller?.release()
            if (osdEnabled) {
                PlayerMetricsBridge.clear()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        val currentController = controller
        if (currentController == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )
            return@Box
        }

        val uiState by currentController.uiState.collectAsState()

        val availableQualities by currentController.availableQualities.collectAsState()
        val selectedQuality by currentController.selectedQuality.collectAsState()

        // Fix (2026-08-04) : `currentController.exoPlayer` peut désormais changer
        // d'instance en cours de vie de cet écran (voir PlayerController.updateSettings,
        // rappelé quand Réglages → Lecteur change pendant la lecture) — la lire via ce
        // StateFlow collecté, plutôt qu'une seule fois dans `factory` ci-dessous, permet à
        // `update` de rebrancher la PlayerView sur la nouvelle instance dès qu'elle change,
        // au lieu de rester accrochée à un ExoPlayer libéré entre-temps (écran figé/noir).
        val activePlayer by currentController.player.collectAsState()

        AndroidView(
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                if (view.player !== activePlayer) {
                    view.player = activePlayer
                }
            },
            factory = { ctx ->
                PlayerView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    player = activePlayer
                    // Controles Media3 integres desactives au profit de PlayerOsd - voir
                    // la doc de la fonction (8a).
                    useController = false
                    isFocusable = true
                    isFocusableInTouchMode = true
                    // Ecran plein cadre = on regarde activement une video : ne doit jamais
                    // s'eteindre tout seul, contrairement au reste de l'app.
                    keepScreenOn = true

                    if (osdEnabled) {
                        // Tap simple = bascule OSD, glissement vertical = zapping (8c) -
                        // un seul GestureDetector pour distinguer les deux, pose sur cette
                        // vraie View Android (voir la doc de la fonction sur pourquoi un
                        // geste Compose pose sur le Box englobant ne recevrait rien).
                        val swipeMinDistancePx = SWIPE_MIN_DISTANCE_DP * resources.displayMetrics.density
                        val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent): Boolean {
                                // Sans ceci, onDown() renvoie false par defaut et Android
                                // n'achemine jamais la suite du geste (ACTION_MOVE/UP) a
                                // cette View : seul le tout premier ACTION_DOWN serait recu,
                                // onSingleTapUp/onFling ne se declencheraient plus jamais -
                                // c'est ce qui empechait un tap de refaire apparaitre l'OSD
                                // (boutons reglages, etc.) une fois sorti du plein ecran.
                                return true
                            }

                            override fun onSingleTapUp(e: MotionEvent): Boolean {
                                toggleOsd()
                                return true
                            }

                            override fun onFling(
                                e1: MotionEvent?,
                                e2: MotionEvent,
                                velocityX: Float,
                                velocityY: Float
                            ): Boolean {
                                val startY = e1?.y ?: return false
                                val deltaY = e2.y - startY
                                val deltaX = e2.x - e1.x
                                if (abs(deltaY) < swipeMinDistancePx || abs(deltaY) < abs(deltaX)) return false
                                zap(if (deltaY < 0) ZapDirection.NEXT else ZapDirection.PREVIOUS)
                                return true
                            }
                        })
                        setOnTouchListener { _, event -> gestureDetector.onTouchEvent(event) }
                        setOnKeyListener(
                            buildPlayerViewKeyListener(
                                showOsd = ::showOsd,
                                zap = ::zap,
                                appendDigit = ::appendDigit,
                                hasTypedNumber = { typedNumber.isNotEmpty() },
                                validateTypedNumber = ::validateTypedNumber,
                                togglePlayPause = { currentController.togglePlayPause() }
                            )
                        )
                    }
                }.also { playerView = it }
            }
        )

        if (osdEnabled) {
            PlayerOsd(
                channel = currentChannel,
                visible = osdVisible,
                nowMillis = nowMillis,
                liveEdgeOffsetSeconds = liveEdgeOffsetSeconds,
                currentProgramTitle = currentProgramTitle,
                isPlaying = osdIsPlaying(uiState),
                onTogglePlayPause = { currentController.togglePlayPause() },
                volumeFraction = volumeFraction,
                onVolumeChange = ::setSystemVolume,
                availableQualities = availableQualities,
                selectedQuality = selectedQuality,
                onQualityChange = { option -> currentController.setQualityOverride(option) },
                onRequestNumericEntry = if (appRepository != null) { { openKeypad() } } else null,
                onOpenSettings = if (appRepository != null) { { settingsOverlayVisible = true } } else null,
                // Depuis 8d9, PlayerOsd gère lui-même deux zones (bandeau haut + barre de
                // contrôles bas) : il lui faut tout l'espace, plus seulement le haut.
                modifier = Modifier.fillMaxSize()
            )

            PlayerZapEntryOverlay(
                visible = typedNumber.isNotEmpty() || keypadVisible,
                typedNumber = typedNumber,
                showKeypad = keypadVisible,
                onDigit = ::appendDigit,
                onValidate = ::validateTypedNumber,
                onDismiss = ::cancelNumericEntry,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        when (val state = uiState) {
            is PlayerUiState.Buffering -> CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White
            )

            is PlayerUiState.Error -> {
                // En erreur, le focus D-pad doit quitter PlayerView (qui n'a plus aucun
                // controle utile a proposer, voir la doc de la fonction) pour se poser sur
                // "Reessayer" - sinon les touches D-pad n'iraient nulle part d'utile.
                val retryFocusRequester = remember(channel.id) { FocusRequester() }
                var isRetryFocused by remember(channel.id) { mutableStateOf(false) }

                LaunchedEffect(state) {
                    retryFocusRequester.requestFocus()
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Erreur de lecture (${state.message}) — appuyer pour réessayer",
                        color = Color.White,
                        modifier = Modifier
                            .focusRequester(retryFocusRequester)
                            .onFocusChanged { isRetryFocused = it.isFocused }
                            .border(width = if (isRetryFocused) 2.dp else 0.dp, color = Color.Red)
                            .padding(8.dp)
                            .clickable { currentController.retry(currentChannel) }
                    )
                }
            }

            else -> Unit
        }

        // Incrustation Réglages (§4.6) — voir la doc du paramètre settingsOverlayVisible
        // plus haut. Rendue en dernier dans ce Box pour passer au-dessus de tout le reste
        // (vidéo, OSD, indicateurs d'état) ; appRepository != null est garanti ici
        // puisque c'est la seule condition sous laquelle onOpenSettings existe (voir
        // l'appel à PlayerOsd ci-dessus).
        if (settingsOverlayVisible && appRepository != null) {
            SettingsScreen(
                appRepository = appRepository,
                onBack = { settingsOverlayVisible = false },
                onResetComplete = {
                    settingsOverlayVisible = false
                    onRequestFullReset()
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * Remonte du [Context] Compose (potentiellement un ContextWrapper - theme, langue...)
 * jusqu'a l'[Activity] qui porte reellement la fenetre (mode immersif ci-dessus) -
 * LocalContext.current n'est pas toujours directement une Activity. Retourne null si
 * aucune Activity n'est trouvee (cas theorique ici, PlayerScreen n'etant instancie que
 * depuis MainActivity/TvMainActivity ou leurs mini-lecteurs, mais le mode immersif est
 * de toute facon inactif pour ces derniers - voir osdEnabled).
 */
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Icone du bouton lecture/pause (8d1) : reflete `PlayerUiState.Ready.isPlaying` quand cet
 * etat est connu. En dehors de `Ready` :
 * - `Buffering` -> `true` (icone "pause") : `playChannel`/`togglePlayPause` ont deja mis
 *   `playWhenReady = true` a ce stade (chargement initial ou reprise), donc l'intention de
 *   l'utilisateur est bien "en lecture" meme si aucune image ne s'affiche encore - montrer
 *   l'icone "play" pendant ce court instant suggererait a tort que la chaine est en pause.
 * - `Idle`/`Error` -> `false` (icone "play") : rien n'est en cours, `togglePlayPause` n'a
 *   d'ailleurs aucun effet en `Error` (voir sa doc dans `PlayerController`).
 *
 * Approximation pragmatique plutot qu'un `StateFlow<Boolean>` dedie sur
 * `exoPlayer.playWhenReady` dans `PlayerController` : suffisant pour une icone, a revoir
 * si un besoin plus fin (ex. Diagnostic) apparait plus tard.
 */
private fun osdIsPlaying(uiState: PlayerUiState): Boolean = when (uiState) {
    is PlayerUiState.Ready -> uiState.isPlaying
    is PlayerUiState.Buffering -> true
    is PlayerUiState.Idle, is PlayerUiState.Error -> false
}

/**
 * Construit le OnKeyListener de PlayerView (8a, etendu en 8c puis 8d2) :
 * - Chiffres (KEYCODE_0..KEYCODE_9) -> appendDigit (saisie numerique directe, 5.3).
 * - DPAD_UP/DOWN -> zap (suivant/precedent) - voir la doc de PlayerScreen sur la
 *   convention retenue (haut = suivant, bas = precedent).
 * - DPAD_CENTER/ENTER -> valide la saisie en cours si non vide (validateTypedNumber via
 *   hasTypedNumber), sinon bascule play/pause (togglePlayPause, 8d2 - anciennement
 *   showOsd() a cette meme place jusqu'a 8c inclus, voir le commentaire inline ci-dessous
 *   pour l'arbitrage).
 * - DPAD_LEFT/RIGHT -> affiche l'OSD, comportement inchange depuis 8a.
 *
 * Fonction top-level (plutot qu'un lambda inline dans PlayerScreen) pour garder la
 * factory AndroidView lisible malgre le nombre de cas desormais geres.
 */
private fun buildPlayerViewKeyListener(
    showOsd: () -> Unit,
    zap: (ZapDirection) -> Unit,
    appendDigit: (Int) -> Unit,
    hasTypedNumber: () -> Boolean,
    validateTypedNumber: () -> Unit,
    togglePlayPause: () -> Unit
): android.view.View.OnKeyListener = android.view.View.OnKeyListener { _, keyCode, event ->
    if (event.action != KeyEvent.ACTION_DOWN) return@OnKeyListener false
    when {
        keyCode in DIGIT_KEY_CODES -> {
            appendDigit(keyCode - KeyEvent.KEYCODE_0)
            true
        }
        keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
            zap(ZapDirection.NEXT)
            true
        }
        keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
            zap(ZapDirection.PREVIOUS)
            true
        }
        keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
            // 8c : une saisie numerique en cours a priorite absolue (OK = valider le
            // numero tape). 8d2 : sinon, OK bascule play/pause plutot que le showOsd()
            // de 8a - message de cadrage de 8d2 ("priorite a trancher"), tranche ainsi
            // car sur un vrai boitier IPTV la touche OK/play-pause de la telecommande
            // agit sur la lecture, jamais seulement sur l'affichage d'un bandeau. Le
            // showOsd() est de toute facon implicite : togglePlayPause() reutilise
            // PlayerController.togglePlayPause (5a), lui-meme sans effet sur la
            // visibilite de l'OSD - c'est le focus/l'interaction geree ailleurs (8d10)
            // qui reaffichera l'OSD si besoin. A ce stade (avant 8d10), l'OSD doit deja
            // etre visible pour que l'utilisateur voie l'icone changer.
            if (hasTypedNumber()) validateTypedNumber() else togglePlayPause()
            true
        }
        keyCode in OSD_ONLY_DPAD_KEY_CODES -> {
            showOsd()
            true
        }
        else -> false
    }
}

/** Chiffres 0-9 de la telecommande numerique (5.3/8c, saisie directe). */
private val DIGIT_KEY_CODES = (KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9).toSet()

/** Touches D-pad qui se contentent d'afficher l'OSD (8a) - UP/DOWN ont un role propre
 *  depuis 8c (zapping), CENTER/ENTER depuis 8c (valider une saisie) puis 8d2
 *  (play/pause) - voir buildPlayerViewKeyListener. */
private val OSD_ONLY_DPAD_KEY_CODES = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT
)
