package com.dpflix.android.companion

import android.annotation.SuppressLint
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.dpflix.android.repository.AppRepository

/**
 * WebView « Infos programme » — domaine verrouillé sur [CompanionConfig.BASE_URL].
 * À l'ouverture : marque [GeneralSettings.lastSeenInfosVersion] = version courante
 * pour faire disparaître le badge cloche.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun InfosWebViewScreen(
    appRepository: AppRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    // Marque les infos comme vues dès l'ouverture (badge disparaît au prochain focus accueil).
    LaunchedEffect(Unit) {
        val status = appRepository.companion.getStatus()
        val version = status?.infosVersion ?: return@LaunchedEffect
        appRepository.settings.updateGeneralSettings { current ->
            current.copy(lastSeenInfosVersion = version)
        }
    }

    val allowedHost = Uri.parse(CompanionConfig.BASE_URL).host

    AndroidView(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding(),
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
                setOnLongClickListener { true }
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest
                    ): Boolean {
                        val host = request.url.host ?: return true
                        val ok = allowedHost != null &&
                            (host == allowedHost || host.endsWith(".$allowedHost"))
                        return !ok
                    }
                }
                loadUrl(CompanionConfig.INFOS_URL)
            }
        },
        onRelease = { it.destroy() }
    )
}
