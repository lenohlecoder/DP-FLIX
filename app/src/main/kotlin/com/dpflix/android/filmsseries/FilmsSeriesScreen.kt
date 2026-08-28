package com.dpflix.android.filmsseries

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.filmsseries.stream.DetectedStream
import com.dpflix.android.filmsseries.stream.StreamSniffer
import com.djamylova.tvflix.TvFlixWebView
import com.djamylova.tvflix.cursor.CursorLayout
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.GeneralSettings
import com.dpflix.android.settings.DiagnosticSystemMonitor
import com.dpflix.android.ui.theme.DpFlixColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Section "Films et Séries" (remplace l'ancien Guide TV, retiré le 25 juillet 2026 — voir
 * `DpFlixDestination`) : navigateur intégré verrouillé sur une plateforme externe. Trois
 * plateformes indépendantes possibles ("Stream 1"/"Stream 2"/"Stream 3", French-Stream +
 * TheMovieBox, 08/08 + 15/08) — [streamIndex] sélectionne laquelle :
 * 1 → [GeneralSettings.filmsSeriesUrl]/[GeneralSettings.DEFAULT_FILMS_SERIES_URL],
 * 2 → [GeneralSettings.filmsSeriesUrl2]/[GeneralSettings.DEFAULT_FILMS_SERIES_URL_2],
 * 3 → [GeneralSettings.filmsSeriesUrl3]/[GeneralSettings.DEFAULT_FILMS_SERIES_URL_3].
 * Choisi à l'accueil via `FilmsSeriesStreamPickerDialog`, transporté par
 * `DpFlixDestination.FilmsSeries`.
 *
 * Réutilisé côté mobile ET TV — voir `FilmsSeriesScreenTv.kt` pour le petit wrapper qui
 * l'expose sous ce nom côté TV, avec [showVirtualCursor] activé (§ ci-dessous).
 *
 * ## Verrouillage du navigateur
 * - Un seul domaine autorisé par défaut : celui de [url] au moment de l'ouverture, plus
 *   ses sous-domaines (`*.host`), l'infra du stream ([STREAM_INFRASTRUCTURE_HOSTS]) et les
 *   domaines d'exception Réglages — vérifiés dans [WebViewClient.shouldOverrideUrlLoading].
 * - Deux modes, réglables via l'icône Réglages de l'écran (`GeneralSettings.strictDomainLock`) :
 *   - **Strict** (`strictDomainLock = true`) : whitelist exclusive, toute navigation
 *     hors de cet ensemble est bloquée sans exception.
 *   - **Ouvert + protection soft** (`strictDomainLock = false`, réglage par défaut,
 *     comme un navigateur TV classique) : la navigation est laissée ouverte (redirects
 *     anti-bot, CDN, OAuth légitimes...), sauf vers un hôte de la liste noire
 *     [KNOWN_AD_REDIRECT_HOSTS] (régies pub / redirecteurs connus), qui reste bloqué
 *     même via redirection HTTP ou geste utilisateur (voir doc de
 *     [isKnownAdRedirectHost] sur l'ordre de vérification, volontairement place avant
 *     ces deux exemptions).
 * - Dans les deux modes : jamais de nouvel onglet ni de sortie vers un navigateur externe,
 *   la navigation bloquée est simplement ignorée (la page reste sur son état courant).
 * - `setSupportMultipleWindows(true)` + [WebChromeClient.onCreateWindow] : les
 *   `window.open()`/`target="_blank"` déclenchés par un vrai geste utilisateur sont
 *   capturés et chargés dans cette même WebView (jamais une vraie 2e fenêtre — voir
 *   doc de `onCreateWindow`) ; ceux sans geste utilisateur (pop-under auto) restent
 *   bloqués. Pas de barre d'adresse, de navigation précédente/suivante ni de menu
 *   long-press (désactivé explicitement) : aucun chrome de navigateur visible.
 *
 * ## Téléchargement Films & Séries (module download, principe 1DM)
 * - [StreamSniffer] observe les requêtes via [WebViewClient.shouldInterceptRequest]
 *   (sans jamais bloquer le chargement) et accumule les flux `.mp4` / `.m3u8` / etc.
 *   réellement chargés par la page — reset à chaque nouvelle page ([onPageStarted]) et à
 *   chaque nouvelle plateforme ([streamIndex]/[url]).
 * - Une flèche ↓ apparaît en haut à droite **uniquement hors plein écran** dès qu'au
 *   moins un flux est détecté (badge = nombre de flux) → ouvre [DetectedStreamsDialog].
 *   Le choix d'un flux l'envoie à [downloadManager] (`null` = téléchargement non branché
 *   sur cet écran, ex. avant qu'`AppContainer`/nav ne le fournissent).
 * - Plein écran HTML5 du lecteur vidéo du site (`WebChromeClient.onShowCustomView`/
 *   `onHideCustomView`) masque la flèche et referme le dialogue s'il était ouvert —
 *   règle non négociable du cahier des charges (flèche jamais visible en plein écran).
 * - [onOpenDownloads] (optionnel) affiche un raccourci "Téléch." vers la bibliothèque
 *   `DownloadsScreen`, à côté de la flèche.
 *
 * ## Retour (§ demande utilisateur, révisé 08/08)
 * [BackHandler] : d'abord fermer le dialogue de flux détectés s'il est ouvert, sinon
 * priorité à la navigation dans l'historique du site ([WebView.canGoBack]/
 * [WebView.goBack]) — tant que la WebView peut reculer d'une page, un appui retour la
 * fait simplement reculer, sans toucher au compteur de double-appui. Ce n'est que
 * lorsqu'il n'y a plus de page précédente sur le site qu'un premier appui affiche un
 * `Toast` d'avertissement et ouvre une fenêtre de 2 secondes ([DOUBLE_BACK_WINDOW_MS]),
 * un second appui dans cette fenêtre déclenche [onNavigateHome].
 *
 * ## Curseur virtuel TV (§ demande utilisateur, ajouté 09/08)
 * Un site web ordinaire n'est pas conçu pour une navigation D-pad (haut/bas/gauche/droite)
 * — [showVirtualCursor] (actif uniquement côté TV, voir `FilmsSeriesScreenTv`) affiche un
 * curseur superposé à la `WebView`, déplacé par les flèches de la télécommande
 * ([DPAD_MOVE_STEP_DP] par appui) et qui simule un tap (`ACTION_DOWN`/`ACTION_UP` via
 * [WebView.dispatchTouchEvent]) à sa position courante lorsqu'on appuie sur OK/Sélection.
 * La `WebView` elle-même n'est pas focusable dans ce mode (`isFocusable = false`) pour que
 * toutes les pressions D-pad remontent au curseur plutôt que d'être interceptées par le
 * contenu de la page.
 */
