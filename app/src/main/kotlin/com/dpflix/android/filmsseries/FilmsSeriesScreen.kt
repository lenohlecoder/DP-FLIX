package com.dpflix.android.filmsseries

import android.annotation.SuppressLint
import android.net.Uri
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.filmsseries.download.WebViewHttpFetcher
import com.dpflix.android.filmsseries.stream.StreamType
import com.dpflix.android.filmsseries.stream.DetectedStream
import com.dpflix.android.filmsseries.stream.StreamSniffer
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.GeneralSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Section "Films et Séries" (remplace l'ancien Guide TV, retiré le 25 juillet 2026 — voir
 * `DpFlixDestination`) : navigateur intégré verrouillé sur une plateforme externe. Deux
 * plateformes indépendantes possibles ("Stream 1"/"Stream 2", French-Stream, 08/08) —
 * [streamIndex] sélectionne laquelle : 1 → [GeneralSettings.filmsSeriesUrl]/
 * [GeneralSettings.DEFAULT_FILMS_SERIES_URL], 2 → [GeneralSettings.filmsSeriesUrl2]/
 * [GeneralSettings.DEFAULT_FILMS_SERIES_URL_2]. Choisi à l'accueil via
 * `FilmsSeriesStreamPickerDialog`, transporté par `DpFlixDestination.FilmsSeries`.
 *
 * Réutilisé côté mobile ET TV — voir `FilmsSeriesScreenTv.kt` pour le petit wrapper qui
 * l'expose sous ce nom côté TV, avec [showVirtualCursor] activé (§ ci-dessous).
 *
 * ## Verrouillage du navigateur
 * - Un seul domaine autorisé : celui de [url] au moment de l'ouverture, plus ses
 *   sous-domaines (`*.host`) — toute navigation vers un autre domaine (redirection
 *   publicitaire, lien tiers) est interceptée par [WebViewClient.shouldOverrideUrlLoading]
 *   et simplement ignorée : la page reste sur son état courant, jamais de nouvel onglet
 *   ni de sortie vers un navigateur externe.
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
    val url = if (streamIndex == 2) {
        generalSettings?.filmsSeriesUrl2 ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL_2
    } else {
        generalSettings?.filmsSeriesUrl ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var awaitingSecondBackPress by remember { mutableStateOf(false) }
    val onNavigateHomeState = rememberUpdatedState(onNavigateHome)
    // Référence à la WebView active, posée par `LockedWebView` une fois créée, pour que
    // le BackHandler ci-dessous puisse lui demander de reculer dans l'historique du site,
    // et pour que le curseur virtuel (si actif) puisse lui envoyer des taps simulés.
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

    // Module téléchargement — sniffer partagé pour la durée de l'écran (reset à chaque
    // nouvelle page ou nouvelle plateforme, voir les LaunchedEffect ci-dessous).
    val sniffer = remember { StreamSniffer() }
    val detectedStreams by sniffer.detectedStreams.collectAsState()
    var isPageFullscreen by remember { mutableStateOf(false) }
    var showStreamsDialog by remember { mutableStateOf(false) }
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
            // Curseur centré à l'ouverture.
            cursorOffset = Offset(containerSize.width / 2f, containerSize.height / 2f)
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
        pageTitle = null
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
                        cursorOffset = current.copy(y = (current.y - cursorStepPx).coerceAtLeast(0f))
                        true
                    }
                    Key.DirectionDown -> {
                        cursorOffset = current.copy(
                            y = (current.y + cursorStepPx).coerceAtMost(containerSize.height.toFloat())
                        )
                        true
                    }
                    Key.DirectionLeft -> {
                        cursorOffset = current.copy(x = (current.x - cursorStepPx).coerceAtLeast(0f))
                        true
                    }
                    Key.DirectionRight -> {
                        cursorOffset = current.copy(
                            x = (current.x + cursorStepPx).coerceAtMost(containerSize.width.toFloat())
                        )
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
            key(url) {
                LockedWebView(
                    url = url,
                    sniffer = sniffer,
                    onWebViewCreated = { webView ->
                        webViewRef.value = webView
                        if (showVirtualCursor) {
                            // Empêche la page de capter le focus D-pad : toutes les
                            // pressions doivent remonter au gestionnaire de curseur
                            // ci-dessus, jamais au contenu de la WebView elle-même.
                            webView.isFocusable = false
                            webView.isFocusableInTouchMode = false
                        }
                    },
                    onFullscreenChanged = { fullscreen ->
                        isPageFullscreen = fullscreen
                        // Règle non négociable : jamais de flèche/dialogue en plein écran.
                        if (fullscreen) showStreamsDialog = false
                    },
                    onPageTitleChanged = { title -> pageTitle = title },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val offset = cursorOffset
        if (showVirtualCursor && offset != null) {
            VirtualCursor(offset = offset)
        }

        // Flèche téléchargement + raccourci bibliothèque — hors plein écran uniquement.
        if (!isPageFullscreen) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (onOpenDownloads != null) {
                    Surface(
                        shape = CircleShape,
                        color = Color.Black.copy(alpha = 0.65f),
                        contentColor = Color.White,
                        shadowElevation = 4.dp
                    ) {
                        TextButton(onClick = onOpenDownloads) {
                            Text("Téléch.", color = Color.White)
                        }
                    }
                }
                if (detectedStreams.isNotEmpty()) {
                    DownloadArrowButton(
                        streamCount = detectedStreams.size,
                        onClick = { showStreamsDialog = true }
                    )
                }
            }
        }

        if (showStreamsDialog) {
            DetectedStreamsDialog(
                streams = detectedStreams,
                onDismiss = { showStreamsDialog = false },
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
                        val webView = webViewRef.value
                        scope.launch {
                            try {
                                var streamToEnqueue = stream
                                var prefetched: String? = null
                                // HLS / DASH via WebView (anti-403 Vidzy, principe 1DM)
                                val viaWebView = webView != null &&
                                    (stream.type == StreamType.HLS || stream.type == StreamType.DASH)
                                if (viaWebView) {
                                    Toast.makeText(
                                        context,
                                        "Préparation du téléchargement…",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    val resolved =
                                        resolvePlaylistInWebView(webView!!, stream.url)
                                    prefetched = resolved.second
                                    if (resolved.first != stream.url) {
                                        streamToEnqueue = stream.copy(url = resolved.first)
                                    }
                                }
                                val downloadId = mgr.enqueue(
                                    stream = streamToEnqueue,
                                    title = titleSnapshot,
                                    userAgent = ua,
                                    prefetchedPlaylistBody = prefetched,
                                    // Évite la course Worker OkHttp vs WebView (fichier vide)
                                    startWorker = !viaWebView
                                )
                                if (viaWebView) {
                                    when (stream.type) {
                                        StreamType.HLS ->
                                            mgr.startHlsViaWebView(downloadId, webView!!)
                                        StreamType.DASH ->
                                            mgr.startDashViaWebView(downloadId, webView!!)
                                        else -> Unit
                                    }
                                }
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
                                    Toast.LENGTH_LONG
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
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text("Fermer")
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
private const val DPAD_MOVE_STEP_DP = 28
private const val CURSOR_SIZE_DP = 22

/** Simule un tap à ([x], [y]) — coordonnées en pixels, dans le repère de la WebView. */
private fun WebView.simulateClick(x: Float, y: Float) {
    val downTime = SystemClock.uptimeMillis()
    val downEvent = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0)
    val upEvent = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_UP, x, y, 0)
    dispatchTouchEvent(downEvent)
    dispatchTouchEvent(upEvent)
    downEvent.recycle()
    upEvent.recycle()
}

/** Petit curseur circulaire (blanc, bordure noire) superposé à la WebView, positionné en pixels. */
@Composable
private fun VirtualCursor(offset: Offset, modifier: Modifier = Modifier) {
    val density = LocalDensity.current
    val halfSizePx = with(density) { (CURSOR_SIZE_DP.dp / 2).toPx() }
    Box(
        modifier = modifier
            .offset {
                IntOffset(
                    (offset.x - halfSizePx).roundToInt(),
                    (offset.y - halfSizePx).roundToInt()
                )
            }
            .size(CURSOR_SIZE_DP.dp)
            .background(Color.White, shape = CircleShape)
            .border(2.dp, Color.Black, CircleShape)
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LockedWebView(
    url: String,
    sniffer: StreamSniffer,
    onWebViewCreated: (WebView) -> Unit,
    onFullscreenChanged: (Boolean) -> Unit,
    onPageTitleChanged: (String?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val allowedHost = remember(url) { Uri.parse(url).host }
    val snifferState = rememberUpdatedState(sniffer)
    val onFullscreenState = rememberUpdatedState(onFullscreenChanged)
    val onPageTitleState = rememberUpdatedState(onPageTitleChanged)

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
                setBackgroundColor(Color.Black.toArgb())

                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
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
                        val host = request.url.host ?: return true
                        val isAllowed = allowedHost != null &&
                            (host == allowedHost || host.endsWith(".$allowedHost"))
                        // true = on bloque la navigation (Android n'appelle pas loadUrl,
                        // la page affichée reste inchangée) ; false = on laisse la WebView
                        // la charger elle-même.
                        return !isAllowed
                    }

                    override fun onPageStarted(view: WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                        // Nouvelle page dans l'historique du site → les flux capturés pour
                        // l'ancienne page n'ont plus cours (module téléchargement).
                        snifferState.value.resetForNewPage(pageUrl)
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

                snifferState.value.resetForNewPage(url)
                loadUrl(url)
            }.also(onWebViewCreated)
        },
        onRelease = { webView -> webView.destroy() }
    )
}


/**
 * Récupère le corps texte brut (master OU media HLS, ou manifeste DASH) via fetch() dans
 * la WebView — contourne le 403 OkHttp des CDN anti-hotlink (Vidzy).
 *
 * Fix (12 août 2026) : cette fonction faisait auparavant elle-même la résolution
 * master → variante (via `HlsPlaylistParser.parseMaster` + `isMasterPlaylist`) et ne
 * transmettait que le media body déjà résolu comme `prefetchedPlaylistBody`. Or seul
 * [HlsDownloader] (via `parseMasterFull`) sait détecter une piste audio séparée
 * (`#EXT-X-MEDIA:TYPE=AUDIO`) dans le master — une fois le media body substitué ici, le
 * master d'origine était perdu et `HlsDownloader` ne voyait plus qu'une media playlist
 * (`isMasterPlaylist` → false), donc plus aucune piste audio séparée téléchargée pour les
 * flux HLS passant par la WebView. On renvoie donc désormais le body tel quel (master ou
 * media, HLS ou DASH) sans y toucher : toute la résolution (variante, groupe audio,
 * playlist audio) reste faite une seule fois, par [HlsDownloader] lui-même, qui utilise
 * ensuite le `textFetcher`/`segmentFetcher` (WebView) fournis par
 * `FilmDownloadManager.startHlsViaWebView` pour les fetches suivants.
 *
 * @return Pair([playlistUrl] inchangée, corps texte brut)
 */
private suspend fun resolvePlaylistInWebView(
    webView: android.webkit.WebView,
    playlistUrl: String
): Pair<String, String> {
    val body = WebViewHttpFetcher.fetchText(webView, playlistUrl)
    return playlistUrl to body
}
