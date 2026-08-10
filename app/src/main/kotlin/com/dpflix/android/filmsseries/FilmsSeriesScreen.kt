package com.dpflix.android.filmsseries

import android.annotation.SuppressLint
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.GeneralSettings
import kotlinx.coroutines.delay
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
 * - `setSupportMultipleWindows(false)` + pas de [android.webkit.WebChromeClient] custom
 *   pour `onCreateWindow` : la valeur par défaut d'Android (retourner `false`, donc ne pas
 *   créer de nouvelle fenêtre) empêche déjà `window.open()`/`target="_blank"` d'ouvrir quoi
 *   que ce soit. Pas de barre d'adresse, de navigation précédente/suivante ni de menu
 *   long-press (désactivé explicitement) : aucun chrome de navigateur visible.
 *
 * ## Retour (§ demande utilisateur, révisé 08/08)
 * [BackHandler] intercepte le bouton retour (télécommande TV ou geste/bouton tactile
 * mobile — même API des deux côtés, `androidx.activity.compose.BackHandler`, pas de
 * distinction nécessaire). Priorité à la navigation dans l'historique du site
 * ([WebView.canGoBack]/[WebView.goBack]) : tant que la WebView peut reculer d'une page,
 * un appui retour la fait simplement reculer, sans toucher au compteur de double-appui.
 * Ce n'est que lorsqu'il n'y a plus de page précédente sur le site qu'un premier appui
 * affiche un `Toast` d'avertissement et ouvre une fenêtre de 2 secondes
 * ([DOUBLE_BACK_WINDOW_MS]), un second appui dans cette fenêtre déclenche
 * [onNavigateHome].
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
    modifier: Modifier = Modifier
) {
    val generalSettings by appRepository.settings.generalSettings.collectAsState(initial = null)
    val url = if (streamIndex == 2) {
        generalSettings?.filmsSeriesUrl2 ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL_2
    } else {
        generalSettings?.filmsSeriesUrl ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL
    }

    val context = LocalContext.current
    var awaitingSecondBackPress by remember { mutableStateOf(false) }
    val onNavigateHomeState = rememberUpdatedState(onNavigateHome)
    // Référence à la WebView active, posée par `LockedWebView` une fois créée, pour que
    // le BackHandler ci-dessous puisse lui demander de reculer dans l'historique du site,
    // et pour que le curseur virtuel (si actif) puisse lui envoyer des taps simulés.
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

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

    BackHandler {
        val webView = webViewRef.value
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else if (awaitingSecondBackPress) {
            awaitingSecondBackPress = false
            onNavigateHomeState.value()
        } else {
            awaitingSecondBackPress = true
            Toast.makeText(context, "Appuyez de nouveau sur retour pour revenir à l'accueil", Toast.LENGTH_SHORT).show()
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
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        val offset = cursorOffset
        if (showVirtualCursor && offset != null) {
            VirtualCursor(offset = offset)
        }
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
    onWebViewCreated: (WebView) -> Unit,
    modifier: Modifier = Modifier
) {
    val allowedHost = remember(url) { Uri.parse(url).host }

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
                }

                loadUrl(url)
            }.also(onWebViewCreated)
        },
        onRelease = { webView -> webView.destroy() }
    )
}