@Composable
fun FilmsSeriesScreen(
    appRepository: AppRepository,
    onNavigateHome: () -> Unit,
    streamIndex: Int = 1,
    showVirtualCursor: Boolean = false,
    /** Utilise le moteur TvFlix uniquement pour le rendu/curseur TV. Toutes les
     * fonctionnalités de cet écran (téléchargement, anti-redirection, navigation,
     * sniffer, plein écran, historique, etc.) restent celles de FilmsSeriesScreen. */
    useTvFlix: Boolean = false,
    downloadManager: FilmDownloadManager? = null,
    onOpenDownloads: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val generalSettings by appRepository.settings.generalSettings.collectAsState(initial = null)
    val url = when (streamIndex) {
        2 -> generalSettings?.filmsSeriesUrl2 ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL_2
        3 -> generalSettings?.filmsSeriesUrl3 ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL_3
        4 -> GeneralSettings.DEFAULT_FILMS_SERIES_URL_4
        5 -> GeneralSettings.DEFAULT_FILMS_SERIES_URL_5
        else -> generalSettings?.filmsSeriesUrl ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var awaitingSecondBackPress by remember { mutableStateOf(false) }
    val onNavigateHomeState = rememberUpdatedState(onNavigateHome)
    // Référence à la WebView active, posée par `LockedWebView` une fois créée, pour que
    // le BackHandler ci-dessous puisse lui demander de reculer dans l'historique du site,
    // et pour que le curseur virtuel (si actif) puisse lui envoyer des taps simulés.
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Fix (13 août 2026) : navigation persistante de la WebView à travers les allers-
    // retours vers « Mes téléchargements ». Auparavant, ouvrir cet écran (bouton
    // téléchargements) faisait quitter la composition de `FilmsSeriesScreen` — l'ancienne
    // WebView était détruite (`onRelease` de `LockedWebView`) et, au retour (popBackStack),
    // une toute nouvelle WebView était recréée par `factory` avec un simple `loadUrl(url)`,
    // ramenant l'utilisateur à l'accueil du site au lieu de la page (série/épisode) qu'il
    // était en train de regarder. `rememberSaveable` conserve ce Bundle (WebView.saveState)
    // à travers cette sortie/entrée de composition — voir `LockedWebView` plus bas, qui
    // restaure l'historique de navigation avec `restoreState()` au lieu de recharger [url]
    // quand ce Bundle est déjà présent.
    var webViewStateBundle by rememberSaveable { mutableStateOf<Bundle?>(null) }
    // Le Bundle ci-dessus ne doit être restauré que sur la MÊME plateforme (`url`) que
    // celle sur laquelle il a été capturé — sinon un changement de plateforme (Stream 1 ↔
    // Stream 2, ou lien modifié dans Réglages) restaurerait par erreur l'historique de
    // navigation de l'ancien site sur le nouveau.
    var webViewStateUrl by rememberSaveable { mutableStateOf<String?>(null) }

    // Module téléchargement — sniffer partagé pour la durée de l'écran (reset à chaque
    // nouvelle page ou nouvelle plateforme, voir les LaunchedEffect ci-dessous).
    val sniffer = remember { StreamSniffer() }
    val detectedStreams by sniffer.detectedStreams.collectAsState()
    var isPageFullscreen by remember { mutableStateOf(false) }
    var showStreamsDialog by remember { mutableStateOf(false) }
    var showExceptionDomainsDialog by remember { mutableStateOf(false) }
    var showDpFlixMenu by remember { mutableStateOf(false) }
    // Titre de la page WebView courante, pour nommer le téléchargement à l'enqueue.
    var pageTitle by remember { mutableStateOf<String?>(null) }

    // État du curseur virtuel — inutilisé (et sans overhead notable) quand
    // `showVirtualCursor` est false.
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var cursorOffset by remember { mutableStateOf<Offset?>(null) }
    val focusRequester = remember { FocusRequester() }
    val density = LocalDensity.current
    val cursorStepPx = with(density) { DPAD_MOVE_STEP_DP.dp.toPx() }

    LaunchedEffect(containerSize) {
        if (showVirtualCursor && cursorOffset == null && containerSize != IntSize.Zero) {
            // Curseur centré à l'ouverture + premier survol pour initialiser le DOM.
            val start = Offset(containerSize.width / 2f, containerSize.height / 2f)
            cursorOffset = start
            webViewRef.value?.simulateHover(start.x, start.y)
        }
    }

    if (showVirtualCursor) {
        LaunchedEffect(Unit) { focusRequester.requestFocus() }
    }

    LaunchedEffect(awaitingSecondBackPress) {
        if (awaitingSecondBackPress) {
            delay(DOUBLE_BACK_WINDOW_MS)
            awaitingSecondBackPress = false
        }
    }

    // Nouvelle plateforme (changement de streamIndex/url) → repart d'un sniffer vide et
    // referme tout état de téléchargement transitoire de l'ancienne page.
    LaunchedEffect(url) {
        sniffer.resetForNewPage(url)
        showStreamsDialog = false
        isPageFullscreen = false
    }

    BackHandler {
        val webView = webViewRef.value
        when {
            showStreamsDialog -> showStreamsDialog = false
            webView != null && webView.canGoBack() -> webView.goBack()
            awaitingSecondBackPress -> {
                awaitingSecondBackPress = false
                onNavigateHomeState.value()
            }
            else -> {
                awaitingSecondBackPress = true
                Toast.makeText(context, "Appuyez de nouveau sur retour pour revenir à l'accueil", Toast.LENGTH_SHORT).show()
            }
        }
    }

    var boxModifier = modifier.fillMaxSize().statusBarsPadding()
    if (showVirtualCursor) {
        boxModifier = boxModifier
            .onSizeChanged { containerSize = it }
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type != KeyEventType.KeyDown) return@onKeyEvent false
                val current = cursorOffset ?: return@onKeyEvent false
                when (keyEvent.key) {
                    Key.DirectionUp -> {
                        val next = current.copy(y = (current.y - cursorStepPx).coerceAtLeast(0f))
                        cursorOffset = next
                        webViewRef.value?.simulateHover(next.x, next.y)
                        true
                    }
                    Key.DirectionDown -> {
                        val next = current.copy(
                            y = (current.y + cursorStepPx).coerceAtMost(containerSize.height.toFloat())
                        )
                        cursorOffset = next
                        webViewRef.value?.simulateHover(next.x, next.y)
                        true
                    }
                    Key.DirectionLeft -> {
                        val next = current.copy(x = (current.x - cursorStepPx).coerceAtLeast(0f))
                        cursorOffset = next
                        webViewRef.value?.simulateHover(next.x, next.y)
                        true
                    }
                    Key.DirectionRight -> {
                        val next = current.copy(
                            x = (current.x + cursorStepPx).coerceAtMost(containerSize.width.toFloat())
                        )
                        cursorOffset = next
                        webViewRef.value?.simulateHover(next.x, next.y)
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        webViewRef.value?.simulateClick(current.x, current.y)
                        true
                    }
                    else -> false
                }
            }
    }

    // Politique de verrouillage de domaine par stream — voir doc de
    // [resolveStrictDomainLock] : Stream 1 toujours ouvert, Stream 2 toujours confiné à son
    // propre domaine, Stream 3 (restauré 27/08/2026) et les autres streams suivent le
    // réglage utilisateur (Réglages → icône DP-FLIX, OFF par défaut).
    val effectiveStrictDomainLock = resolveStrictDomainLock(
        streamIndex = streamIndex,
        userSetting = generalSettings?.strictDomainLock ?: false,
    )

    Box(modifier = boxModifier) {
        if (generalSettings == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            // `key(url)` : si l'utilisateur modifie le lien dans Réglages pendant que cet
            // écran est déjà ouvert (retour arrière, changement, retour ici), on force une
            // toute nouvelle WebView plutôt que de tenter un `loadUrl` sur l'existante —
            // plus simple et plus sûr que de garder une référence mutable à la WebView. Le
            // 3e élément de la clé réagit aussi à un changement live du réglage utilisateur
            // (Stream 2) pendant que l'écran est déjà ouvert.
            key(url, streamIndex, effectiveStrictDomainLock) {
                LockedWebView(
                    url = url,
                    sniffer = sniffer,
                    // Restauré 27/08/2026 (§ README-stream3-restauration.md) : profil desktop
                    // sur TOUTES les plateformes pour Stream 3 (mobile + TV), comme dans la
                    // version où ce stream s'affichait correctement — la restriction au seul
                    // moteur TV est ce qui a changé le rendu mobile entre les deux versions.
                    preferDesktopUserAgent = streamIndex == 3,
                    // Stream 3 : certaines TV sont instables avec le WebView en couche logicielle.
                    // Les autres streams conservent le correctif Z-order existant.
                    forceSoftwareLayer = showVirtualCursor && !useTvFlix,
                    useTvFlix = useTvFlix,
                    strictDomainLock = effectiveStrictDomainLock,
                    hardBlockedHosts = resolveHardBlockedHosts(streamIndex),
                    savedState = webViewStateBundle.takeIf { webViewStateUrl == url },
                    onSaveState = { bundle ->
                        webViewStateBundle = bundle
                        webViewStateUrl = url
                    },
                    extraAllowedHosts = resolveAllowedHosts(
                        streamIndex = streamIndex,
                        userExtras = generalSettings?.extraAllowedDomains
                            ?: GeneralSettings.DEFAULT_EXTRA_ALLOWED_DOMAINS,
                    ),
                    onWebViewCreated = { webView ->
                        webViewRef.value = webView
                        if (showVirtualCursor && !useTvFlix) {
                            // Curseur historique mobile/TV conservé uniquement pour
                            // compatibilité. En TV, TvFlix CursorLayout prend le relais.
                            webView.isFocusable = false
                            webView.isFocusableInTouchMode = false
                            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        }
                    },
                    onFullscreenChanged = { fullscreen ->
                        isPageFullscreen = fullscreen
                        // Règle non négociable : jamais de flèche/dialogue en plein écran.
                        if (fullscreen) { showStreamsDialog = false; showDpFlixMenu = false }
                    },
                    onPageTitleChanged = { title -> pageTitle = title },
                    onRendererGone = {
                DiagnosticSystemMonitor.recordPlayback(
                    "Renderer WebView",
                    DiagnosticSystemMonitor.Status.ERROR,
                    "Le processus de rendu WebView a été arrêté par Android.",
                    "Renderer WebView indisponible ou mémoire insuffisante."
                )
                        // Fix (12 août 2026) : voir doc de `onRenderProcessGone` plus bas —
                        // sans ce callback, Android tue tout le processus de l'app dès que
                        // le processus de rendu WebView meurt (mémoire sous pression, ex.
                        // un téléchargement actif en tâche de fond). On revient proprement
                        // à l'accueil au lieu de laisser l'app entière s'arrêter net.
                        Toast.makeText(
                            context,
                            "La page s'est fermée (mémoire insuffisante) — retour à l'accueil.",
                            Toast.LENGTH_LONG
                        ).show()
                        onNavigateHomeState.value()
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val offset = cursorOffset
        if (showVirtualCursor && !useTvFlix && offset != null) {
            // Fix (16 août 2026) : voir doc de [VirtualCursorView] plus bas — une View
            // interop (la WebView) dessine toujours par-dessus le contenu Compose composé
            // après elle, curseur donc invisible tant qu'il restait un Composable pur.
            VirtualCursorOverlay(offsetPx = offset)
        }

        // Une seule petite icône DP-FLIX, centrée dans la barre supérieure. Elle regroupe
        // réglages, bibliothèque et détection/téléchargement sans masquer le site.
        if (!isPageFullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp)
            ) {
                IconButton(
                    onClick = { showDpFlixMenu = true },
                    modifier = Modifier.size(44.dp)
                ) {
                    Icon(
                        painter = painterResource(com.dpflix.android.R.drawable.ic_dpflix_menu),
                        contentDescription = "Menu DP-FLIX",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
                DropdownMenu(
                    expanded = showDpFlixMenu,
                    onDismissRequest = { showDpFlixMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Réglages") },
                        leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                        onClick = { showDpFlixMenu = false; showExceptionDomainsDialog = true }
                    )
                    if (onOpenDownloads != null) {
                        DropdownMenuItem(
                            text = { Text("Mes téléchargements") },
                            leadingIcon = { Icon(Icons.Filled.Folder, contentDescription = null) },
                            onClick = { showDpFlixMenu = false; onOpenDownloads() }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(if (detectedStreams.isEmpty()) "Aucun téléchargement détecté" else "Télécharger (${detectedStreams.size})")
                        },
                        leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                        enabled = detectedStreams.isNotEmpty(),
                        onClick = { showDpFlixMenu = false; showStreamsDialog = true }
                    )
                }
            }
        }

        if (showExceptionDomainsDialog) {
            ExceptionDomainsDialog(
                domains = generalSettings?.extraAllowedDomains
                    ?: GeneralSettings.DEFAULT_EXTRA_ALLOWED_DOMAINS,
                strictDomainLock = effectiveStrictDomainLock,
                // Le réglage n'a d'effet réel que pour les streams qui le suivent
                // (voir [resolveStrictDomainLock]) — seul Stream 1 a une politique fixe,
                // Stream 3 suit de nouveau ce réglage depuis sa restauration (27/08/2026).
                strictDomainLockEditable = streamIndex != 1,
                onStrictDomainLockChange = { enabled ->
                    scope.launch {
                        appRepository.settings.updateGeneralSettings { current ->
                            current.copy(strictDomainLock = enabled)
                        }
                    }
                },
                onDismiss = { showExceptionDomainsDialog = false },
                onAddDomain = { newDomain ->
                    scope.launch {
                        appRepository.settings.updateGeneralSettings { current ->
                            current.copy(extraAllowedDomains = current.extraAllowedDomains + newDomain)
                        }
                    }
                },
                onRemoveDomain = { domain ->
                    scope.launch {
                        appRepository.settings.updateGeneralSettings { current ->
                            current.copy(extraAllowedDomains = current.extraAllowedDomains - domain)
                        }
                    }
                }
            )
        }

        if (showStreamsDialog) {
            DetectedStreamsDialog(
                streams = detectedStreams,
                onDismiss = { showStreamsDialog = false },
                onClear = {
                    // Fix (12 août 2026) : option manuelle pour vider la liste — le sniffer
                    // ne se réinitialise automatiquement que sur un vrai changement de page
                    // WebView (`onPageStarted`) ; beaucoup de sites lecteur naviguent en
                    // JS/SPA sans déclencher ça, d'où l'accumulation observée en passant
                    // d'une vidéo à l'autre sans recharger la page.
                    sniffer.clear()
                },
                onSelectStream = { stream ->
                    val mgr = downloadManager
                    if (mgr == null) {
                        Toast.makeText(
                            context,
                            "Téléchargement indisponible (manager non branché)",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val ua = webViewRef.value?.settings?.userAgentString
                        val titleSnapshot = pageTitle
                        scope.launch {
                            try {
                                mgr.enqueue(
                                    stream = stream,
                                    title = titleSnapshot,
                                    userAgent = ua
                                )
                                Toast.makeText(
                                    context,
                                    "Téléchargement ajouté — ${stream.shortLabel}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            } catch (e: FilmDownloadManager.InsufficientStorageException) {
                                Toast.makeText(
                                    context,
                                    e.message ?: "Espace disque insuffisant",
                                    Toast.LENGTH_LONG
                                ).show()
                            } catch (e: Exception) {
                                Toast.makeText(
                                    context,
                                    "Erreur: ${e.message ?: "échec"}",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                    showStreamsDialog = false
                }
            )
        }
    }
}

/** Bouton flèche téléchargement — coin supérieur droit, badge = nombre de flux détectés. */
@Composable
private fun DownloadArrowButton(
    streamCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.65f),
        contentColor = Color.White,
        shadowElevation = 4.dp
    ) {
        IconButton(onClick = onClick) {
            BadgedBox(
                badge = {
                    if (streamCount > 0) {
                        Badge { Text(text = streamCount.coerceAtMost(99).toString()) }
                    }
                }
            ) {
                Icon(
                    imageVector = Icons.Filled.Download,
                    contentDescription = "Flux vidéo détectés"
                )
            }
        }
    }
}

/** Dialogue listant les flux capturés par [StreamSniffer] ; sélection → enqueue via [FilmDownloadManager]. */
@Composable
private fun DetectedStreamsDialog(
    streams: List<DetectedStream>,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSelectStream: (DetectedStream) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 480.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Flux détectés",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer")
                    }
                }
                Text(
                    text = "Choisissez un flux (MP4 ou HLS) à télécharger dans l'app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(streams, key = { it.url }) { stream ->
                        StreamRow(
                            stream = stream,
                            onClick = { onSelectStream(stream) }
                        )
                        HorizontalDivider()
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onClear,
                        enabled = streams.isNotEmpty()
                    ) {
                        Text("Effacer")
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Fermer")
                    }
                }
            }
        }
    }
}

