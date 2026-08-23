package com.dpflix.android.filmsseries

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.filmsseries.stream.DetectedStream
import com.dpflix.android.filmsseries.stream.StreamSniffer
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
 * - `setSupportMultipleWindows(false)` + [WebChromeClient.onCreateWindow] retourne
 *   toujours `false` : `window.open()`/`target="_blank"` n'ouvrent rien. Pas de barre
 *   d'adresse, de navigation précédente/suivante ni de menu long-press (désactivé
 *   explicitement) : aucun chrome de navigateur visible.
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
    val dpFlixMenuFocusRequester = remember { FocusRequester() }
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

    LaunchedEffect(showDpFlixMenu) {
        if (showDpFlixMenu && showVirtualCursor) {
            // Le menu DP-Flix devient le point de focus D-pad pendant son ouverture.
            // Les flèches peuvent ensuite circuler entre les actions, tandis que
            // Retour referme simplement le menu.
            dpFlixMenuFocusRequester.requestFocus()
        }
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
            showDpFlixMenu -> showDpFlixMenu = false
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

    Box(modifier = boxModifier) {
        if (generalSettings == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else {
            // `key(url)` : si l'utilisateur modifie le lien dans Réglages pendant que cet
            // écran est déjà ouvert (retour arrière, changement, retour ici), on force une
            // toute nouvelle WebView plutôt que de tenter un `loadUrl` sur l'existante —
            // plus simple et plus sûr que de garder une référence mutable à la WebView.
            key(url, generalSettings?.strictDomainLock == true) {
                LockedWebView(
                    url = url,
                    sniffer = sniffer,
                    preferDesktopUserAgent = streamIndex == 3,
                    forceSoftwareLayer = showVirtualCursor,
                    strictDomainLock = generalSettings?.strictDomainLock == true,
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
                        if (showVirtualCursor) {
                            // Empêche la page de capter le focus D-pad : toutes les
                            // pressions doivent remonter au gestionnaire de curseur
                            // ci-dessus, jamais au contenu de la WebView elle-même.
                            webView.isFocusable = false
                            webView.isFocusableInTouchMode = false
                            // Double filet Z-order (au cas où le factory n'aurait pas
                            // encore appliqué forceSoftwareLayer).
                            webView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                        }
                    },
                    onFullscreenChanged = { fullscreen ->
                        isPageFullscreen = fullscreen
                        // Règle non négociable : jamais de flèche/dialogue en plein écran.
                        if (fullscreen) showStreamsDialog = false
                    },
                    onPageTitleChanged = { title -> pageTitle = title },
                    onRendererGone = {
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
        if (showVirtualCursor && offset != null) {
            // Fix (16 août 2026) : voir doc de [VirtualCursorView] plus bas — une View
            // interop (la WebView) dessine toujours par-dessus le contenu Compose composé
            // après elle, curseur donc invisible tant qu'il restait un Composable pur.
            VirtualCursorOverlay(offsetPx = offset)
        }

        // Barre DP-Flix compacte : une seule icône discrète regroupe les actions
        // Réglages / Téléchargements / Détection. Le badge rouge reste visible même
        // lorsque le menu est fermé afin de signaler immédiatement les flux captés.
        //
        // Fix (21 août 2026) : positionnée en haut-CENTRE, l'icône pouvait recouvrir un
        // logo de site lui-même centré (aucun moyen fiable de connaître la mise en page
        // exacte de chaque site sans injecter du JS fragile pour mesurer son DOM). Le
        // coin haut-droit, en léger retrait du bord, est la zone la plus sûre quel que
        // soit le site : les logos sont presque toujours calés à gauche, et ce retrait
        // (12dp) laisse aussi de la marge avec un éventuel ☰ du site tout au bord.
        if (!isPageFullscreen) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 4.dp, end = 12.dp)
            ) {
                CompactDpFlixMenuButton(
                    streamCount = detectedStreams.size,
                    expanded = showDpFlixMenu,
                    focusRequester = dpFlixMenuFocusRequester,
                    onClick = { showDpFlixMenu = !showDpFlixMenu },
                    modifier = Modifier.align(Alignment.TopEnd)
                )

                if (showDpFlixMenu) {
                    DpFlixActionMenu(
                        hasDetectedStreams = detectedStreams.isNotEmpty(),
                        streamCount = detectedStreams.size,
                        hasDownloadsShortcut = onOpenDownloads != null,
                        onSettings = {
                            showDpFlixMenu = false
                            showExceptionDomainsDialog = true
                        },
                        onDownloads = {
                            showDpFlixMenu = false
                            onOpenDownloads?.invoke()
                        },
                        onDetectedStreams = {
                            showDpFlixMenu = false
                            showStreamsDialog = true
                        },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(top = 48.dp)
                    )
                }
            }
        }

        if (showExceptionDomainsDialog) {
            ExceptionDomainsDialog(
                domains = generalSettings?.extraAllowedDomains
                    ?: GeneralSettings.DEFAULT_EXTRA_ALLOWED_DOMAINS,
                strictDomainLock = generalSettings?.strictDomainLock == true,
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

/**
 * Icône DP-Flix compacte : visuellement petite, mais avec une zone de focus/toucher
 * confortable. Le badge rouge reprend le compteur du détecteur sans ouvrir le menu.
 */
@Composable
private fun CompactDpFlixMenuButton(
    streamCount: Int,
    expanded: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var hasFocus by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .size(52.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            // Fond translucide permanent, très discret (pas le "gros cercle noir" visé par
            // le retour) : juste assez pour que l'icône se lise comme un élément DP-Flix
            // par-dessus le site, même sans focus, plutôt que de sembler faire partie de
            // la page en dessous.
            .background(Color.Black.copy(alpha = 0.28f), RoundedCornerShape(12.dp))
            .then(
                if (hasFocus || expanded) {
                    Modifier.border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(12.dp))
                } else Modifier
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Menu,
            contentDescription = if (expanded) "Fermer le menu DP-Flix" else "Ouvrir le menu DP-Flix",
            tint = Color.White,
            modifier = Modifier.size(23.dp)
        )
        if (streamCount > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 2.dp, end = 2.dp)
                    .size(20.dp)
                    .background(Color(0xFFE53935), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = streamCount.coerceAtMost(99).toString(),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** Menu contextuel DP-Flix. Les actions restent petites et lisibles sur TV/mobile. */
@Composable
private fun DpFlixActionMenu(
    hasDetectedStreams: Boolean,
    streamCount: Int,
    hasDownloadsShortcut: Boolean,
    onSettings: () -> Unit,
    onDownloads: () -> Unit,
    onDetectedStreams: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .widthIn(min = 190.dp, max = 260.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.Black.copy(alpha = 0.88f),
        contentColor = Color.White,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(6.dp)) {
            DpFlixMenuAction(
                icon = Icons.Filled.Settings,
                label = "Réglages",
                onClick = onSettings
            )
            if (hasDownloadsShortcut) {
                DpFlixMenuAction(
                    icon = Icons.Filled.Download,
                    label = "Mes téléchargements",
                    onClick = onDownloads
                )
            }
            if (hasDetectedStreams) {
                DpFlixMenuAction(
                    icon = Icons.Filled.Download,
                    label = if (streamCount == 1) "Télécharger" else "Télécharger ($streamCount)",
                    onClick = onDetectedStreams
                )
            }
        }
    }
}

@Composable
private fun DpFlixMenuAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit
) {
    var hasFocus by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .onFocusChanged { hasFocus = it.isFocused }
            .focusable()
            .clickable(onClick = onClick)
            .then(
                if (hasFocus) {
                    Modifier
                        .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(10.dp))
                        .border(2.dp, Color.White, RoundedCornerShape(10.dp))
                } else Modifier
            )
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
        Text(
            text = label,
            color = Color.White,
            modifier = Modifier.padding(start = 12.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
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
                            text = "Verrouillage strict : seuls le site, ses sous-domaines " +
                                "et la liste ci-dessous sont autorisés. Désactivé = navigation " +
                                "ouverte (recommandé) avec filtrage soft des régies pub connues.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = strictDomainLock,
                        onCheckedChange = onStrictDomainLockChange
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
          if (!el) return;
          var opts = {bubbles:true, cancelable:true, clientX:x, clientY:y, view:window};
          el.dispatchEvent(new MouseEvent('mousedown', opts));
          el.dispatchEvent(new MouseEvent('mouseup', opts));
          el.dispatchEvent(new MouseEvent('click', opts));
        })();
        """.trimIndent().format(cssX, cssY),
        null
    )
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
        "googlevideo.com",
        "youtube-nocookie.com",
        "ytimg.com",
        "ggpht.com",
    ),
    5 to setOf(
        "xnxx.com",
        "www.xnxx.com",
    ),
)

private fun resolveAllowedHosts(streamIndex: Int, userExtras: Set<String>): Set<String> {
    return buildSet {
        addAll(userExtras)
        addAll(STREAM_INFRASTRUCTURE_HOSTS[streamIndex].orEmpty())
    }
}

/**
 * Hôtes typiques de redirections publicitaires / pop-under / trackers agressifs.
 * Protection « anti-redirection » du mode ouvert (§ [LockedWebView], `strictDomainLock =
 * false`) : on bloque ces destinations **sur la navigation principale**, sans empêcher les
 * redirects HTTP légitimes (Cloudflare, OAuth, CDN, lecture vidéo) ni les sous-ressources.
 *
 * Liste volontairement courte et ciblée — à enrichir si besoin.
 */
private val KNOWN_AD_REDIRECT_HOSTS: Set<String> = setOf(
    "doubleclick.net",
    "googlesyndication.com",
    "googleadservices.com",
    "advertising.com",
    "adnxs.com",
    "adservice.google.com",
    "pagead2.googlesyndication.com",
    "popads.net",
    "popcash.net",
    "propellerads.com",
    "propellerclick.com",
    "adsterra.com",
    "juicyads.com",
    "exoclick.com",
    "clickadu.com",
    "trafficjunky.com",
    "realsrv.com",
    "tsyndicate.com",
    "ad-maven.com",
    "adcash.com",
    "bidvertiser.com",
    "openx.net",
    "pubmatic.com",
    "rubiconproject.com",
    "taboola.com",
    "outbrain.com",
    "mgid.com",
    "revcontent.com",
)

/**
 * `host` (déjà normalisé — minuscule, sans `www.`) est-il [KNOWN_AD_REDIRECT_HOSTS] ou un
 * sous-domaine de l'un de ces hôtes ?
 *
 * Appelée dans [LockedWebView.shouldOverrideUrlLoading] **avant** les exemptions
 * `request.isRedirect` / `request.hasGesture()` du mode ouvert : un tap-hijack (calque
 * publicitaire invisible qui capte le tap "lecture" de l'utilisateur) est justement une
 * navigation avec `hasGesture() == true`, et une régie pub peut tout autant être atteinte
 * via un redirect HTTP 3xx classique — si ces deux cas étaient vérifiés avant celui-ci, la
 * liste noire ne bloquerait plus jamais rien en pratique.
 */
private fun isKnownAdRedirectHost(host: String): Boolean {
    if (host.isEmpty()) return false
    return KNOWN_AD_REDIRECT_HOSTS.any { base -> host == base || host.endsWith(".$base") }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LockedWebView(
    url: String,
    sniffer: StreamSniffer,
    extraAllowedHosts: Set<String> = emptySet(),
    /**
     * Verrouillage strict de domaine (whitelist exclusive) : `false` par défaut →
     * navigation ouverte + filtrage soft des hôtes pub connus ([KNOWN_AD_REDIRECT_HOSTS]).
     * `true` = seuls le domaine principal, l'infra du stream et [extraAllowedHosts] passent.
     * Voir doc de classe de `FilmsSeriesScreen` (§ Verrouillage du navigateur).
     */
    strictDomainLock: Boolean = false,
    savedState: Bundle? = null,
    onSaveState: (Bundle) -> Unit = {},
    onWebViewCreated: (WebView) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onPageTitleChanged: (String?) -> Unit = {},
    onRendererGone: () -> Unit = {},
    /** TV : UA bureau pour limiter les pages vides / versions mobiles cassées. */
    preferDesktopUserAgent: Boolean = false,
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

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.Black.toArgb())

                // Fix Z-order curseur TV (SurfaceFlinger / SurfaceView interne WebView) :
                // sans ceci le curseur peut rester invisible même s'il est au-dessus dans l'arbre.
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
                settings.setSupportMultipleWindows(false)
                settings.javaScriptCanOpenWindowsAutomatically = false

                // Pas de menu contextuel long-press (copier le lien, ouvrir dans un nouvel
                // onglet) : seul geste autorisé sur cette WebView, le tap simple.
                setOnLongClickListener { true }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        // Schémas non-web (intent:, market:, tel:, etc.) : jamais suivis,
                        // pour ne jamais sortir de l'app vers un handler externe opaque.
                        val scheme = request.url.scheme?.lowercase()
                        if (scheme != "http" && scheme != "https") {
                            return true
                        }
                        val host = request.url.host?.lowercase()?.removePrefix("www.") ?: return true
                        // Domaine du stream + tous ses sous-domaines (ex. api.purstream.store).
                        val isMainDomain = allowedHostNormalized != null &&
                            (host == allowedHostNormalized ||
                                host.endsWith(".$allowedHostNormalized"))
                        // Infra du stream + domaines d'exception Réglages (CDN téléchargement,
                        // API, assets). Sous-domaines inclus.
                        val isExtraAllowed = normalizedExtraHosts.any { extra ->
                            host == extra || host.endsWith(".$extra")
                        }
                        val isAllowed = isMainDomain || isExtraAllowed

                        // --- Mode STRICT (whitelist exclusive) ---
                        if (strictDomainLock) {
                            return !isAllowed
                        }

                        // --- Mode ouvert + protection soft (défaut, style navigateur TV) ---
                        if (isAllowed) {
                            return false
                        }
                        // Reste sur le même hôte (ou un sous/sur-domaine) que la page
                        // actuellement affichée : navigation interne normale du site.
                        val currentHost = view.url?.let { u ->
                            Uri.parse(u).host?.lowercase()?.removePrefix("www.")
                        }
                        if (currentHost != null && (
                                host == currentHost ||
                                    host.endsWith(".$currentHost") ||
                                    currentHost.endsWith(".$host")
                                )
                        ) {
                            return false
                        }
                        // Liste noire pub vérifiée AVANT les exemptions redirect/gesture —
                        // voir doc de [isKnownAdRedirectHost].
                        if (isKnownAdRedirectHost(host)) {
                            return true
                        }
                        if (request.isRedirect) {
                            return false
                        }
                        if (request.hasGesture()) {
                            return false
                        }
                        return false
                    }

                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        // Nouvelle page dans l'historique du site → les flux capturés pour
                        // l'ancienne page n'ont plus cours (module téléchargement).
                        snifferState.value.resetForNewPage(pageUrl)
                        DiagnosticSystemMonitor.record(
                            area = "Films & Séries",
                            action = "Chargement de page",
                            status = DiagnosticSystemMonitor.Status.WARNING,
                            detail = "Navigation WebView démarrée${pageUrl?.let { " · ${it.substringBefore('?').takeLast(120)}" } ?: ""}"
                        )
                        // Évite de renvoyer mouseout vers un élément de la page précédente.
                        view?.evaluateJavascript(
                            "window.__dpflixLastHoverEl = null;",
                            null
                        )
                    }

                    override fun onPageFinished(view: WebView?, pageUrl: String?) {
                        DiagnosticSystemMonitor.record(
                            area = "Films & Séries",
                            action = "Chargement de page terminé",
                            status = DiagnosticSystemMonitor.Status.SUCCESS,
                            detail = "WebView a signalé la fin du chargement"
                        )
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        if (request?.isForMainFrame == true) {
                            DiagnosticSystemMonitor.record(
                                area = "Films & Séries",
                                action = "Erreur WebView",
                                status = DiagnosticSystemMonitor.Status.ERROR,
                                detail = "${error?.errorCode ?: -1} · ${error?.description ?: "erreur inconnue"}",
                                cause = "La page principale n'a pas pu être chargée correctement."
                            )
                        }
                    }

                    override fun onReceivedHttpError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        errorResponse: android.webkit.WebResourceResponse?
                    ) {
                        if (request?.isForMainFrame == true) {
                            DiagnosticSystemMonitor.recordHttp(
                                area = "Films & Séries",
                                action = "Réponse HTTP WebView",
                                code = errorResponse?.statusCode ?: -1,
                                url = request.url.toString(),
                                userAgentPresent = true,
                                cookiesPresent = null,
                                contentType = errorResponse?.mimeType
                            )
                        }
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
                        try {
                            snifferState.value.onRequest(request)
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
                        decor.addView(
                            view,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                        decor.systemUiVisibility = (
                            View.SYSTEM_UI_FLAG_FULLSCREEN
                                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            )
                        onFullscreenState.value(true)
                    }

                    override fun onHideCustomView() {
                        val activity = context as? android.app.Activity
                        val decor = activity?.window?.decorView as? FrameLayout
                        customView?.let { decor?.removeView(it) }
                        decor?.systemUiVisibility = originalSystemUiVisibility
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
                        // Interdit toute popup / nouvel onglet — cohérent avec le
                        // verrouillage du navigateur (voir doc de classe).
                        return false
                    }
                }

                if (savedState != null) {
                    // Restaure l'historique de navigation (page + pile retour) au lieu de
                    // repartir de l'accueil du site — voir le commentaire sur
                    // `webViewStateBundle` dans `FilmsSeriesScreen`. `onPageStarted`
                    // (ci-dessus) se charge de resynchroniser le sniffer avec la VRAIE page
                    // restaurée dès que la navigation démarre ; inutile de le faire ici
                    // avec [url], qui ne correspond qu'à la racine du site.
                    restoreState(savedState)
                } else {
                    snifferState.value.resetForNewPage(url)
                    loadUrl(url)
                }
            }.also(onWebViewCreated)
        },
        onRelease = { webView ->
            val bundle = Bundle()
            webView.saveState(bundle)
            onSaveState(bundle)
            webView.destroy()
        }
    )
}
