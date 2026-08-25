package com.dpflix.android.filmsseries

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dpflix.android.filmsseries.download.FilmDownloadManager
import com.dpflix.android.repository.AppRepository

/**
 * Wrapper TV de [FilmsSeriesScreen] (§ section "Films et Séries", remplace l'ancien Guide
 * TV — voir `DpFlixDestination`).
 *
 * Comme anticipé dans la doc de [FilmsSeriesScreen] : cet écran est une simple `WebView`
 * plein écran (verrouillage domaine + double-appui retour), sans disposition D-pad
 * spécifique à adapter — contrairement au reste de l'app (mobile `material3` vs TV
 * `androidx.tv.material3`), il n'y a donc rien à dupliquer ici. `showVirtualCursor = true`
 * (§ demande utilisateur, 09/08) est en revanche spécifique à ce wrapper : un site web
 * ordinaire n'étant pas conçu pour une navigation D-pad, un curseur superposé, déplacé par
 * les flèches de la télécommande, remplace le clic tactile absent sur TV — voir la doc de
 * [FilmsSeriesScreen] pour le détail de l'implémentation.
 *
 * Module téléchargement : [downloadManager]/[onOpenDownloads] sont simplement transmis
 * tels quels à [FilmsSeriesScreen] (flèche ↓ + raccourci "Mes téléchargements"), `null`
 * par défaut pour ne rien changer tant que l'appelant ne les fournit pas encore.
 */
@Composable
fun FilmsSeriesScreenTv(
    appRepository: AppRepository,
    onNavigateHome: () -> Unit,
    streamIndex: Int = 1,
    downloadManager: FilmDownloadManager? = null,
    onOpenDownloads: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    FilmsSeriesScreen(
        appRepository = appRepository,
        onNavigateHome = onNavigateHome,
        streamIndex = streamIndex,
        // TvFlix fournit le WebView desktop et le curseur D-pad. Le reste de
        // FilmsSeriesScreen est inchangé : téléchargement, sniffer, blocage des
        // redirections, historique, plein écran, menus et persistance WebView.
        showVirtualCursor = false,
        useTvFlix = true,
        downloadManager = downloadManager,
        onOpenDownloads = onOpenDownloads,
        modifier = modifier
    )
}
