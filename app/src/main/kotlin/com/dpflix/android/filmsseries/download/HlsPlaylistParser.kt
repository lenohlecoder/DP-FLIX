package com.dpflix.android.filmsseries.download

import java.net.URI

/**
 * Parseur m3u8 (master + media playlist) avec support des pistes audio séparées
 * (`#EXT-X-MEDIA:TYPE=AUDIO`).
 */
object HlsPlaylistParser {

    data class MasterVariant(
        val bandwidth: Int?,
        val resolution: String?,
        val uri: String,
        /** GROUP-ID audio associé (EXT-X-STREAM-INF:AUDIO="…") */
        val audioGroupId: String? = null
    )

    data class AudioRendition(
        val groupId: String,
        val name: String?,
        val language: String?,
        val uri: String?,
        val isDefault: Boolean
    )

    data class MediaPlaylist(
        val segments: List<Segment>,
        val isEndList: Boolean
    )

    data class Segment(
        val uri: String,
        val durationSec: Double?
    )

    data class MasterParseResult(
        val variants: List<MasterVariant>,
        val audioRenditions: List<AudioRendition>
    )

    fun isMasterPlaylist(body: String): Boolean =
        body.lineSequence().any { it.trimStart().startsWith("#EXT-X-STREAM-INF") }

    fun parseMasterFull(body: String, baseUrl: String): MasterParseResult {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val variants = mutableListOf<MasterVariant>()
        val audios = mutableListOf<AudioRendition>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            when {
                line.startsWith("#EXT-X-MEDIA:") -> {
                    val type = attr(line, "TYPE")
                    if (type.equals("AUDIO", ignoreCase = true)) {
                        val groupId = attr(line, "GROUP-ID") ?: ""
                        val uri = attr(line, "URI")
                        audios += AudioRendition(
                            groupId = groupId,
                            name = attr(line, "NAME"),
                            language = attr(line, "LANGUAGE"),
                            uri = uri?.let { resolveUrl(baseUrl, it.removeSurrounding("\"")) },
                            isDefault = attr(line, "DEFAULT")?.equals("YES", true) == true
                        )
                    }
                    i++
                }
                line.startsWith("#EXT-X-STREAM-INF") -> {
                    val bandwidth = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)?.toIntOrNull()
                    val resolution = Regex("RESOLUTION=([^,\\s]+)", RegexOption.IGNORE_CASE)
                        .find(line)?.groupValues?.get(1)
                    val audioGroup = attr(line, "AUDIO")
                    val next = lines.getOrNull(i + 1)
                    if (next != null && !next.startsWith("#")) {
                        variants += MasterVariant(
                            bandwidth = bandwidth,
                            resolution = resolution,
                            uri = resolveUrl(baseUrl, next),
                            audioGroupId = audioGroup
                        )
                        i += 2
                        continue
                    }
                    i++
                }
                else -> i++
            }
        }
        return MasterParseResult(
            variants = variants.sortedByDescending { it.bandwidth ?: 0 },
            audioRenditions = audios
        )
    }

    fun parseMaster(body: String, baseUrl: String): List<MasterVariant> =
        parseMasterFull(body, baseUrl).variants

    fun parseMedia(body: String, baseUrl: String): MediaPlaylist {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val segments = mutableListOf<Segment>()
        var pendingDuration: Double? = null
        var endList = false
        for (line in lines) {
            when {
                line.startsWith("#EXTINF:") -> {
                    val raw = line.removePrefix("#EXTINF:").substringBefore(',')
                    pendingDuration = raw.toDoubleOrNull()
                }
                line.startsWith("#EXT-X-ENDLIST") -> endList = true
                line.startsWith("#") -> Unit
                else -> {
                    segments += Segment(
                        uri = resolveUrl(baseUrl, line),
                        durationSec = pendingDuration
                    )
                    pendingDuration = null
                }
            }
        }
        return MediaPlaylist(segments = segments, isEndList = endList)
    }

    private fun attr(line: String, key: String): String? {
        val quoted = Regex("""$key="([^"]*)"""", RegexOption.IGNORE_CASE).find(line)
        if (quoted != null) return quoted.groupValues[1]
        val plain = Regex("""$key=([^,]+)""", RegexOption.IGNORE_CASE).find(line)
        return plain?.groupValues?.get(1)?.trim()
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
}