/**
 * Gestion des domaines "exception" (§Réglages Films et Séries, 12 août 2026) : liste des
 * domaines, en plus du site principal, autorisés dans la navigation de la WebView verrouillée
 * — typiquement le CDN de téléchargement vers lequel le site redirige lui-même via son propre
 * lien "Télécharger" (ex. vidzy.cc, videodownloader.site). Ajout/suppression persistés immédiatement via
 * [onAddDomain]/[onRemoveDomain] (voir `GeneralSettings.extraAllowedDomains`).
 */
@Composable
private fun ExceptionDomainsDialog(
    domains: Set<String>,
    strictDomainLock: Boolean,
    // Fix 26/08/2026 : certains streams ont désormais une politique fixe (voir
    // [resolveStrictDomainLock]) — le switch reste visible pour montrer l'état réel
    // appliqué, mais devient non interactif pour ne pas laisser croire qu'il change
    // quelque chose sur ces streams.
    strictDomainLockEditable: Boolean = true,
    onStrictDomainLockChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onAddDomain: (String) -> Unit,
    onRemoveDomain: (String) -> Unit
) {
    var newDomainText by remember { mutableStateOf("") }
    val sortedDomains = remember(domains) { domains.sorted() }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Navigation WebView",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Fermer")
                    }
                }
                // Protection stricte (whitelist exclusive) — OFF par défaut : mode ouvert
                // + filtrage soft des régies pub connues, voir doc de classe de
                // `FilmsSeriesScreen`.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            text = "Activer la protection stricte",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = if (strictDomainLockEditable) {
                                "Verrouillage strict : seuls le site, ses sous-domaines " +
                                    "et la liste ci-dessous sont autorisés. Désactivé = navigation " +
                                    "ouverte (recommandé) avec filtrage soft des régies pub connues."
                            } else {
                                "Ce stream a une politique fixe, indépendante de ce réglage " +
                                    "(non modifiable ici)."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = strictDomainLock,
                        onCheckedChange = onStrictDomainLockChange,
                        enabled = strictDomainLockEditable
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    text = "Domaines d'exception",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                Text(
                    text = "Utiles surtout si la protection stricte est activée (CDN / " +
                        "pages de téléchargement hors domaine principal). Un domaine " +
                        "autorise aussi ses sous-domaines.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                HorizontalDivider()
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                ) {
                    items(sortedDomains, key = { it }) { domain ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        ) {
                            Text(
                                text = domain,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { onRemoveDomain(domain) }) {
                                Icon(
                                    imageVector = Icons.Filled.Delete,
                                    contentDescription = "Supprimer $domain"
                                )
                            }
                        }
                        HorizontalDivider()
                    }
                    if (sortedDomains.isEmpty()) {
                        item {
                            Text(
                                text = "Aucun domaine d'exception.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp)
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = newDomainText,
                        onValueChange = { newDomainText = it },
                        label = { Text("ex. vidzy.cc, videodownloader.site") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            // Normalisation minimale ici aussi (le check définitif se fait
                            // dans `LockedWebView`) : évite juste des entrées manifestement
                            // inutilisables (vides, avec espaces) dans la liste affichée.
                            val cleaned = newDomainText.trim().lowercase().removePrefix("www.")
                            if (cleaned.isNotEmpty()) {
                                onAddDomain(cleaned)
                                newDomainText = ""
                            }
                        },
                        enabled = newDomainText.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Ajouter")
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamRow(
    stream: DetectedStream,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(
            text = stream.shortLabel,
            style = MaterialTheme.typography.bodyLarge
        )
        Text(
            text = stream.displayHost,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = stream.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val DOUBLE_BACK_WINDOW_MS = 2000L
private const val DPAD_MOVE_STEP_DP = 48
private const val CURSOR_SIZE_DP = 36

/**
 * Convertit des pixels **vue WebView** (écran) en coordonnées **viewport CSS**
 * pour `elementFromPoint` / MouseEvent DOM.
 *
 * Ne pas utiliser `density` : avec UA desktop (Stream 3) la page est large et
 * [WebView.getScale] reflète le vrai zoom appliqué, souvent ≠ density.
 */
private fun WebView.cssViewportCoords(viewX: Float, viewY: Float): Pair<Float, Float> {
    val s = scale.coerceAtLeast(0.01f)
    return (viewX / s) to (viewY / s)
}

/**
 * Survol souris à ([x], [y]) en pixels vue WebView.
 * - Natif : ACTION_HOVER_MOVE (SOURCE_MOUSE)
 * - Fallback JS : mouseout/mouseleave sur l'ancien élément, puis
 *   mousemove/mouseover/mouseenter sur le nouveau (évite les :hover coincés)
 */
private fun WebView.simulateHover(x: Float, y: Float) {
    val time = SystemClock.uptimeMillis()
    val props = arrayOf(
        MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        }
    )
    val coords = arrayOf(
        MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        }
    )
    val hover = MotionEvent.obtain(
        time,
        time,
        MotionEvent.ACTION_HOVER_MOVE,
        1,
        props,
        coords,
        0,
        0,
        1f,
        1f,
        0,
        0,
        android.view.InputDevice.SOURCE_MOUSE,
        0
    )
    dispatchGenericMotionEvent(hover)
    hover.recycle()

    val (cssX, cssY) = cssViewportCoords(x, y)
    // window.__dpflixLastHoverEl : élément précédemment survolé (fallback JS only).
    evaluateJavascript(
        """
        (function(){
          var x = %f, y = %f;
          var prev = window.__dpflixLastHoverEl || null;
          var el = document.elementFromPoint(x, y);
          if (prev && prev !== el) {
            try {
              var leaveOpts = {
                bubbles: true, cancelable: true, clientX: x, clientY: y,
                view: window, relatedTarget: el
              };
              prev.dispatchEvent(new MouseEvent('mouseout', leaveOpts));
              prev.dispatchEvent(new MouseEvent('mouseleave', {
                bubbles: false, cancelable: true, clientX: x, clientY: y,
                view: window, relatedTarget: el
              }));
            } catch (e) {}
          }
          if (el) {
            try {
              var enterOpts = {
                bubbles: true, cancelable: true, clientX: x, clientY: y,
                view: window, relatedTarget: prev
              };
              el.dispatchEvent(new MouseEvent('mousemove', enterOpts));
              if (prev !== el) {
                el.dispatchEvent(new MouseEvent('mouseover', enterOpts));
                el.dispatchEvent(new MouseEvent('mouseenter', {
                  bubbles: false, cancelable: true, clientX: x, clientY: y,
                  view: window, relatedTarget: prev
                }));
              }
            } catch (e) {}
          }
          window.__dpflixLastHoverEl = el || null;
        })();
        """.trimIndent().format(cssX, cssY),
        null
    )
}

/** Simule un tap à ([x], [y]) — touch + clic souris DOM (coords CSS via scale WebView). */
private fun WebView.simulateClick(x: Float, y: Float) {
    simulateHover(x, y)
    val downTime = SystemClock.uptimeMillis()
    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
    dispatchTouchEvent(downEvent)
    dispatchTouchEvent(upEvent)
    downEvent.recycle()
    upEvent.recycle()

    val (cssX, cssY) = cssViewportCoords(x, y)
    evaluateJavascript(
        """
        (function(){
          var x = %f, y = %f;
          var el = document.elementFromPoint(x, y);
          if (!el) return false;
          var opts = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
          el.dispatchEvent(new MouseEvent('mousedown', opts));
          el.dispatchEvent(new MouseEvent('mouseup', opts));
          el.dispatchEvent(new MouseEvent('click', opts));
          try {
            if (el.matches && el.matches('input, textarea, select, [contenteditable="true"]')) {
              el.focus();
              return true;
            }
          } catch (e) {}
          return false;
        })();
        """.trimIndent().format(cssX, cssY)
    ) { focusedInput ->
        if (focusedInput == "true") {
            requestFocus()
            postDelayed({
                val imm = context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                    as? android.view.inputmethod.InputMethodManager
                imm?.showSoftInput(this, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
            }, 80L)
        }
    }
}

/**
 * Overlay du curseur virtuel rendu comme une vraie View Android (`onDraw` custom) plutôt
 * qu'en Compose pur.
 *
 * Fix (16 août 2026) : une `WebView` intégrée via `AndroidView` dessine TOUJOURS par-dessus
 * tout contenu Compose composé après elle dans le même arbre — limitation connue de
 * l'interop Compose/View (une View interop "passe devant" le rendu Compose, quel que soit
 * l'ordre dans le code source). L'ancien curseur (`VirtualCursor`, Composable pur) était
 * donc bien positionné et bien recalculé à chaque appui D-pad, mais invisible à l'écran :
 * rendu "sous" la WebView. Deux Views Android natives respectent en revanche leur ordre
 * d'ajout entre elles — cet overlay est donc lui aussi une View native, ajoutée après la
 * WebView dans l'arbre, ce qui la fait apparaître par-dessus comme attendu.
 *
 * Style : curseur noir plein (§ demande utilisateur — souris bien visible façon navigateur
 * classique) cerné d'un anneau blanc pour rester lisible même sur un fond de page sombre.
 */
private class VirtualCursorView(context: android.content.Context) : View(context) {

    /** Position en pixels dans le repère de cette View ; `null` = rien à dessiner. */
    var cursorOffsetPx: Offset? = null
        set(value) {
            field = value
            invalidate()
        }

    private val outerRingPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        style = android.graphics.Paint.Style.FILL
    }
    private val bodyPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK
        style = android.graphics.Paint.Style.FILL
    }
    private val haloPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(70, 0, 0, 0)
        style = android.graphics.Paint.Style.FILL
    }

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val offset = cursorOffsetPx ?: return
        val outerRadius = resources.displayMetrics.density * (CURSOR_SIZE_DP / 2f)
        // Halo léger pour détacher le curseur d'un fond de page très clair.
        canvas.drawCircle(offset.x, offset.y, outerRadius + resources.displayMetrics.density * 3f, haloPaint)
        // Anneau blanc, bien visible même sur fond sombre.
        canvas.drawCircle(offset.x, offset.y, outerRadius, outerRingPaint)
        // Cœur noir plein — le curseur "souris" demandé.
        canvas.drawCircle(offset.x, offset.y, outerRadius * 0.72f, bodyPaint)
    }
}

@Composable
private fun VirtualCursorOverlay(offsetPx: Offset, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            VirtualCursorView(ctx).apply {
                // Ne doit jamais intercepter de touch/focus : elle n'est là que pour
                // dessiner par-dessus la WebView, tout le reste (D-pad, clics simulés)
                // reste géré par le gestionnaire de curseur de `FilmsSeriesScreen`.
                isClickable = false
                isFocusable = false
                cursorOffsetPx = offsetPx
            }
        },
        update = { view -> view.cursorOffsetPx = offsetPx }
    )
}


