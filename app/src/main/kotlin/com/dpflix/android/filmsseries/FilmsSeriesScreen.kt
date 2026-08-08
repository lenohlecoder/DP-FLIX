package com.dpflix.android.filmsseries

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.GeneralSettings
import kotlinx.coroutines.delay

/**
 * Section "Films et Séries" (remplace l'ancien Guide TV, retiré le 25 juillet 2026 — voir
 * `DpFlixDestination`) : navigateur intégré verrouillé sur une plateforme externe. Deux
 * plateformes indépendantes possibles ("Stream 1"/"Stream 2", French-Stream, 08/08) —
 * [streamIndex] sélectionne laquelle : 1 → [GeneralSettings.filmsSeriesUrl]/
 * [GeneralSettings.DEFAULT_FILMS_SERIES_URL], 2 → [GeneralSettings.filmsSeriesUrl2]/
 * [GeneralSettings.DEFAULT_FILMS_SERIES_URL_2]. Choisi à l'accueil via
 * `FilmsSeriesStreamPickerDialog`, transporté par `DpFlixDestination.FilmsSeries`.
 *
 * Réutilisé tel quel côté mobile ET TV — contrairement au reste de l'app (mobile
 * `material3`/`androidx.compose.foundation`, TV `androidx.tv.material3`/D-pad), cet écran
 * n'a aucun état ni disposition propres à dupliquer : c'est une simple `WebView` plein
 * écran, dont le comportement (verrouillage domaine, double-appui retour) est strictement
 * identique sur les deux points d'entrée. Voir `FilmsSeriesScreenTv.kt` pour le petit
 * wrapper qui l'expose sous ce nom côté TV.
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
 */
@Composable
fun FilmsSeriesScreen(
    appRepository: AppRepository,
    onNavigateHome: () -> Unit,
    streamIndex: Int = 1,
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
    // le BackHandler ci-dessous puisse lui demander de reculer dans l'historique du site
    // avant d'envisager de sortir vers l'accueil.
    val webViewRef = remember { mutableStateOf<WebView?>(null) }

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

    Box(modifier = modifier.fillMaxSize().statusBarsPadding()) {
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
                    onWebViewCreated = { webViewRef.value = it },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}

private const val DOUBLE_BACK_WINDOW_MS = 2000L

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
