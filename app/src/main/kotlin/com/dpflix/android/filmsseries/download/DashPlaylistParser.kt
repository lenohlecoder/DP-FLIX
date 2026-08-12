package com.dpflix.android.filmsseries.download

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import kotlin.math.max

/**
 * Parseur MPD DASH minimal (static VOD).
 *
 * Couvre les cas courants :
 * - Representation video/audio avec SegmentBase / SegmentList / SegmentTemplate
 * - BaseURL relatifs
 * - Choix de la meilleure bande passante vidéo + première piste audio
 *
 * Limitations assumées :
 * - pas de DRM (ContentProtection → échec côté downloader)
 * - live dynamique (type="dynamic") : segments présents au parse uniquement
 * - pas de multi-Period complexe (on prend le premier Period)
 */
object DashPlaylistParser {

    data class DashManifest(
        val isDynamic: Boolean,
        val hasDrm: Boolean,
        val video: Representation?,
        val audio: Representation?,
        val videoSegments: List<String>,
        val audioSegments: List<String>
    )

    data class Representation(
        val id: String?,
        val bandwidth: Int,
        val mimeType: String?,
        val codecs: String?,
        val width: Int?,
        val height: Int?,
        val baseUrl: String,
        val contentType: ContentType
    )

    enum class ContentType { VIDEO, AUDIO, UNKNOWN }