/**
 * Domaines d'infrastructure légitimes par stream (CDN, API, assets, domaines frères).
 * Le domaine principal du lien (et tous ses sous-domaines) est toujours autorisé à part.
 * Seules les navigations hors de cet ensemble sont traitées comme redirections à bloquer.
 */
private val STREAM_INFRASTRUCTURE_HOSTS: Map<Int, Set<String>> = mapOf(
    1 to setOf(
        "purstream.tv",
        "purstream.wiki",
        "themoviedb.org",
        "api.themoviedb.org",
        "image.tmdb.org",
    ),
    2 to setOf(
        "french-manga.net",
        "cdnjs.cloudflare.com",
        "image.tmdb.org",
        "themoviedb.org",
    ),
    3 to setOf(
        "aoneroom.com",
        "cloudfront.net",
        "themoviebox.app",
        "moviebox.co",
        "moviebox.ph",
        "movieboxonline.net",
        "trasre.com",
        "downloadmoviebox.com",
        "downloader2.com",
    ),
    4 to setOf(
        "youtube.com",
        "m.youtube.com",
        "youtube-nocookie.com",
        "googlevideo.com",
        "ytimg.com",
        "youtubei.googleapis.com",
    ),
    5 to setOf(
        "xnxx.com",
    ),
)

