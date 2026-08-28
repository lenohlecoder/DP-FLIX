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
        var mediaPresentationDurationSec: Double? = null
        var periodDurationSec: Double? = null
        val representations = mutableListOf<RepBuilder>()
        var currentRep: RepBuilder? = null
        var currentPeriodBase = mpdUrl
        var mpdBase = mpdUrl
        var currentAdaptationType = ContentType.UNKNOWN
        var segmentTemplate: SegmentTemplate? = null
        var segmentListUrls = mutableListOf<String>()
        var segmentTimeline = mutableListOf<TimelineEntry>()
        var segmentBaseUrl: String? = null
        var initializationUrl: String? = null
        var timescale = 1L
        var duration = 0L
        var startNumber = 1L
        var timelineTimeCursor = 0L

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name.substringAfterLast(':')) {
                        "MPD" -> {
                            val type = parser.getAttributeValue(null, "type")
                            isDynamic = type.equals("dynamic", ignoreCase = true)
                            // ISO-8601 duration e.g. PT1H23M45.6S
                            mediaPresentationDurationSec = parseIsoDurationSec(
                                parser.getAttributeValue(null, "mediaPresentationDuration")
                            )
                        }
                        "Period" -> {
                            val pd = parseIsoDurationSec(parser.getAttributeValue(null, "duration"))
                            if (pd != null) periodDurationSec = pd
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
                            segmentTimeline = mutableListOf()
                            segmentBaseUrl = null
                            initializationUrl = null
                            timelineTimeCursor = 0L
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
                        "SegmentTimeline" -> {
                            // reset cursor; entries collected via nested S tags
                            timelineTimeCursor = 0L
                        }
                        "S" -> {
                            // SegmentTimeline entry: t (start time), d (duration), r (repeat count)
                            val t = parser.getAttributeValue(null, "t")?.toLongOrNull()
                            val d = parser.getAttributeValue(null, "d")?.toLongOrNull() ?: 0L
                            val r = parser.getAttributeValue(null, "r")?.toLongOrNull() ?: 0L
                            val start = t ?: timelineTimeCursor
                            // r = number of *additional* repeats (total occurrences = r+1)
                            val occurrences = (r + 1).coerceAtLeast(1)
                            var time = start
                            repeat(occurrences.toInt().coerceAtMost(50_000)) {
                                segmentTimeline += TimelineEntry(time = time, duration = d)
                                time += d
                            }
                            timelineTimeCursor = time
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name.substringAfterLast(':')) {
                        "Representation" -> {
                            currentRep?.let { rep ->
                                val base = rep.baseUrl.ifBlank { currentPeriodBase }
                                val totalDur = periodDurationSec ?: mediaPresentationDurationSec
                                val segs = buildSegments(
                                    baseUrl = base,
                                    template = segmentTemplate,
                                    listUrls = segmentListUrls.toList(),
                                    timeline = segmentTimeline.toList(),
                                    initUrl = initializationUrl,
                                    segmentBase = segmentBaseUrl != null,
                                    totalDurationSec = totalDur,
                                    repId = rep.id,
                                    bandwidth = rep.bandwidth
                                )
                                rep.segmentUrls = segs
                                representations += rep
                            }
                            currentRep = null
                            // Timeline souvent au niveau AdaptationSet (partagée) ;
                            // si elle était sous Representation, elle a déjà été consommée.
                            // On ne la vide pas ici pour préserver le cas AdaptationSet.
                        }
                        "AdaptationSet" -> {
                            segmentTemplate = null
                            segmentListUrls = mutableListOf()
                            segmentTimeline = mutableListOf()
                            initializationUrl = null
                            segmentBaseUrl = null
                            timelineTimeCursor = 0L
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

    /** Entrée SegmentTimeline : temps de début et durée en unités timescale. */
    private data class TimelineEntry(
        val time: Long,
        val duration: Long
    )

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
        timeline: List<TimelineEntry>,
        initUrl: String?,
        segmentBase: Boolean,
        totalDurationSec: Double? = null,
        repId: String? = null,
        bandwidth: Int? = null
    ): List<String> {
        val out = mutableListOf<String>()
        if (initUrl != null) {
            out += resolveUrl(
                baseUrl,
                expandTemplate(initUrl, number = 0, time = 0, repId = repId, bandwidth = bandwidth)
            )
        }
        when {
            listUrls.isNotEmpty() -> {
                listUrls.forEach { out += resolveUrl(baseUrl, it) }
            }
            // SegmentTimeline : liste explicite de (t, d) — priorité sur duration fixe
            template?.media != null && timeline.isNotEmpty() -> {
                var n = template.startNumber
                for (entry in timeline) {
                    val media = expandTemplate(
                        template.media,
                        number = n,
                        time = entry.time,
                        repId = repId,
                        bandwidth = bandwidth
                    )
                    out += resolveUrl(baseUrl, media)
                    n++
                }
            }
            template?.media != null && template.duration > 0 -> {
                // Nombre de segments calculé à partir de la durée réelle du MPD/Period
                // quand elle est disponible ; sinon borne haute raisonnable + stop au 404.
                val segDurSec = template.duration.toDouble() / template.timescale.coerceAtLeast(1L)
                val count = when {
                    totalDurationSec != null && segDurSec > 0 -> {
                        (kotlin.math.ceil(totalDurationSec / segDurSec).toInt() + 2).coerceIn(1, 20_000)
                    }
                    else -> 500
                }
                var n = template.startNumber
                var time = template.presentationTimeOffset
                repeat(count) {
                    val media = expandTemplate(
                        template.media,
                        number = n,
                        time = time,
                        repId = repId,
                        bandwidth = bandwidth
                    )
                    out += resolveUrl(baseUrl, media)
                    n++
                    time += template.duration
                }
            }
            template?.media != null -> {
                // Sans duration : un seul segment média
                out += resolveUrl(
                    baseUrl,
                    expandTemplate(template.media, template.startNumber, 0, repId, bandwidth)
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
     * Remplace $Number$, $Time$, $RepresentationID$, $Bandwidth$ (avec éventuel format %0Nd).
     */

    /**
     * Parse une durée ISO-8601 simplifiée (PT#H#M#S / PT#S / P#DT#H…).
     * Retourne des secondes, ou null si absent / illisible.
     */
    fun parseIsoDurationSec(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        // Exemples : PT1H2M3.5S, PT90S, PT1.5H, P0Y0M0DT1H2M3S
        val re = Regex(
            """^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?$""",
            RegexOption.IGNORE_CASE
        )
        val m = re.matchEntire(raw.trim()) ?: return null
        val days = m.groupValues[3].toDoubleOrNull() ?: 0.0
        val hours = m.groupValues[4].toDoubleOrNull() ?: 0.0
        val minutes = m.groupValues[5].toDoubleOrNull() ?: 0.0
        val seconds = m.groupValues[6].toDoubleOrNull() ?: 0.0
        // months/years ignorés volontairement (rares en VOD streaming)
        return days * 86_400 + hours * 3600 + minutes * 60 + seconds
    }

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
     * Affinage post-parse : si SegmentTemplate sans durée fixe, on ne garde que
     * l'init + on laisse le downloader découvrir via Timeline si besoin.
     * Ici on borne les listes trop longues générées par estimation.
     */
    fun trimEstimatedSegments(segments: List<String>, maxSegments: Int = 10_000): List<String> {
        if (segments.size <= maxSegments) return segments
        return segments.take(maxSegments)
    }
}
