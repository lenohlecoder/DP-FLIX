package com.dpflix.android.filmsseries.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/**
 * Assemblage audio + vidéo en un seul conteneur MP4 via [MediaMuxer] (API Android,
 * sans binaire ffmpeg externe).
 *
 * Fonctionne bien quand les pistes sont en conteneurs progressifs / fMP4 lisibles
 * par [MediaExtractor] (souvent segments `.m4s` / `.mp4` DASH ou HLS fMP4).
 *
 * Échoue proprement sur MPEG-TS brut (`.ts`) : dans ce cas le caller conserve
 * les deux fichiers et la lecture passe par [MergingMediaSource] ExoPlayer.
 */
object MediaTrackMuxer {

    data class MuxResult(
        val output: File,
        val usedMuxer: Boolean
    )

    /**
     * Tente de muxer [videoFile] + [audioFile] → [outputMp4].
     * @return usedMuxer=false si impossible (caller doit garder les fichiers séparés)
     */
    fun tryMux(videoFile: File, audioFile: File, outputMp4: File): MuxResult {
        if (!videoFile.exists() || !audioFile.exists()) {
            return MuxResult(videoFile, usedMuxer = false)
        }
        // MPEG-TS : MediaExtractor gère parfois, souvent pas de façon fiable pour mux
        if (looksLikeMpegTs(videoFile) || looksLikeMpegTs(audioFile)) {
            return MuxResult(videoFile, usedMuxer = false)
        }

        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        return try {
            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            val videoTrack = findTrack(videoExtractor, "video/")
                ?: return MuxResult(videoFile, usedMuxer = false)
            val audioTrack = findTrack(audioExtractor, "audio/")
                ?: return MuxResult(videoFile, usedMuxer = false)

            videoExtractor.selectTrack(videoTrack)
            audioExtractor.selectTrack(audioTrack)

            if (outputMp4.exists()) outputMp4.delete()
            muxer = MediaMuxer(outputMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val outVideo = muxer.addTrack(videoExtractor.getTrackFormat(videoTrack))
            val outAudio = muxer.addTrack(audioExtractor.getTrackFormat(audioTrack))
            muxer.start()

            val buffer = ByteBuffer.allocate(1024 * 1024)
            val info = MediaCodec.BufferInfo()

            copyTrack(videoExtractor, muxer, outVideo, buffer, info)
            copyTrack(audioExtractor, muxer, outAudio, buffer, info)

            muxer.stop()
            muxer.release()
            muxer = null

            if (outputMp4.length() > 0L) {
                MuxResult(outputMp4, usedMuxer = true)
            } else {
                runCatching { outputMp4.delete() }
                MuxResult(videoFile, usedMuxer = false)
            }
        } catch (_: Exception) {
            runCatching { outputMp4.delete() }
            MuxResult(videoFile, usedMuxer = false)
        } finally {
            runCatching { videoExtractor.release() }
            runCatching { audioExtractor.release() }
            runCatching {
                muxer?.stop()
                muxer?.release()
            }
        }
    }

    private fun findTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        muxer: MediaMuxer,
        trackIndex: Int,
        buffer: ByteBuffer,
        info: MediaCodec.BufferInfo
    ) {
        extractor.seekTo(0, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break
            info.offset = 0
            info.size = sampleSize
            info.presentationTimeUs = extractor.sampleTime.coerceAtLeast(0L)
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(trackIndex, buffer, info)
            if (!extractor.advance()) break
        }
    }

    private fun looksLikeMpegTs(file: File): Boolean {
        if (file.name.endsWith(".ts", ignoreCase = true)) return true
        return try {
            file.inputStream().use { input ->
                val b = ByteArray(4)
                val n = input.read(b)
                // sync byte 0x47 MPEG-TS
                n >= 1 && b[0] == 0x47.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Convention sidecar audio à côté du fichier vidéo principal. */
    fun audioSidecarFor(videoFile: File): File {
        val name = videoFile.nameWithoutExtension
        return File(videoFile.parentFile, "$name.audio${videoFile.extension.let { if (it.isNotEmpty()) ".$it" else ".bin" }}")
    }

    fun findSidecarAudio(videoPath: String): File? {
        val video = File(videoPath)
        val candidates = listOf(
            File(video.parentFile, "${video.nameWithoutExtension}.audio.ts"),
            File(video.parentFile, "${video.nameWithoutExtension}.audio.m4a"),
            File(video.parentFile, "${video.nameWithoutExtension}.audio.mp4"),
            File(video.parentFile, "${video.nameWithoutExtension}.audio.bin")
        )
        return candidates.firstOrNull { it.exists() && it.length() > 0L }
    }
}