/**
 * Politique de verrouillage de domaine par stream (fix 26/08/2026, remplace la formule
 * `!(useTvFlix && streamIndex == 1)` introduite avec TvFlix qui forçait le mode strict
 * partout sauf TV Stream 1, mobile inclus, sans jamais lire [GeneralSettings.strictDomainLock]).
 *
 * - Stream 1 : toujours ouvert (mobile + TV) — le site n'accède à sa vraie page qu'via une
 *   redirection/`window.open()` hors de son domaine de base.
 * - Stream 2 (27/08/2026) : toujours confiné à son propre domaine (french-stream.one) +
 *   sous-domaines + infra nécessaire ([STREAM_INFRASTRUCTURE_HOSTS]), quelle que soit la
 *   plateforme (mobile + TV) et indépendamment du réglage utilisateur — pour renforcer la
 *   protection contre les redirections publicitaires en tout genre du site, le mode ouvert
 *   (liste noire pub uniquement) étant jugé insuffisant. Stream 3 avait initialement reçu
 *   la même politique fixe pour la même raison, mais elle a été annulée le jour même (voir
 *   bullet Stream 3 ci-dessous) pour retrouver un affichage qui fonctionnait.
 * - Stream 3 (restauré 27/08/2026, § README-stream3-restauration.md) : suit de nouveau le
 *   réglage utilisateur comme avant l'introduction de la politique fixe — ouvert par défaut,
 *   protection soft uniquement ([KNOWN_AD_REDIRECT_HOSTS]). La politique fixe (whitelist
 *   exclusive) avait été ajoutée pour couper les CTA "Download App"/liens publicitaires
 *   externes du site, mais correspond à une version du projet où Stream 3 ne s'affichait
 *   déjà plus correctement — annulée en priorité pour retrouver l'affichage qui fonctionnait.
 * - Autre stream restant (Stream 4, Stream 5) : suit lui aussi le réglage utilisateur (Réglages →
 *   icône DP-FLIX, ouvert par défaut).
 */
private fun resolveStrictDomainLock(streamIndex: Int, userSetting: Boolean): Boolean {
    return when (streamIndex) {
        1 -> false
        2 -> true
        else -> userSetting
    }
}

private fun resolveAllowedHosts(streamIndex: Int, userExtras: Set<String>): Set<String> {
    return buildSet {
        addAll(userExtras)
        addAll(STREAM_INFRASTRUCTURE_HOSTS[streamIndex].orEmpty())
    }
}

/**
 * Sites tiers dont l'affichage en pleine page (via iframe publicitaire) doit être coupé
 * dans un stream donné, quelle que soit la façon dont ils apparaissent — contrairement à
 * [KNOWN_AD_REDIRECT_HOSTS], qui ne couvre que la navigation principale (§ doc de
 * [isKnownAdRedirectHost]) et laisse donc passer un site injecté en iframe plein cadre
 * par une régie pub du site visité. Seul le CHARGEMENT DE PAGE de l'iframe est coupé
 * (voir garde `isDocumentSubframeRequest` dans [LockedWebView.shouldInterceptRequest]) —
 * pas les scripts/XHR/pixels annexes vers ces mêmes hôtes, dont la page principale
 * pourrait dépendre pour fonctionner (fix du 27/08/2026 : bloquer aveuglément toute
 * requête vers ces hôtes rendait le Stream 3 entièrement noir).
 *
 * Ajouté le 27/08/2026 : le site de paris sportifs MELBET (puis 1xbet) s'affichait en
 * plein écran sur le Stream 3 mobile (capture fournie par l'utilisateur), très probablement
 * via une iframe publicitaire du site plutôt qu'une vraie navigation — d'où son passage
 * inaperçu par la whitelist stricte du Stream 3 ([STREAM_INFRASTRUCTURE_HOSTS], qui ne
 * régit que la navigation principale). Bloqué au niveau des requêtes réseau elles-mêmes
 * (voir usage dans [LockedWebView.shouldInterceptRequest]), donc y compris en iframe — sans
 * toucher à la whitelist ni à aucun autre stream/hôte. Retiré du Stream 3 le jour même
 * (toujours écran noir malgré le passage à un blocage iframe-only, cause encore incertaine —
 * l'écran noir a la priorité sur le blocage MELBET/1xbet tant que la vraie cause n'est pas
 * identifiée). Même constat et même correctif conservé pour le Stream 2 (French Stream) :
 * chaîne de redirection publicitaire classique (melbet.ci, 1xlite-83442.com, etc., fournie
 * par l'utilisateur) traversant l'iframe du lecteur choisi (DOOD/VOE/VIDZY/FILMOON).
 */
private val STREAM_HARD_BLOCKED_HOSTS: Map<Int, Set<String>> = mapOf(
    2 to setOf(
        "xsportshd.com",
        "melbet.ci",
        "wuytg.com",
        "1xlite-83442.com",
        "moonlighthathel.org",
        "golzu.com",
        "ragiscafila.rest",
        "tracylocalschool.com",
    ),
)

private fun resolveHardBlockedHosts(streamIndex: Int): Set<String> {
    return STREAM_HARD_BLOCKED_HOSTS[streamIndex].orEmpty()
}