    fun parse(mpdBody: String, mpdUrl: String): DashManifest {
        val factory = XmlPullParserFactory.newInstance().apply { isNamespaceAware = false }
        val parser = factory.newPullParser()
        parser.setInput(StringReader(mpdBody))

        var isDynamic = false
        var hasDrm = false
        val representations = mutableListOf<RepBuilder>()
        var currentRep: RepBuilder? = null
        var currentPeriodBase = mpdUrl
        var mpdBase = mpdUrl
        var currentAdaptationType = ContentType.UNKNOWN
        var segmentTemplate: SegmentTemplate? = null
        var segmentListUrls = mutableListOf<String>()
        var segmentBaseUrl: String? = null
        var initializationUrl: String? = null
        var segmentTimeline: MutableList<TimelineS>? = null
        var timescale = 1L
        var duration = 0L
        var startNumber = 1L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfterLast(':')) {
                        "MPD" -> {
                            val type = parser.getAttributeValue(null, "type")
                            isDynamic = type.equals("dynamic", ignoreCase = true)
                        }
                        "BaseURL" -> {
                            val text = parser.nextText().trim()
                            if (text.isNotEmpty()) {
                                when {
                                    currentRep != null -> {
                                        currentRep!!.baseUrl = resolveUrl(
                                            currentRep!!.baseUrl.ifBlank { currentPeriodBase },
                                            text
                                        )
                                    }
                                    else -> {
                                        currentPeriodBase = resolveUrl(currentPeriodBase, text)
                                        mpdBase = currentPeriodBase
                                    }
                                }
                            }
                        }
                        "ContentProtection" -> hasDrm = true
                        "AdaptationSet" -> {
                            val mime = parser.getAttributeValue(null, "mimeType")
                            val ctype = parser.getAttributeValue(null, "contentType")
                            currentAdaptationType = when {
                                mime?.startsWith("video") == true || ctype == "video" -> ContentType.VIDEO
                                mime?.startsWith("audio") == true || ctype == "audio" -> ContentType.AUDIO
                                else -> ContentType.UNKNOWN
                            }
                            segmentTemplate = null
                            segmentListUrls = mutableListOf()
                            segmentBaseUrl = null
                            initializationUrl = null
                            segmentTimeline = null
                        }
                        "Representation" -> {
                            val id = parser.getAttributeValue(null, "id")
                            val bandwidth = parser.getAttributeValue(null, "bandwidth")?.toIntOrNull() ?: 0
                            val mime = parser.getAttributeValue(null, "mimeType")
                            val codecs = parser.getAttributeValue(null, "codecs")
                            val width = parser.getAttributeValue(null, "width")?.toIntOrNull()
                            val height = parser.getAttributeValue(null, "height")?.toIntOrNull()
                            var ctype = currentAdaptationType
                            if (ctype == ContentType.UNKNOWN) {
                                ctype = when {
                                    mime?.startsWith("video") == true -> ContentType.VIDEO
                                    mime?.startsWith("audio") == true -> ContentType.AUDIO
                                    width != null || height != null -> ContentType.VIDEO
                                    codecs?.startsWith("mp4a") == true || codecs?.contains("mp4a") == true ->
                                        ContentType.AUDIO
                                    else -> ContentType.UNKNOWN
                                }
                            }
                            currentRep = RepBuilder(
                                id = id,
                                bandwidth = bandwidth,
                                mimeType = mime,
                                codecs = codecs,
                                width = width,
                                height = height,
                                baseUrl = currentPeriodBase,
                                contentType = ctype
                            )
                        }
                        "SegmentTemplate" -> {
                            val media = parser.getAttributeValue(null, "media")
                            val init = parser.getAttributeValue(null, "initialization")
                            timescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull() ?: 1L
                            duration = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: 0L
                            startNumber = parser.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: 1L
                            val presentationTimeOffset =
                                parser.getAttributeValue(null, "presentationTimeOffset")?.toLongOrNull() ?: 0L
                            segmentTemplate = SegmentTemplate(
                                media = media,
                                initialization = init,
                                timescale = timescale,
                                duration = duration,
                                startNumber = startNumber,
                                presentationTimeOffset = presentationTimeOffset
                            )
                            if (init != null) initializationUrl = init
                        }
                        "SegmentTimeline" -> {
                            segmentTimeline = mutableListOf()
                        }
                        "S" -> {
                            val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                            val d = parser.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                            val r = parser.getAttributeValue(null, "r")?.toLongOrNull() ?: 0L
                            segmentTimeline?.add(TimelineS(t = t, d = d, r = r))
                        }
                        "SegmentList" -> {
                            timescale = parser.getAttributeValue(null, "timescale")?.toLongOrNull() ?: timescale
                            duration = parser.getAttributeValue(null, "duration")?.toLongOrNull() ?: duration
                            startNumber = parser.getAttributeValue(null, "startNumber")?.toLongOrNull() ?: startNumber
                        }
                        "SegmentURL" -> {
                            val media = parser.getAttributeValue(null, "media")
                            if (media != null) segmentListUrls += media
                        }
                        "Initialization" -> {
                            val sourceURL = parser.getAttributeValue(null, "sourceURL")
                            if (sourceURL != null) initializationUrl = sourceURL
                        }
                        "SegmentBase" -> {
                            // single segment often via BaseURL of representation
                            segmentBaseUrl = ""
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfterLast(':')) {
                        "Representation" -> {
                            currentRep?.let { rep ->
                                val base = rep.baseUrl.ifBlank { currentPeriodBase }
                                val segs = buildSegments(
                                    baseUrl = base,
                                    template = segmentTemplate,
                                    listUrls = segmentListUrls.toList(),
                                    initUrl = initializationUrl,
                                    segmentBase = segmentBaseUrl != null,
                                    timeline = segmentTimeline?.toList()
                                )
                                rep.segmentUrls = segs
                                representations += rep
                            }
                            currentRep = null
                        }
                        "AdaptationSet" -> {
                            segmentTemplate = null
                            segmentListUrls = mutableListOf()
                            initializationUrl = null
                            segmentBaseUrl = null
                            segmentTimeline = null
                            currentAdaptationType = ContentType.UNKNOWN
                        }
                    }
                }
            }
            event = parser.next()
        }

        val videos = representations.filter { it.contentType == ContentType.VIDEO }
        val audios = representations.filter { it.contentType == ContentType.AUDIO }
        val bestVideo = videos.maxByOrNull { it.bandwidth }
        val bestAudio = audios.maxByOrNull { it.bandwidth } ?: audios.firstOrNull()

        // Si une seule Representation "muxée" (pas de piste audio séparée), segments vidéo suffisent
        val videoSegs = bestVideo?.segmentUrls.orEmpty().ifEmpty {
            representations.maxByOrNull { it.bandwidth }?.segmentUrls.orEmpty()
        }
        val audioSegs = if (bestAudio != null && bestAudio !== bestVideo) {
            bestAudio.segmentUrls
        } else {
            emptyList()
        }

        fun toRep(b: RepBuilder?) = b?.let {
            Representation(
                id = it.id,
                bandwidth = it.bandwidth,
                mimeType = it.mimeType,
                codecs = it.codecs,
                width = it.width,
                height = it.height,
                baseUrl = it.baseUrl,
                contentType = it.contentType
            )
        }

        return DashManifest(
            isDynamic = isDynamic,
            hasDrm = hasDrm,
            video = toRep(bestVideo) ?: toRep(representations.maxByOrNull { it.bandwidth }),
            audio = toRep(bestAudio),
            videoSegments = videoSegs,
            audioSegments = audioSegs
        )
    }

    private data class SegmentTemplate(
        val media: String?,
        val initialization: String?,
        val timescale: Long,
        val duration: Long,
        val startNumber: Long,
        val presentationTimeOffset: Long
    )

    /** Une entrée `<S t= d= r=>` de `<SegmentTimeline>` — [r] = répétitions EN PLUS de la première occurrence. */
    private data class TimelineS(val t: Long?, val d: Long, val r: Long)

    private class RepBuilder(
        val id: String?,
        val bandwidth: Int,
        val mimeType: String?,
        val codecs: String?,
        val width: Int?,
        val height: Int?,
        var baseUrl: String,
        val contentType: ContentType,
        var segmentUrls: List<String> = emptyList()
    )

    private fun buildSegments(
        baseUrl: String,
        template: SegmentTemplate?,
        listUrls: List<String>,
        initUrl: String?,
        segmentBase: Boolean,
        timeline: List<TimelineS>? = null
    ): List<String> {
        val out = mutableListOf<String>()
        if (initUrl != null) {
            out += resolveUrl(baseUrl, expandTemplate(initUrl, number = 0, time = 0, repId = null, bandwidth = null))
        }
        when {
            listUrls.isNotEmpty() -> {
                listUrls.forEach { out += resolveUrl(baseUrl, it) }
            }
            // Fix (12 août 2026) : cas DASH le plus courant pour du VOD (Shaka, Bento4,
            // GPAC...) — SegmentTemplate avec un <SegmentTimeline> enfant listant les
            // segments exacts (`<S t= d= r=>`), SANS attribut `duration` fixe sur
            // SegmentTemplate lui-même. Avant ce fix, ce cas tombait dans la branche
            // "sans duration : un seul segment média" ci-dessous → un unique segment
            // (+ init) généré, téléchargement quasi instantané, fichier ne contenant
            // presque aucune frame exploitable. On priorise donc la timeline exacte
            // quand elle est présente, quel que soit l'attribut `duration`.
            template?.media != null && !timeline.isNullOrEmpty() -> {
                val times = expandTimelineTimes(timeline)
                var n = template.startNumber
                for (time in times) {
                    val media = expandTemplate(
                        template.media,
                        number = n,
                        time = time,
                        repId = null,
                        bandwidth = null
                    )
                    out += resolveUrl(baseUrl, media)
                    n++
                }
            }
            template?.media != null && template.duration > 0 -> {
                // Estimation : ~ mediaDuration inconnue → on génère un nombre raisonnable
                // de segments (max 5000) ; le downloader arrêtera sur HTTP 404.
                val approxCount = 5000
                var n = template.startNumber
                var time = template.presentationTimeOffset
                repeat(approxCount) {
                    val media = expandTemplate(
                        template.media,
                        number = n,
                        time = time,
                        repId = null,
                        bandwidth = null
                    )
                    out += resolveUrl(baseUrl, media)
                    n++
                    time += template.duration
                }
            }
            template?.media != null -> {
                // Sans duration ni timeline : un seul segment média
                out += resolveUrl(
                    baseUrl,
                    expandTemplate(template.media, template.startNumber, 0, null, null)
                )
            }
            segmentBase -> {
                // Segment unique = BaseURL de la representation
                if (baseUrl.isNotBlank() && !baseUrl.endsWith(".mpd", ignoreCase = true)) {
                    out += baseUrl
                }
            }
            else -> {
                if (baseUrl.isNotBlank() && !baseUrl.endsWith(".mpd", ignoreCase = true) && out.isEmpty()) {
                    out += baseUrl
                }
            }
        }
        return out.distinct()
    }

    /**
     * Développe une liste d'entrées `<S t= d= r=>` en la liste plate des `time` de chaque
     * segment. `t` absent → on continue depuis le temps courant (cumul des `d`
     * précédents) ; `r` = répétitions EN PLUS de la première occurrence (donc `r=2` = 3
     * segments identiques de durée `d`).
     */
    private fun expandTimelineTimes(entries: List<TimelineS>): List<Long> {
        val result = mutableListOf<Long>()
        var time = 0L
        for (entry in entries) {
            var t = entry.t ?: time
            val occurrences = 1L + entry.r.coerceAtLeast(0L)
            repeat(occurrences.toInt()) {
                result += t
                t += entry.d
            }
            time = t
        }
        return result
    }

    /**
     * Remplace $Number$, $Time$, $RepresentationID$, $Bandwidth$ (avec éventuel format %0Nd).
     */
    fun expandTemplate(
        template: String,
        number: Long,
        time: Long,
        repId: String?,
        bandwidth: Int?
    ): String {
        var s = template
        s = replaceToken(s, "Number", number)
        s = replaceToken(s, "Time", time)
        if (repId != null) s = s.replace("\$RepresentationID\$", repId)
        if (bandwidth != null) s = replaceToken(s, "Bandwidth", bandwidth.toLong())
        return s
    }

    private fun replaceToken(input: String, name: String, value: Long): String {
        val regex = Regex("""\$$name(%0(\d+)d)?\$""")
        return regex.replace(input) { m ->
            val width = m.groupValues.getOrNull(2)?.toIntOrNull()
            if (width != null) value.toString().padStart(width, '0') else value.toString()
        }
    }

    fun resolveUrl(baseUrl: String, ref: String): String {
        if (ref.startsWith("http://") || ref.startsWith("https://")) return ref
        return try {
            URI(baseUrl).resolve(ref).toString()
        } catch (_: Exception) {
            val base = baseUrl.substringBeforeLast('/') + "/"
            base + ref.trimStart('/')
        }
    }

    /**
     * Garde-fou final contre un manifeste aberrant (boucle, corruption...). Ne doit
     * normalement jamais réduire une liste exacte (SegmentList / SegmentTimeline /
     * segment unique) : celles-ci reflètent le contenu réel, potentiellement plusieurs
     * milliers de segments pour un film long à segments courts. La branche d'estimation
     * approximative (SegmentTemplate à durée fixe sans timeline) est déjà bornée à 5000
     * à la génération (voir [buildSegments]) ; ce plafond n'est qu'une seconde limite de
     * sécurité, fixée volontairement bien au-dessus pour ne jamais tronquer un cas réel.
     */
    fun trimEstimatedSegments(segments: List<String>, maxSegments: Int = 20_000): List<String> {
        if (segments.size <= maxSegments) return segments
        return segments.take(maxSegments)
    }
}
