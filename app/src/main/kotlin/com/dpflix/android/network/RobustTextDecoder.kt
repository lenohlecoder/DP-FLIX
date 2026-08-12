package com.dpflix.android.network

import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Décodage tolérant du contenu texte d'une playlist M3U (§"n'importe quel caractère
 * spécial") — téléchargée via HTTP ou lue depuis un fichier local importé.
 *
 * Beaucoup de panels/générateurs de playlist ne déclarent aucun charset dans leur
 * en-tête `Content-Type` (ou en déclarent un faux) alors que le contenu réel est en
 * Windows-1252/ISO-8859-1 (cas très courant pour des noms de chaîne en français avec
 * accents, générés par des outils Windows) — `okhttp3.ResponseBody.string()` utilisé
 * naïvement décode toujours en UTF-8 par défaut dans ce cas, produisant des caractères
 * accentués corrompus ("Ã©" au lieu de "é") sans qu'aucune erreur ne remonte : le fichier
 * "se lit" quand même, juste avec des noms de chaîne illisibles.
 *
 * Stratégie : BOM explicite en tête de fichier en priorité (UTF-8/UTF-16), sinon
 * décodage UTF-8 strict (erreurs signalées plutôt que remplacées par des caractères de
 * substitution) — si ça échoue, c'est que le contenu n'est très probablement pas de
 * l'UTF-8, on retombe alors sur Windows-1252 (surensemble d'ISO-8859-1, sans caractère
 * invalide possible : ce fallback ne peut jamais lever d'exception).
 */
object RobustTextDecoder {

    private val UTF8_STRICT: Charset = Charsets.UTF_8
    private val FALLBACK: Charset = Charset.forName("windows-1252")

    /**
     * @param bytes contenu brut, non encore décodé.
     * @param declaredCharset charset explicitement annoncé par la source (ex. paramètre
     * `charset` du `Content-Type` HTTP), s'il existe et est reconnu — a priorité sur
     * toute détection, l'information explicite prime toujours sur l'heuristique.
     */
    fun decode(bytes: ByteArray, declaredCharset: Charset? = null): String {
        if (declaredCharset != null && declaredCharset != Charsets.UTF_8) {
            // Un charset non-UTF-8 explicitement déclaré est fiable tel quel : pas
            // d'ambiguïté à lever, contrairement au cas "non déclaré" ci-dessous.
            return String(bytes, declaredCharset)
        }

        stripBom(bytes)?.let { (charset, offset) ->
            return String(bytes, offset, bytes.size - offset, charset)
        }

        return try {
            decodeStrict(bytes, UTF8_STRICT)
        } catch (e: CharacterCodingException) {
            // Pas de l'UTF-8 valide : très probablement du Windows-1252/ISO-8859-1
            // (courant chez les panels IPTV/exports M3U qui ne se soucient pas du
            // charset), jamais d'exception possible sur ce fallback (tout octet est une
            // position valide en Windows-1252).
            String(bytes, FALLBACK)
        }
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset): String {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    }

    /** Détecte un BOM UTF-8/UTF-16 explicite en tête de fichier et renvoie le charset à
     *  utiliser plus l'offset (en octets) où le contenu réel commence, ou `null` si
     *  aucun BOM reconnu n'est présent. */
    private fun stripBom(bytes: ByteArray): Pair<Charset, Int>? = when {
        bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte() ->
            Charsets.UTF_8 to 3
        bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() ->
            Charsets.UTF_16LE to 2
        bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() ->
            Charsets.UTF_16BE to 2
        else -> null
    }

    /** Extrait le charset du paramètre `charset=` d'un en-tête `Content-Type`, s'il est
     *  présent et reconnu par la JVM (ex. `text/plain; charset=UTF-8`). */
    fun charsetFromContentType(contentType: String?): Charset? {
        if (contentType == null) return null
        val match = Regex("""charset\s*=\s*"?([\w-]+)"?""", RegexOption.IGNORE_CASE).find(contentType)
            ?: return null
        return try {
            Charset.forName(match.groupValues[1])
        } catch (e: Exception) {
            null
        }
    }
}