/**
 * Hôtes typiques de redirections publicitaires / pop-under / trackers agressifs — inspiré
 * du principe de TV Bro (`AdblockModel`/EasyList) : plutôt qu'une whitelist qui bloquerait
 * aussi les sous-domaines légitimes du site (CDN, embed, mirroirs), on bloque au contraire
 * une liste noire connue de régies pub/redirecteurs, **peu importe le domaine visité** —
 * ça laisse un stream ouvrir tous ses sous-domaines utiles sans les whitelister un par un,
 * tout en coupant les redirections vers ces régies. Contrairement à TV Bro (EasyList complet
 * + moteur natif, cf. `com.brave.adblock`), la liste ici reste volontairement courte et
 * ciblée sur la **navigation principale** (redirections plein cadre), pas sur les
 * sous-ressources — à enrichir si un nouveau redirecteur apparaît en test.
 */
private val KNOWN_AD_REDIRECT_HOSTS: Set<String> = setOf(
    "propellerads.com", "propellerapi.com", "onclickmax.com", "onclckmx.com",
    "adsterra.com", "adsterratech.com", "exoclick.com", "exosrv.com",
    "juicyads.com", "juicyads.net", "trafficjunky.net", "trafficjunky.com",
    "clickadu.com", "hilltopads.net", "hilltopads.com", "adnium.com",
    "bidvertiser.com", "popads.net", "popcash.net", "propush.me",
    "yllix.com", "adcash.com", "a-ads.com", "mgid.com", "adskeeper.co.uk",
    "smartadserver.com", "revcontent.com", "outbrain.com", "taboola.com",
    "popunder.net", "popunderjs.com", "adexchangeprediction.com",
    "adk2.com", "adk2x.com", "cpmstar.com", "cpalead.com",
    "galaksion.com", "richads.com", "clickaine.com", "adcorto.co",
    "shrinkme.io", "linkvertise.com", "poplink.io", "trafficstars.com",
    "trafficfactory.biz", "syndication.exdynsrv.com", "exdynsrv.com",
)

/**
 * Vérification volontairement placée AVANT les exemptions de mode ouvert / whitelist dans
 * [isAllowedMainFrameUri] : une régie connue reste bloquée même si elle correspond par
 * ailleurs à un hôte autorisé (ne devrait jamais arriver en pratique, mais l'ordre de
 * vérification est ce qui rend la liste noire réellement prioritaire).
 */
private fun isKnownAdRedirectHost(host: String): Boolean {
    return KNOWN_AD_REDIRECT_HOSTS.any { blocked -> host == blocked || host.endsWith(".$blocked") }
}

