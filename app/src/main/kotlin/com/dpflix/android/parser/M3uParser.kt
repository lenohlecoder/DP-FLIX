package com.dpflix.android.parser

import com.dpflix.android.model.Channel
import java.util.UUID

/**
 * Résultat du parsing d'une playlist M3U : les chaînes extraites.
 */
data class M3uParseResult(
    val channels: List<Channel>
)

/**
 * Parseur M3U / M3U8 (§4.2 Étape 2b, §4.6).
 *
 * Volontairement une simple fonction pure `String -> M3uParseResult` : ce parseur
 * ne fait aucune IO. Le téléchargement de l'URL de playlist ou la lecture du fichier
 * local importé (les deux options du formulaire M3U) sont la responsabilité de la
 * couche réseau/repository (étape 4), qui appellera [M3uParser.parse] avec le
 * contenu texte déjà récupéré. Ça permet de tester le parseur sans réseau ni disque.
 */
object M3uParser {

    // Accepte clé="valeur", clé='valeur' et clé=valeur (sans guillemets, jusqu'au prochain
    // espace/virgule) — certains générateurs de playlist (panels IPTV, exports maison) ne
    // quotent pas systématiquement leurs attributs.
    private val ATTR_REGEX = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s,"']+))""")

    /**
     * @param rawContent contenu texte brut du fichier M3U (déjà téléchargé ou lu localement).
     * @param playlistId id de la [com.dpflix.android.model.Playlist] à laquelle rattacher les chaînes produites.
     * @throws IllegalArgumentException si le contenu ne commence pas par `#EXTM3U`.
     */
    fun parse(rawContent: String, playlistId: String): M3uParseResult {
        // Normalise toutes les variantes de fin de ligne (\n, \r\n, et \r seul — ce dernier
        // provoquait un fichier "à une seule ligne" et donc 0 chaîne détectée) et retire un
        // éventuel BOM UTF-8 en tête de fichier.
        val normalized = rawContent
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val lines = normalized.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()

        require(lines.isNotEmpty() && lines.first().uppercase().startsWith("#EXTM3U")) {
            "Fichier M3U invalide : doit commencer par #EXTM3U"
        }

        val channels = mutableListOf<Channel>()
        var pendingAttrs: Map<String, String> = emptyMap()
        var pendingName: String? = null
        var sequentialNumber = 0

        for (line in lines.drop(1)) {
            when {
                line.startsWith("#EXTINF") -> {
                    // La virgule qui sépare les attributs du nom de la chaîne peut apparaître
                    // à l'intérieur d'une valeur quotée (ex: group-title="News, Sport") : on
                    // ignore donc les virgules situées entre guillemets pour trouver la bonne.
                    val commaIndex = indexOfUnquotedComma(line)
                    val attrsPart = if (commaIndex >= 0) line.substring(0, commaIndex) else line
                    pendingName = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else null
                    pendingAttrs = ATTR_REGEX.findAll(attrsPart)
                        .associate { match ->
                            val g = match.groupValues
                            val value = g[2].takeIf { it.isNotEmpty() }
                                ?: g[3].takeIf { it.isNotEmpty() }
                                ?: g[4]
                            g[1].lowercase() to value
                        }
                }
                line.startsWith("#") -> {
                    // #EXTGRP, #EXTVLCOPT, #EXTALB, etc. — ignorés à ce stade (hors périmètre §4.2/§4.6).
                }
                else -> {
                    // Ligne d'URL de flux : clôt l'entrée #EXTINF courante (ou entrée sans métadonnées).
                    sequentialNumber++
                    val attrs = pendingAttrs
                    channels += Channel(
                        id = UUID.randomUUID().toString(),
                        playlistId = playlistId,
                        name = pendingName?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: "Chaîne $sequentialNumber",
                        streamUrl = sanitizeStreamUrl(line),
                        logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                        category = attrs["group-title"]?.takeIf { it.isNotBlank() },
                        tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                        originalNumber = attrs["tvg-chno"]?.toIntOrNull() ?: sequentialNumber
                    )
                    pendingAttrs = emptyMap()
                    pendingName = null
                }
            }
        }

        return M3uParseResult(channels = channels)
    }

    // N'importe quel caractère hors de l'ASCII imprimable (0x21-0x7E) ou un espace :
    // signe qu'une URL n'est pas correctement encodée (espace brut dans un nom de
    // fichier, caractères accentués/unicode collés tels quels dans le chemin par
    // certains générateurs de playlist). Sert uniquement à décider si un encodage est
    // nécessaire, pas à choisir quoi encoder — voir [sanitizeStreamUrl].
    private val URL_NEEDS_ENCODING_REGEX = Regex("""[^\x21-\x7E]|\s""")

    // Caractères qu'on laisse tels quels lors de l'encodage : structure d'URL (schéma,
    // séparateurs de chemin/requête) et '%' pour ne jamais ré-encoder une séquence déjà
    // percent-encodée (sans quoi "%20" deviendrait "%2520").
    private const val URL_SAFE_CHARACTERS = "%/:?&=,+@#!\$'()*;-._~"

    /**
     * Assainit l'URL de flux d'une entrée M3U (§"n'importe quel caractère spécial").
     *
     * Certains panels/générateurs de playlist exportent des URLs avec des espaces non
     * encodés ou des caractères accentués/Unicode insérés tels quels dans le chemin —
     * sans ce garde-fou, OkHttp/ExoPlayer rejette l'URL avant même la première requête
     * réseau (`IllegalArgumentException` ou `PARSING_CONTAINER_UNSUPPORTED` selon le
     * point où ça casse). Retire aussi des guillemets superflus qu'on trouve parfois
     * autour de l'URL sur certains exports maison.
     *
     * Ne touche jamais aux séquences déjà percent-encodées ni aux caractères structurels
     * de l'URL (`:`, `/`, `?`, `&`, `=`...) — seuls les caractères réellement invalides
     * dans une URI sont encodés, et seulement si au moins un en est détecté (URL déjà
     * propre laissée strictement inchangée).
     */
    private fun sanitizeStreamUrl(rawLine: String): String {
        val trimmed = rawLine.trim().removeSurrounding("\"").removeSurrounding("'").trim()
        if (!URL_NEEDS_ENCODING_REGEX.containsMatchIn(trimmed)) return trimmed
        return android.net.Uri.encode(trimmed, URL_SAFE_CHARACTERS)
    }

    /**
     * Trouve l'index de la première virgule qui ne se trouve pas à l'intérieur d'une paire
     * de guillemets (simples ou doubles). Retourne -1 si aucune n'est trouvée.
     */
    private fun indexOfUnquotedComma(line: String): Int {
        var inDoubleQuotes = false
        var inSingleQuotes = false
        for (i in line.indices) {
            when (line[i]) {
                '"' -> if (!inSingleQuotes) inDoubleQuotes = !inDoubleQuotes
                '\'' -> if (!inDoubleQuotes) inSingleQuotes = !inSingleQuotes
                ',' -> if (!inDoubleQuotes && !inSingleQuotes) return i
            }
        }
        return -1
    }
}
