package com.dpflix.android.filmsseries

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.phlox.tvwebbrowser.activity.main.MainActivity
import java.util.Locale

/**
 * Launches the embedded TV Bro browser in DP-FLIX locked mode.
 *
 * TV Bro owns WebView, cursor, D-pad, focus, IME, address bar, fullscreen and
 * browser navigation. DP-FLIX only supplies the selected stream URL and its
 * host allow-list. The browser therefore remains isolated from DP-FLIX's Compose
 * UI workload while preserving the application's access-control shell.
 */
object TvBroStreamLauncher {
    private val STREAM_INFRASTRUCTURE_HOSTS: Map<Int, Set<String>> = mapOf(
        1 to setOf("purstream.tv", "purstream.wiki", "themoviedb.org", "api.themoviedb.org", "image.tmdb.org"),
        2 to setOf("french-manga.net", "cdnjs.cloudflare.com", "image.tmdb.org", "themoviedb.org"),
        3 to setOf("aoneroom.com", "cloudfront.net", "themoviebox.app", "moviebox.co", "moviebox.ph",
            "movieboxonline.net", "trasre.com", "downloadmoviebox.com", "downloader2.com"),
        4 to setOf("youtube.com", "m.youtube.com", "youtube-nocookie.com", "googlevideo.com",
            "ytimg.com", "youtubei.googleapis.com"),
        5 to setOf("xnxx.com")
    )


    /**
     * Conservative defaults: match brand fragments anywhere in the hostname.
     * Deliberately excludes generic words ("ads", "video", "cdn", "stream", etc.) and
     * excludes MovieBox because it is legitimate infrastructure for Stream 3.
     */
    private val STREAM_BLOCKED_HOST_KEYWORDS: Map<Int, Set<String>> = mapOf(
        1 to setOf("1xbet", "1xlite", "melbet", "propellerads", "adsterra", "exoclick", "juicyads", "trafficjunky", "clickadu", "hilltopads", "adnium", "popads", "popcash", "adcash", "mgid", "taboola", "outbrain"),
        2 to setOf("1xbet", "1xlite", "melbet", "xsportshd", "wuytg", "moonlighthathel", "golzu", "ragiscafila", "tracylocalschool", "propellerads", "adsterra", "exoclick", "juicyads", "clickadu", "hilltopads", "popads", "popcash", "adcash", "mgid", "taboola", "outbrain"),
        3 to setOf("1xbet", "1xlite", "melbet", "propellerads", "adsterra", "exoclick", "juicyads", "trafficjunky", "clickadu", "hilltopads", "adnium", "popads", "popcash", "adcash", "mgid", "taboola", "outbrain"),
        4 to setOf("1xbet", "1xlite", "melbet", "propellerads", "adsterra", "exoclick", "juicyads", "trafficjunky", "clickadu", "hilltopads", "adnium", "popads", "popcash", "adcash", "mgid", "taboola", "outbrain"),
        5 to setOf("1xbet", "1xlite", "melbet", "propellerads", "adsterra", "exoclick", "juicyads", "trafficjunky", "clickadu", "hilltopads", "adnium", "popads", "popcash", "adcash", "mgid", "taboola", "outbrain")
    )

    suspend fun open(context: Context, url: String, streamIndex: Int = 1, manualBlockedKeywords: Set<String> = emptySet()) {
        val host = Uri.parse(url).host?.lowercase(Locale.ROOT)?.removePrefix("www.")
            ?: return
        val allowed = ArrayList<String>().apply {
            add(host)
            addAll(STREAM_INFRASTRUCTURE_HOSTS[streamIndex].orEmpty())
        }
        // Stream 1 et 3 : aucune blacklist automatique DP-FLIX. L'utilisateur contrôle
        // exclusivement les mots-clés depuis Réglages. Les autres streams conservent leur
        // protection automatique existante et acceptent en plus les règles personnalisées.
        val automatic = if (streamIndex == 1 || streamIndex == 3) emptySet()
                        else STREAM_BLOCKED_HOST_KEYWORDS[streamIndex].orEmpty()
        val combinedKeywords = automatic + manualBlockedKeywords
        val intent = Intent(context, MainActivity::class.java).apply {
            data = Uri.parse(url)
            putExtra(MainActivity.EXTRA_DPFLIX_LOCKED_MODE, true)
            putStringArrayListExtra(MainActivity.EXTRA_DPFLIX_ALLOWED_HOSTS, allowed)
            putStringArrayListExtra(
                MainActivity.EXTRA_DPFLIX_BLOCKED_HOST_KEYWORDS,
                ArrayList(combinedKeywords)
            )
            putExtra(MainActivity.EXTRA_DPFLIX_STREAM_INDEX, streamIndex)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        context.startActivity(intent)
    }
}