/**
 * Hôtes typiques de redirections publicitaires / pop-under / trackers agressifs.
 * Protection « anti-redirection » du mode ouvert (§ [LockedWebView], `strictDomainLock =
 * false`) : on bloque ces destinations **sur la navigation principale**, sans empêcher les
 * redirects HTTP légitimes (Cloudflare, OAuth, CDN, lecture vidéo) ni les sous-ressources.
 *
 * Liste volontairement courte et ciblée — à enrichir si besoin.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LockedWebView(
    url: String,
    sniffer: StreamSniffer,
    extraAllowedHosts: Set<String> = emptySet(),
    /**
     * Verrouillage strict de domaine (whitelist exclusive) : `true` par défaut.
     * Toute navigation principale hors du domaine principal et [extraAllowedHosts] est bloquée,
     * y compris les redirections et les schémas externes. Les sous-ressources restent autorisées
     * pour ne pas casser les CDN/segments nécessaires au lecteur.
     * Voir doc de classe de `FilmsSeriesScreen` (§ Verrouillage du navigateur).
     */
    strictDomainLock: Boolean = true,
    /**
     * Blocage dur, indépendant de [strictDomainLock] (§ doc de [STREAM_HARD_BLOCKED_HOSTS]) :
     * ces hôtes ne chargent JAMAIS, ni en navigation principale ni en sous-ressource/iframe —
     * à la différence du reste de cette fonction, qui laisse toujours passer les sous-ressources
     * pour ne pas casser CDN/segments vidéo.
     */
    hardBlockedHosts: Set<String> = emptySet(),
    savedState: Bundle? = null,
    onSaveState: (Bundle) -> Unit = {},
    onWebViewCreated: (WebView) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onPageTitleChanged: (String?) -> Unit = {},
    onRendererGone: () -> Unit = {},
    /** TV : UA bureau pour limiter les pages vides / versions mobiles cassées. */
    preferDesktopUserAgent: Boolean = false,
    /** Utilise TvFlixWebView + CursorLayout comme moteur TV. */
    useTvFlix: Boolean = false,
    /**
     * TV + curseur : force [View.LAYER_TYPE_SOFTWARE] pour éviter le punch-through
     * SurfaceView de la WebView qui cache le curseur (Z-order SurfaceFlinger).
     */
    forceSoftwareLayer: Boolean = false,
    modifier: Modifier = Modifier
) {
    val allowedHostNormalized = remember(url) {
        Uri.parse(url).host?.lowercase()?.removePrefix("www.")
    }
    // Infra stream + exceptions Réglages, normalisés (minuscule, sans www.).
    val normalizedExtraHosts = remember(extraAllowedHosts) {
        extraAllowedHosts
            .map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotEmpty() }
            .toSet()
    }
    val snifferState = rememberUpdatedState(sniffer)
    val onFullscreenState = rememberUpdatedState(onFullscreenChanged)
    val onPageTitleState = rememberUpdatedState(onPageTitleChanged)
    val onRendererGoneState = rememberUpdatedState(onRendererGone)
    val normalizedHardBlockedHosts = remember(hardBlockedHosts) {
        hardBlockedHosts
            .map { it.trim().lowercase().removePrefix("www.") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    fun isHardBlockedHost(host: String?): Boolean {
        if (host == null) return false
        return normalizedHardBlockedHosts.any { blocked -> host == blocked || host.endsWith(".$blocked") }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            // Référence tardive : le WebChromeClient est installé avant la création du
            // CursorLayout, mais onShowCustomView() n'arrive qu'après le rendu de la page.
            // Elle permet donc de faire passer le plein écran vidéo DANS CursorLayout.
            var tvCursorLayout: CursorLayout? = null

            // Hissée ici (avant webView/webViewClient/webChromeClient) pour être partagée
            // entre la navigation principale (WebViewClient) et les popups/window.open()
            // (WebChromeClient.onCreateWindow, voir plus bas) : les deux doivent appliquer
            // exactement la même politique de domaine + liste noire pub.
            fun isAllowedMainFrameUri(uri: Uri): Boolean {
                val scheme = uri.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return false
                val host = uri.host?.lowercase()?.removePrefix("www.")
                // Blocage dur (§ doc de [STREAM_HARD_BLOCKED_HOSTS]) vérifié en tout
                // premier : prioritaire même sur la liste noire pub ci-dessous.
                if (isHardBlockedHost(host)) return false
                // Liste noire vérifiée en premier, avant les deux exemptions
                // ci-dessous (mode ouvert ET whitelist stricte) : une régie
                // pub/redirecteur connue reste bloquée dans tous les cas — voir
                // doc de [isKnownAdRedirectHost].
                if (host != null && isKnownAdRedirectHost(host)) return false
                // Le paramètre reste disponible pour des intégrations hôtes qui
                // choisissent explicitement le mode ouvert. DP-FLIX Films/Séries
                // force actuellement le mode strict.
                if (!strictDomainLock) return true
                if (host == null) return false
                val isMainDomain = allowedHostNormalized != null &&
                    (host == allowedHostNormalized || host.endsWith(".$allowedHostNormalized"))
                val isExtraAllowed = normalizedExtraHosts.any { extra ->
                    host == extra || host.endsWith(".$extra")
                }
                return isMainDomain || isExtraAllowed
            }

            val webView = (if (useTvFlix) TvFlixWebView(ctx) else WebView(ctx)).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.Black.toArgb())

                // Le WebView TV reste matériel. Le SOFTWARE layer généralisé sur les
                // streams TV pénalisait le rendu des pages et de la vidéo ; CursorLayout
                // dessine déjà le curseur après son enfant dans dispatchDraw().
                if (forceSoftwareLayer) {
                    setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                }

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Fix (16 août 2026) : cookies jamais activés jusqu'ici. La plupart des
                // sites Films & Séries (stream 1 en particulier) passent par une
                // vérification anti-bot / session basée sur cookie avant d'afficher quoi
                // que ce soit — sans ça la page reste bloquée indéfiniment sur un écran
                // noir, le HTML est chargé mais l'échange de session ne se termine jamais.
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
                cookieManager.setAcceptThirdPartyCookies(this, true)
                // Fix (16 août 2026) : Android bloque par défaut les ressources http://
                // chargées depuis une page https:// (MIXED_CONTENT_NEVER_ALLOW). Plusieurs
                // CDN d'images de stream 3 sont encore servis en http:// → les vignettes de
                // programme restaient invisibles (cases vides) sans jamais d'erreur visible.
                settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                if (preferDesktopUserAgent) {
                    // Stream 3 (themoviebox) : UA bureau + viewport large pour que la
                    // mise en page desktop reste exploitable avec le curseur D-pad TV.
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                }
                // Nécessaire pour que les sites Films & Séries démarrent la lecture sans
                // exiger un second tap dédié côté page (le tap initial de l'utilisateur
                // reste requis, ce réglage évite seulement un blocage supplémentaire du
                // navigateur système sur certains lecteurs embarqués).
                settings.mediaPlaybackRequiresUserGesture = false
                // Fix 26/08/2026 : true (au lieu de false) — indispensable pour que
                // WebChromeClient.onCreateWindow soit seulement appelé quand un lien fait
                // un window.open()/target="_blank" (cas de stream 1 : la page d'accueil
                // ouvre le vrai site dans une "nouvelle fenêtre"). À false, Chromium
                // ignorait ces clics en silence, avant même d'atteindre onCreateWindow —
                // qui, lui, ne crée jamais de vraie 2e fenêtre : il charge l'URL demandée
                // dans cette même WebView (voir onCreateWindow plus bas). L'app reste donc
                // mono-fenêtre ; javaScriptCanOpenWindowsAutomatically=false ci-dessous
                // continue de couper les popups automatiques (sans geste utilisateur).
                settings.setSupportMultipleWindows(true)
                settings.javaScriptCanOpenWindowsAutomatically = false

                // Pas de menu contextuel long-press (copier le lien, ouvrir dans un nouvel
                // onglet) : seul geste autorisé sur cette WebView, le tap simple.
                setOnLongClickListener { true }
                // Les champs HTML doivent pouvoir prendre le focus afin que le clavier
                // système s'ouvre sur TV après un clic simulé par le curseur.
                isFocusable = true
                isFocusableInTouchMode = true

                webViewClient = object : WebViewClient() {
                    /**
                     * Politique de navigation Films/Séries : aucune navigation principale
                     * externe n'est autorisée. Les redirections HTTP 3xx, location.href,
                     * window.open(), intent://, tel:, market:, etc. ne peuvent donc pas
                     * sortir de la whitelist. Les sous-ressources (CDN, segments vidéo,
                     * images, JS) ne passent pas par cette méthode et restent disponibles
                     * au lecteur ; leur domaine peut être ajouté à extraAllowedHosts.
                     * Voir [isAllowedMainFrameUri] ci-dessus (hissée hors de cette classe
                     * pour être réutilisée par WebChromeClient.onCreateWindow).
                     */
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Ne bloque jamais les sous-ressources : cette méthode concerne
                        // uniquement les navigations. Les segments vidéo/CDN restent donc
                        // consommables par le lecteur même lorsque leur domaine est externe.
                        if (!request.isForMainFrame) return false
                        return !isAllowedMainFrameUri(request.url)
                    }

                    @Suppress("DEPRECATION")
                    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                        // Compatibilité API 23 : l'ancienne surcharge est indispensable sur
                        // certaines box Android où WebView n'appelle pas la version moderne.
                        return !isAllowedMainFrameUri(Uri.parse(url))
                    }

                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        if (useTvFlix) (view as? TvFlixWebView)?.injectDesktopSpoof()
                        // Nouvelle page dans l'historique du site → les flux capturés pour
                        // l'ancienne page n'ont plus cours (module téléchargement).
                        snifferState.value.resetForNewPage(pageUrl)
                        DiagnosticSystemMonitor.record(
                            "Films & Séries / WebView",
                            "Ouverture d'une page",
                            DiagnosticSystemMonitor.Status.SUCCESS,
                            "Page démarrée : ${pageUrl?.let { Uri.parse(it).host } ?: "hôte inconnu"}"
                        )
                        // Évite de renvoyer mouseout vers un élément de la page précédente.
                        view?.evaluateJavascript(
                            "window.__dpflixLastHoverEl = null;",
                            null
                        )
                    }

                    /**
                     * Fix (12 août 2026) : sans cette surcharge, Android considère un crash
                     * du processus de rendu WebView (renderer tué par le système sous
                     * pression mémoire — plus probable avec un téléchargement actif en
                     * tâche de fond en parallèle) comme fatal pour TOUT le processus de
                     * l'app, qui s'arrête net (comportement par défaut d'Android depuis
                     * l'API 26 quand `onRenderProcessGone` n'est pas implémenté). En la
                     * fournissant et en retournant `true` ("géré"), seul cet écran est
                     * abandonné — l'app elle-même survit et peut revenir proprement à
                     * l'accueil (voir [onRendererGoneState] côté appelant).
                     */
                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        if (useTvFlix) (view as? TvFlixWebView)?.injectDesktopSpoof()
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?
                    ) {
                        super.onReceivedHttpError(view, request, errorResponse)
                        if (request != null && errorResponse != null) {
                            // CookieManager n'est consulté que pendant une vraie session
                            // de diagnostic : aucune lecture de cookie dans le chemin normal.
                            val cookieHeader = if (DiagnosticSystemMonitor.isRunning()) {
                                android.webkit.CookieManager.getInstance().getCookie(request.url.toString())
                            } else {
                                null
                            }
                            DiagnosticSystemMonitor.recordHttp(
                                area = "Films & Séries / WebView",
                                action = "Réponse HTTP WebView",
                                code = errorResponse.statusCode,
                                url = request.url.toString(),
                                userAgentPresent = view?.settings?.userAgentString?.isNotBlank() == true,
                                cookiesPresent = !cookieHeader.isNullOrBlank(),
                                contentType = errorResponse.mimeType
                            )
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?
                    ) {
                        super.onReceivedError(view, request, error)
                        if (request != null && error != null) {
                            DiagnosticSystemMonitor.record(
                                "Films & Séries / WebView",
                                "Erreur de chargement",
                                DiagnosticSystemMonitor.Status.ERROR,
                                "${error.errorCode} · ${error.description}",
                                "La ressource/page n'a pas pu être chargée correctement."
                            )
                        }
                    }

                    override fun onReceivedSslError(
                        view: WebView?,
                        handler: android.webkit.SslErrorHandler?,
                        error: android.net.http.SslError?
                    ) {
                        DiagnosticSystemMonitor.record(
                            "Films & Séries / WebView",
                            "Erreur TLS/SSL",
                            DiagnosticSystemMonitor.Status.ERROR,
                            error?.toString() ?: "Erreur SSL",
                            "Certificat TLS/SSL refusé par Android."
                        )
                        super.onReceivedSslError(view, handler, error)
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: android.webkit.RenderProcessGoneDetail?
                    ): Boolean {
                        // Ne pas appeler view.destroy() ici : `onRelease` de l'AndroidView
                        // (plus bas) s'en charge déjà une fois la navigation vers l'accueil
                        // effective — appeler destroy() deux fois plante.
                        onRendererGoneState.value()
                        return true
                    }

                    /**
                     * Module téléchargement — observation pure des requêtes réseau de la
                     * WebView pour y détecter des flux vidéo (.mp4/.m3u8/...), sans jamais
                     * bloquer ni modifier le chargement réel de la page (retourne toujours
                     * `null`, la WebView charge la ressource normalement).
                     */
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest
                    ): android.webkit.WebResourceResponse? {
                        // Blocage dur (§ doc de [STREAM_HARD_BLOCKED_HOSTS]) : contrairement
                        // au reste de cette méthode (observation pure, ne bloque jamais rien),
                        // ces hôtes précis sont coupés ici même hors navigation principale —
                        // c'est le seul moyen de les bloquer quand ils n'apparaissent jamais
                        // comme une navigation principale (donc invisibles à
                        // isAllowedMainFrameUri/shouldOverrideUrlLoading).
                        //
                        // Fix (27/08/2026) : bloquer TOUTES les requêtes vers ces hôtes (y
                        // compris scripts/XHR/pixels) rendait le Stream 3 entièrement noir —
                        // le site attend visiblement une réponse de ces domaines (mécanisme
                        // anti-adblock classique d'un site financé par la pub) avant de retirer
                        // son écran de chargement, et ne recevait plus jamais cette réponse.
                        // On ne coupe donc plus que le CHARGEMENT DE PAGE de l'iframe pub
                        // elle-même (frame secondaire + en-tête Accept de type document HTML,
                        // signature d'une navigation de frame et non d'un script/pixel/XHR) :
                        // ça empêche l'iframe de s'afficher en plein cadre sans jamais couper
                        // les requêtes annexes dont la page principale pourrait dépendre.
                        val isDocumentSubframeRequest = !request.isForMainFrame &&
                            request.requestHeaders["Accept"]?.contains("text/html") == true
                        if (isDocumentSubframeRequest &&
                            isHardBlockedHost(request.url.host?.lowercase()?.removePrefix("www."))
                        ) {
                            return android.webkit.WebResourceResponse(
                                "text/plain",
                                "utf-8",
                                java.io.ByteArrayInputStream(ByteArray(0))
                            )
                        }
                        try {
                            snifferState.value.onRequest(request)
                            // Aucun accès CookieManager sur le chemin réseau normal.
                            // Les cookies de diagnostic ne sont lus que si la session est active.
                            if (DiagnosticSystemMonitor.isRunning()) {
                                val cookieHeader = android.webkit.CookieManager.getInstance()
                                    .getCookie(request.url.toString())
                                DiagnosticSystemMonitor.recordWebViewRequest(
                                    area = "Films & Séries / WebView",
                                    request = request,
                                    userAgentPresent = view?.settings?.userAgentString?.isNotBlank() == true,
                                    cookieHeaderPresent = !cookieHeader.isNullOrBlank()
                                )
                            }
                        } catch (_: Exception) {
                            // Best-effort : un sniffer qui plante ne doit jamais casser la page.
                        }
                        return null
                    }
                }

                // Plein écran HTML5 (lecteur vidéo des sites films) → notifier l'UI pour
                // masquer la flèche de téléchargement (règle non négociable du cahier des
                // charges : jamais de flèche visible en plein écran).
                webChromeClient = object : WebChromeClient() {
                    private var customView: View? = null
                    private var customViewCallback: CustomViewCallback? = null
                    private var originalSystemUiVisibility: Int = 0
                    // Mémorise le chemin réellement emprunté à l'ouverture (CursorLayout TV
                    // ou decor de l'Activity), pour que la fermeture nettoie exactement le
                    // même endroit — plus de déduction a posteriori depuis useTvFlix/
                    // isFullscreenViewShown(), qui pouvait diverger de ce qui a été fait à
                    // l'ouverture et laisser un removeView() sans effet.
                    private var customViewInCursorLayout = false

                    override fun onReceivedTitle(view: WebView?, title: String?) {
                        // Propage le titre de la page vers l'UI Compose — utilisé pour
                        // nommer le téléchargement à l'enqueue.
                        onPageTitleState.value(title?.takeIf { it.isNotBlank() })
                    }

                    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                        if (customView != null) {
                            callback?.onCustomViewHidden()
                            return
                        }
                        val activity = context as? android.app.Activity
                        val decor = activity?.window?.decorView as? FrameLayout
                        if (view == null || decor == null) {
                            callback?.onCustomViewHidden()
                            return
                        }
                        originalSystemUiVisibility = decor.systemUiVisibility
                        customView = view
                        customViewCallback = callback

                        // IMPORTANT TV : le lecteur HTML5 doit rester dans le même
                        // CursorLayout que la WebView. Le curseur est ensuite dessiné
                        // par CursorLayout.dispatchDraw(), donc au-dessus du lecteur.
                        // Cela rend le plein écran et le mini-player navigables au D-pad.
                        val cursorLayout = tvCursorLayout
                        customViewInCursorLayout = useTvFlix && cursorLayout != null
                        if (customViewInCursorLayout) {
                            cursorLayout?.showFullscreenView(view)
                        } else {
                            decor.addView(
                                view,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT
                                )
                            )
                        }

                        decor.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            )
                        // La vidéo plein écran ne doit jamais être coupée par la mise en
                        // veille de l'écran (TV/box restée immobile devant un film).
                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        onFullscreenState.value(true)
                    }

                    override fun onHideCustomView() {
                        val activity = context as? android.app.Activity
                        val decor = activity?.window?.decorView as? FrameLayout
                        if (customViewInCursorLayout) {
                            tvCursorLayout?.hideFullscreenView(notify = false)
                        } else {
                            customView?.let { decor?.removeView(it) }
                        }
                        decor?.systemUiVisibility = originalSystemUiVisibility
                        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        customViewInCursorLayout = false
                        customViewCallback?.onCustomViewHidden()
                        customView = null
                        customViewCallback = null
                        onFullscreenState.value(false)
                    }

                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: android.os.Message?
                    ): Boolean {
                        // Fix 26/08/2026 : stream 1 (et potentiellement d'autres) ouvre son
                        // vrai site via un clic sur un lien qui fait un window.open()/
                        // target="_blank" — pas une redirection plein cadre. Avant, on
                        // bloquait ça sans condition ("return false"), donc ce clic ne
                        // menait jamais nulle part. On ne crée pas de vraie 2e fenêtre
                        // (l'appli reste mono-fenêtre volontairement, cf. doc de classe) :
                        // à la place, on capture l'URL demandée via une WebView jetable
                        // (technique standard Android pour intercepter window.open() sans
                        // l'ouvrir), puis on la charge DANS cette même WebView si elle
                        // passe la même politique que la navigation normale (liste noire
                        // pub + whitelist si mode strict). Les popups automatiques (sans
                        // geste utilisateur, typiques des pop-under publicitaires) restent
                        // bloquées : c'est justement le cas qu'on veut continuer à couper.
                        if (view == null || resultMsg == null || !isUserGesture) return false
                        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
                        val throwawayWebView = WebView(view.context).apply {
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    dv: WebView,
                                    request: WebResourceRequest
                                ): Boolean {
                                    if (isAllowedMainFrameUri(request.url)) {
                                        view.loadUrl(request.url.toString())
                                    }
                                    return true
                                }

                                @Suppress("DEPRECATION")
                                override fun shouldOverrideUrlLoading(dv: WebView, url: String): Boolean {
                                    val uri = Uri.parse(url)
                                    if (isAllowedMainFrameUri(uri)) {
                                        view.loadUrl(url)
                                    }
                                    return true
                                }
                            }
                        }
                        transport.webView = throwawayWebView
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                if (savedState != null) {
                    // Restaure l'historique de navigation (page + pile retour) au lieu de
                    // repartir de l'accueil du site.
                    restoreState(savedState)
                } else {
                    snifferState.value.resetForNewPage(url)
                    loadUrl(url)
                }
            }

            if (useTvFlix) {
                val cursor = CursorLayout(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    cursorEnabled = true
                    // Fix 26/08/2026 : FOCUS_BLOCK_DESCENDANTS empêchait la WebView (et donc
                    // tout champ HTML interne — barre d'adresse, recherche, etc.) de recevoir
                    // le focus, donc le clavier système ne s'affichait jamais au clic du
                    // curseur sur ces champs, sur les 5 streams. Le D-pad continue d'être
                    // intercepté en priorité par CursorLayout.dispatchKeyEvent() (indépendant
                    // de descendantFocusability, qui ne régit que le focus, pas le routage des
                    // touches) — voir CursorLayout.init pour le même choix
                    // (FOCUS_AFTER_DESCENDANTS), ici réaffirmé explicitement pour éviter toute
                    // régression si ce bloc `.apply` est retouché de nouveau.
                    descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
                }
                cursor.addView(webView)
                tvCursorLayout = cursor
                onWebViewCreated(webView)
                cursor
            } else {
                onWebViewCreated(webView)
                webView
            }
        },
        onRelease = { container ->
            val webView = if (container is CursorLayout) {
                container.getChildAt(0) as? WebView
            } else {
                container as? WebView
            }
            webView?.let {
                val bundle = Bundle()
                it.saveState(bundle)
                onSaveState(bundle)
                it.stopLoading()
                it.destroy()
            }
        }
    )
}
