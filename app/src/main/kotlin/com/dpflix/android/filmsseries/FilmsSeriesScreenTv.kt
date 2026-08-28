package com.dpflix.android.filmsseries

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.dpflix.android.repository.AppRepository
import com.dpflix.android.settings.GeneralSettings
import com.dpflix.android.filmsseries.download.FilmDownloadManager

/**
 * TV entry point for Films & Series.
 *
 * DP-FLIX keeps its shell/navigation/access model. The complete web experience is
 * delegated to the embedded TV Bro activity: cursor, remote input, focus, IME,
 * address bar, WebView, fullscreen and browser navigation are therefore handled by
 * TV Bro rather than by a second DP-FLIX implementation.
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
    val context = LocalContext.current
    val generalSettings by appRepository.settings.generalSettings.collectAsState(initial = null)

    LaunchedEffect(streamIndex, generalSettings) {
        val settings = generalSettings ?: return@LaunchedEffect
        val url = when (streamIndex) {
            2 -> settings.filmsSeriesUrl2 ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL_2
            3 -> settings.filmsSeriesUrl3 ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL_3
            4 -> GeneralSettings.DEFAULT_FILMS_SERIES_URL_4
            5 -> GeneralSettings.DEFAULT_FILMS_SERIES_URL_5
            else -> settings.filmsSeriesUrl ?: GeneralSettings.DEFAULT_FILMS_SERIES_URL
        }
        val manualKeywords = when (streamIndex) {
            1 -> settings.tvBroBlockedKeywords1
            2 -> settings.tvBroBlockedKeywords2
            3 -> settings.tvBroBlockedKeywords3
            4 -> settings.tvBroBlockedKeywords4
            5 -> settings.tvBroBlockedKeywords5
            else -> emptySet()
        }
        TvBroStreamLauncher.open(context, url, streamIndex, manualKeywords)
        onNavigateHome()
    }

    Box(modifier = modifier.fillMaxSize())
}
