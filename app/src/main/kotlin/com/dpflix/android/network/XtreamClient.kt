package com.dpflix.android.network

import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Client Xtream Codes (§4.2 Étape 2a, §4.6, §7 étape 3 du cahier des charges).
 *
 * Rôle : authentification + récupération des chaînes live via l'API `player_api.php`,
 * exposées sous forme de [Channel] — le même modèle que produit [com.dpflix.android.parser.M3uParser],
 * pour que l'accueil (§4.4) et le lecteur n'aient jamais à distinguer la provenance
 * d'une chaîne.
 *
 * Contrairement à `M3uParser` (fonction pure), ce client fait forcément de l'IO réseau :
 * l'authentification Xtream n'est pas un simple parsing de texte déjà récupéré, elle
 * nécessite d'interroger le serveur. Les fonctions sont `suspend` et s'exécutent sur
 * `Dispatchers.IO`.
 *
 * Volontairement hors périmètre à cette sous-étape (comme pour 3b) :
 * - l'usage de `includeTvChannels` (case à cocher §4.2) : ce client récupère toujours
 *   les chaînes live si on le lui demande, c'est à la couche repository (étape 4) de
 *   décider d'appeler [fetchLiveChannels] ou non selon la playlist ;
 * - VOD / séries : hors périmètre du projet (§1, §4.2).
 */
class XtreamClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        // Fix (2026-07-25) : certains panels (ex. constatés depuis 11000 Chelles) mettent
        // jusqu'à une minute à établir la connexion — parfois lors de CHAQUE tentative de
        // la cascade User-Agent (executeGet peut donc légitimement prendre plusieurs
        // minutes bout en bout pour un seul appel `player_api.php`). Les anciens délais
        // (20s/45s/20s) faisaient donc échouer prématurément des panels par ailleurs
        // valides, juste lents à répondre. Nouveaux délais généreux, chacun dépassant
        // 2 minutes : mieux vaut laisser l'utilisateur attendre (avec un indicateur de
        // chargement côté UI) que déclarer un panel valide injoignable.
        .connectTimeout(150, TimeUnit.SECONDS)
        .readTimeout(150, TimeUnit.SECONDS)
        .writeTimeout(150, TimeUnit.SECONDS)
        // callTimeout = budget total (connexion + écriture + lecture + éventuelles
        // redirections) pour un appel donné ; volontairement encore plus large que chacun
        // des délais individuels ci-dessus pour ne jamais couper une requête par ce
        // timeout global avant que les délais spécifiques n'aient eu leur chance.
        .callTimeout(240, TimeUnit.SECONDS)
        // Un panel lent à connecter l'est souvent de façon intermittente (serveur
        // mutualisé/surchargé) : retenter automatiquement la connexion TCP au lieu
        // d'abandonner sur le premier échec augmente les chances d'aboutir sans même
        // solliciter la cascade de User-Agent.
        .retryOnConnectionFailure(true)
        // Force HTTP/1.1 (2026-07-25) : beaucoup de panels Xtream tournent derrière un
        // reverse-proxy ou un serveur PHP/nginx ancien/mal configuré dont la négociation
        // HTTP/2 échoue silencieusement ou produit des réponses tronquées, alors que le
        // même serveur répond normalement en HTTP/1.1 (le protocole que parlent
        // naturellement ces panels). Option la plus permissive : ne pas risquer une
        // négociation HTTP/2 qu'un panel bricolé gère mal.
        .protocols(listOf(Protocol.HTTP_1_1))
        // Specs de connexion les plus permissives possible pour TLS (2026-07-25) : en
        // plus du TrustManager/HostnameVerifier permissifs ci-dessous (qui gèrent la
        // confiance du certificat), COMPATIBLE_TLS accepte des versions TLS et suites de
        // chiffrement plus anciennes que MODERN_TLS (le défaut OkHttp) — nécessaire pour
        // les panels tournant sur de vieilles piles OpenSSL. CLEARTEXT reste nécessaire
        // pour les panels en simple http://.
        .connectionSpecs(listOf(ConnectionSpec.COMPATIBLE_TLS, ConnectionSpec.CLEARTEXT))
        .followRedirects(true)
        .followSslRedirects(true)
        // TLS permissif (2026-07-22, étendu 2026-07-25) : mêmes raisons que
        // IptvHttpDataSourceFactory — certains panels servent un certificat auto-signé/
        // invalide sur player_api.php lui-même, ce qui ferait échouer l'authentification
        // avant même d'arriver à la lecture du flux vidéo. Voir PermissiveTls pour le
        // compromis assumé et la prise en charge des vieilles versions TLS (1.0/1.1).
        .sslSocketFactory(PermissiveTls.sslSocketFactory, PermissiveTls.trustManager)
        .hostnameVerifier(PermissiveTls.hostnameVerifier)
        .build()
) {

    /**
     * Authentifie les [credentials] auprès du serveur (appel de base de `player_api.php`,
     * sans paramètre `action`). Utilisé par le formulaire d'onboarding (§4.2 Étape 2a)
     * pour valider la saisie avant d'enregistrer la playlist.
     */
    suspend fun authenticate(credentials: XtreamCredentials): XtreamResult<XtreamUserInfo> =
        withContext(Dispatchers.IO) {
            when (val outcome = executeGet(playerApiUrl(credentials))) {
                is GetOutcome.NetworkError -> XtreamResult.NetworkError(outcome.message)
                is GetOutcome.HttpError -> XtreamResult.ServerError(httpErrorMessage(outcome.code))
                is GetOutcome.Body -> parseAuthBody(outcome.text)
            }
        }

    /**
     * Récupère les chaînes live du compte (`get_live_categories` + `get_live_streams`),
     * après vérification de l'authentification. Retourne les mêmes types d'erreur que
     * [authenticate] en cas d'échec, pour un traitement UI uniforme.
     *
     * @param playlistId id de la [com.dpflix.android.model.Playlist] à laquelle rattacher les chaînes produites.
     */
    suspend fun fetchLiveChannels(
        credentials: XtreamCredentials,
        playlistId: String
    ): XtreamResult<XtreamLiveChannelsData> = withContext(Dispatchers.IO) {
        try {
            val authResult = when (val outcome = executeGet(playerApiUrl(credentials))) {
                is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
                is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
                is GetOutcome.Body -> parseAuthBody(outcome.text)
            }
            if (authResult !is XtreamResult.Success) {
                @Suppress("UNCHECKED_CAST")
                return@withContext authResult as XtreamResult<XtreamLiveChannelsData>
            }

            val categoryNames = when (
                val outcome = executeGet(
                    playerApiUrl(credentials, action = "get_live_categories"),
                    continueOnEmptyArray = true
                )
            ) {
                is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
                is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
                is GetOutcome.Body -> parseCategories(outcome.text)
            }

            var (channels, rawStreamCount) = when (
                val outcome = executeGet(
                    playerApiUrl(credentials, action = "get_live_streams"),
                    continueOnEmptyArray = true
                )
            ) {
                is GetOutcome.NetworkError -> return@withContext XtreamResult.NetworkError(outcome.message)
                is GetOutcome.HttpError -> return@withContext XtreamResult.ServerError(httpErrorMessage(outcome.code))
                is GetOutcome.Body -> parseLiveStreams(outcome.text, credentials, playlistId, categoryNames)
                    ?: return@withContext XtreamResult.ServerError(unparsableStreamsMessage(outcome.text))
            }

            // Fix (2026-07-25) : repli par catégorie pour les gros panels. Certains panels
            // Xtream (vus sur des comptes à très nombreuses chaînes) répondent volontairement
            // "[]" à `get_live_streams` SANS `category_id` — même après la cascade de
            // User-Agent et le fix "catalogue restreint" ci-dessus — pour éviter de servir
            // des dizaines de milliers d'entrées en un seul appel non filtré, mais répondent
            // normalement dès qu'on précise une catégorie. Sans ce repli, ces panels
            // affichaient "0 chaîne" alors que le compte est parfaitement valide et chargé.
            // Déclenché UNIQUEMENT si l'appel global n'a strictement rien renvoyé (pas de
            // fausse activation sur un compte réellement vide sans catégories) ; requêtes
            // séquentielles catégorie par catégorie, acceptables ici car ce chemin ne
            // s'exécute que quand le chemin rapide a déjà échoué.
            if (channels.isEmpty() && rawStreamCount == 0 && categoryNames.isNotEmpty()) {
                val perCategoryChannels = mutableListOf<Channel>()
                var perCategoryRawCount = 0
                for (categoryId in categoryNames.keys) {
                    val categoryOutcome = executeGet(
                        playerApiUrl(credentials, action = "get_live_streams", categoryId = categoryId),
                        continueOnEmptyArray = true
                    )
                    val (categoryChannels, categoryRawCount) = when (categoryOutcome) {
                        is GetOutcome.Body -> parseLiveStreams(categoryOutcome.text, credentials, playlistId, categoryNames)
                            ?: continue // Catégorie illisible isolément : ignorée, pas fatale pour les autres.
                        else -> continue // Erreur réseau/HTTP isolée à cette catégorie : idem.
                    }
                    perCategoryChannels += categoryChannels
                    perCategoryRawCount += categoryRawCount
                }
                if (perCategoryChannels.isNotEmpty()) {
                    channels = perCategoryChannels
                    rawStreamCount = perCategoryRawCount
                }
            }

            XtreamResult.Success(
                XtreamLiveChannelsData(
                    channels = channels,
                    rawStreamCount = rawStreamCount
                )
            )
        } catch (e: OutOfMemoryError) {
            // Panel à très nombreuses chaînes (11 000-20 000+) : `get_live_streams` sans
            // `category_id` renvoie alors un JSON de plusieurs dizaines de Mo, chargé
            // intégralement en mémoire par `body?.bytes()`
            // (executeGetWithUserAgentCascade) PUIS reconstruit en objets
            // `JSONArray`/`JSONObject` (parseLiveStreams) — org.json double/triple
            // facilement la taille mémoire par rapport au texte brut. OutOfMemoryError
            // est une `Error`, pas une `Exception` : aucun des `catch` existants
            // (IOException/IllegalArgumentException/JSONException) ne l'interceptait, donc
            // elle remontait non rattrapée hors de la coroutine
            // `viewModelScope.launch` d'OnboardingViewModel.submitXtream et tuait tout le
            // process — APRÈS que la playlist avait déjà été insérée en base
            // (`appRepository.playlists.addPlaylist`, avant cet appel), mais AVANT que
            // `refreshChannels` n'ait pu persister la moindre chaîne : exactement le
            // symptôme "Xtream Codes charge mais affiche zéro chaîne" — l'app relancée
            // retrouve la playlist déjà là, toujours vide.
            //
            // Repli : chaque appel `get_live_streams&category_id=...` ne charge qu'une
            // fraction du catalogue à la fois, largement sous la pression mémoire qui a
            // fait échouer l'appel global non filtré.
            fetchLiveChannelsByCategoryOnly(credentials, playlistId) ?: XtreamResult.ServerError(
                "Ce panel a trop de chaînes pour être chargé en une seule fois " +
                    "(mémoire insuffisante sur l'appareil), et le repli par catégorie a " +
                    "échoué aussi. Réessayez, ou signalez ce panel pour qu'on ajuste l'app."
            )
        }
    }

    /**
     * Repli déclenché après un [OutOfMemoryError] sur l'appel global de [fetchLiveChannels] :
     * ré-authentification déjà faite par l'appelant, on reprend directement aux
     * catégories puis construit le catalogue catégorie par catégorie — chaque réponse
     * individuelle est bien plus petite que le flux complet et reste sous la pression
     * mémoire qui a fait échouer le premier essai.
     *
     * @return `null` si ce second essai échoue aussi (catégories introuvables, ou même
     * une catégorie individuelle sature encore la mémoire sur un panel extrême) —
     * l'appelant retombe alors sur un message d'erreur explicite plutôt que de masquer
     * l'échec par un succès vide indiscernable d'un compte réellement sans chaînes.
     */
    private suspend fun fetchLiveChannelsByCategoryOnly(
        credentials: XtreamCredentials,
        playlistId: String
    ): XtreamResult<XtreamLiveChannelsData>? {
        return try {
            val categoryNames = when (
                val outcome = executeGet(
                    playerApiUrl(credentials, action = "get_live_categories"),
                    continueOnEmptyArray = true
                )
            ) {
                is GetOutcome.Body -> parseCategories(outcome.text)
                else -> return null
            }
            if (categoryNames.isEmpty()) return null

            val channels = mutableListOf<Channel>()
            var rawStreamCount = 0
            for (categoryId in categoryNames.keys) {
                val categoryOutcome = executeGet(
                    playerApiUrl(credentials, action = "get_live_streams", categoryId = categoryId),
                    continueOnEmptyArray = true
                )
                val (categoryChannels, categoryRawCount) = when (categoryOutcome) {
                    is GetOutcome.Body -> parseLiveStreams(categoryOutcome.text, credentials, playlistId, categoryNames)
                        ?: continue
                    else -> continue
                }
                channels += categoryChannels
                rawStreamCount += categoryRawCount
            }
            if (channels.isEmpty()) return null

            XtreamResult.Success(
                XtreamLiveChannelsData(
                    channels = channels,
                    rawStreamCount = rawStreamCount
                )
            )
        } catch (e: OutOfMemoryError) {
            // Même une catégorie individuelle peut en théorie être énorme sur un panel
            // extrême : on abandonne proprement plutôt que de boucler indéfiniment sur
            // les catégories restantes avec une mémoire déjà sous pression.
            null
        }
    }

    /**
     * Étape R2 (replay/catch-up) : programmes déjà diffusés (+ en cours/à venir, filtrés par
     * l'appelant) pour une chaîne à catch-up donnée. Appel isolé, indépendant de
     * [fetchLiveChannels] — pas de ré-authentification ici (contrairement à
     * [fetchLiveChannels], cet appel est fait ponctuellement pour UNE chaîne déjà connue,
     * pas au (re)chargement de toute la playlist), donc pas d'`InvalidCredentials`/
     * `AccountInactive` possible en retour : un identifiant erroné remontera comme
     * [XtreamResult.ServerError] ou une liste vide selon ce que répond le panel.
     *
     * Deux actions Xtream tentées dans l'ordre (comme discuté à l'Étape R2 du cahier des
     * charges) :
     * - `get_short_epg` d'abord : la plus légère, supportée par la quasi-totalité des
     *   panels, mais certains ne renvoient par ce biais qu'une poignée d'entrées centrées
     *   sur le direct (programme en cours + quelques suivants), jamais de passé.
     * - `get_simple_data_table` en repli, UNIQUEMENT si le premier appel n'a rien donné
     *   d'exploitable : grille complète du jour pour ce `stream_id` (passé + futur) chez
     *   les panels qui la supportent. On ne le tente pas systématiquement pour ne pas
     *   doubler le nombre de requêtes sur les panels où `get_short_epg` suffit déjà.
     */
    suspend fun fetchShortEpg(
        credentials: XtreamCredentials,
        streamId: String,
        limit: Int = SHORT_EPG_LIMIT
    ): XtreamResult<List<ReplayProgram>> = withContext(Dispatchers.IO) {
        val shortEpgOutcome = executeGet(
            playerApiUrl(credentials, action = "get_short_epg", streamId = streamId, limit = limit)
        )
        val shortPrograms = (shortEpgOutcome as? GetOutcome.Body)?.let { parseEpgListings(it.text) }
        if (!shortPrograms.isNullOrEmpty()) {
            return@withContext XtreamResult.Success(shortPrograms)
        }

        when (
            val simpleOutcome = executeGet(
                playerApiUrl(credentials, action = "get_simple_data_table", streamId = streamId)
            )
        ) {
            is GetOutcome.NetworkError -> if (shortEpgOutcome is GetOutcome.NetworkError) {
                XtreamResult.NetworkError(simpleOutcome.message)
            } else {
                // Le premier appel a au moins joint le serveur (Body ou HttpError) : le
                // replay est simplement indisponible pour cette chaîne côté panel, pas une
                // panne réseau — succès vide plutôt qu'une erreur trompeuse.
                XtreamResult.Success(emptyList())
            }
            is GetOutcome.HttpError -> XtreamResult.ServerError(httpErrorMessage(simpleOutcome.code))
            is GetOutcome.Body -> XtreamResult.Success(parseEpgListings(simpleOutcome.text) ?: emptyList())
        }
    }

    /**
     * URL de flux jouable pour une chaîne live, au format standard Xtream
     * `/live/{user}/{pass}/{streamId}.{ext}`. Extension par défaut `m3u8` (HLS,
     * cohérent avec le choix ExoPlayer/Media3 du §2) ; le serveur peut annoncer une
     * autre extension via `container_extension` dans `get_live_streams`.
     */
    fun buildStreamUrl(
        credentials: XtreamCredentials,
        streamId: String,
        containerExtension: String = DEFAULT_STREAM_EXTENSION
    ): String {
        val ext = containerExtension.trim().trimStart('.').ifBlank { DEFAULT_STREAM_EXTENSION }
        // Fix (2026-07-23) : encodePathSegment (Uri.encode), pas encode (URLEncoder) - ici
        // username/password sont inseres dans le CHEMIN de l'URL (/live/{user}/{pass}/...),
        // pas une query string. URLEncoder.encode encode un espace en "+", valide seulement
        // en query string (application/x-www-form-urlencoded) - dans un segment de chemin,
        // ce "+" reste un caractere litteral pour la plupart des serveurs, jamais decode en
        // espace : un identifiant Xtream contenant un espace ou certains caracteres
        // speciaux produisait donc une URL de flux invalide (chaine injouable), alors que
        // playerApiUrl (query string) n'etait lui pas concerne.
        return "${baseUrl(credentials.serverUrl)}/live/${encodePathSegment(credentials.username)}/${encodePathSegment(credentials.password)}/$streamId.$ext"
    }

    /**
     * Étape R3 (replay) : URL de lecture en différé pour un point précis dans le temps,
     * au format `timeshift.php/{user}/{pass}/{durée}/{date:heure}/{stream_id}.{ext}` — même
     * convention de chemin que [buildStreamUrl] pour `{user}`/`{pass}` (voir sa doc pour le
     * choix d'`encodePathSegment` plutôt que `encode`).
     *
     * - `{durée}` : minutes ENTIÈRES (convention Xtream), [durationMinutes] arrondi au moins
     *   à 1 — jamais 0, qui ne jouerait rien.
     * - `{date:heure}` : format `yyyy-MM-dd:HH-mm`, [startMillis] formaté dans le fuseau
     *   horaire PAR DÉFAUT DE L'APPAREIL — même convention assumée que le repli de parsing
     *   `start`/`end` de l'Étape R2 (voir `epgMillis`). C'est le point le plus susceptible de
     *   nécessiter un ajustement une fois testé sur un panel réel (voir le test VLC de cette
     *   étape) : si l'heure jouée est décalée d'un nombre rond d'heures par rapport au
     *   programme demandé, c'est probablement que le panel attend l'heure serveur (pas
     *   forcément celle de l'appareil) dans ce champ.
     *
     * Fonction pure (pas de suspend, pas d'IO) : peut être appelée directement depuis la
     * couche UI pour composer l'URL, puis testée telle quelle (copier/coller dans VLC) sans
     * dépendre du reste de l'app.
     */
    fun buildTimeshiftUrl(
        credentials: XtreamCredentials,
        streamId: String,
        startMillis: Long,
        durationMinutes: Int,
        containerExtension: String = DEFAULT_STREAM_EXTENSION
    ): String {
        val ext = containerExtension.trim().trimStart('.').ifBlank { DEFAULT_STREAM_EXTENSION }
        val safeDurationMinutes = durationMinutes.coerceAtLeast(1)
        val dateTime = java.text.SimpleDateFormat("yyyy-MM-dd:HH-mm", java.util.Locale.US)
            .format(java.util.Date(startMillis))
        return "${baseUrl(credentials.serverUrl)}/timeshift.php/${encodePathSegment(credentials.username)}/" +
            "${encodePathSegment(credentials.password)}/$safeDurationMinutes/$dateTime/$streamId.$ext"
    }

    /**
     * Variante pratique de [buildTimeshiftUrl] à partir d'un [ReplayProgram] déjà connu
     * (Étape R2, `XtreamClient.fetchShortEpg`/`ReplayRepository.fetchPastPrograms`) : calcule
     * la durée en minutes depuis `startMillis`/`endMillis` (arrondie au SUPÉRIEUR, pour ne
     * jamais couper la fin réelle du programme par un arrondi trop court) plutôt que de la
     * faire recalculer par chaque appelant.
     */
    fun buildTimeshiftUrl(
        credentials: XtreamCredentials,
        streamId: String,
        program: ReplayProgram,
        containerExtension: String = DEFAULT_STREAM_EXTENSION
    ): String {
        val durationMinutes = kotlin.math.ceil(
            (program.endMillis - program.startMillis) / 60_000.0
        ).toInt()
        return buildTimeshiftUrl(credentials, streamId, program.startMillis, durationMinutes, containerExtension)
    }

    // --- Requête HTTP ---------------------------------------------------------------

    private fun playerApiUrl(
        credentials: XtreamCredentials,
        action: String? = null,
        categoryId: String? = null,
        streamId: String? = null,
        limit: Int? = null
    ): String {
        val builder = StringBuilder(baseUrl(credentials.serverUrl))
            .append("/player_api.php")
            .append("?username=").append(encode(credentials.username))
            .append("&password=").append(encode(credentials.password))
        if (action != null) {
            builder.append("&action=").append(action)
        }
        if (categoryId != null) {
            builder.append("&category_id=").append(encode(categoryId))
        }
        if (streamId != null) {
            builder.append("&stream_id=").append(encode(streamId))
        }
        if (limit != null) {
            builder.append("&limit=").append(limit)
        }
        return builder.toString()
    }

    /**
     * Point d'entrée réseau unique de la classe : ajoute un fallback de schéma
     * (2026-07-25) par-dessus la cascade de User-Agent de [executeGetWithUserAgentCascade].
     *
     * Beaucoup d'utilisateurs collent une adresse sans schéma (§`baseUrl`, qui suppose
     * alors `http://` par défaut) alors que le panel n'accepte en réalité QUE du https
     * (reverse-proxy, panel derrière Cloudflare...) — et inversement, certains panels
     * fournis en https:// par le revendeur ne répondent en fait qu'en clair sur le même
     * port. Dans les deux cas, la première tentative échoue au niveau connexion/TLS
     * (jamais un simple code d'erreur HTTP, qui lui indique un serveur bien joignable) :
     * on retente alors une fois avec le schéma opposé avant d'abandonner.
     */
    private fun executeGet(url: String, continueOnEmptyArray: Boolean = false): GetOutcome {
        val firstAttempt = executeGetWithUserAgentCascade(url, continueOnEmptyArray)
        if (firstAttempt !is GetOutcome.NetworkError) return firstAttempt

        val alternateUrl = swapScheme(url) ?: return firstAttempt
        val secondAttempt = executeGetWithUserAgentCascade(alternateUrl, continueOnEmptyArray)
        // Ne garde le 2e essai que s'il apporte une vraie réponse serveur (Body ou même
        // HttpError, qui prouve au moins que ce schéma-là joint le serveur) ; sinon on
        // remonte l'erreur du tout premier essai, plus représentative de la cause réelle.
        return if (secondAttempt is GetOutcome.NetworkError) firstAttempt else secondAttempt
    }

    /** `http://` -> `https://` ou l'inverse ; `null` si l'URL n'a pas l'un de ces deux schémas. */
    private fun swapScheme(url: String): String? = when {
        url.startsWith("https://", ignoreCase = true) -> "http://" + url.removePrefix("https://")
        url.startsWith("http://", ignoreCase = true) -> "https://" + url.removePrefix("http://")
        else -> null
    }

    private fun executeGetWithUserAgentCascade(url: String, continueOnEmptyArray: Boolean = false): GetOutcome = try {
        // Cascade de User-Agent (2026-07-22, voir NetworkConstants.USER_AGENT_FALLBACKS) :
        // remplace l'ancien header unique forcé "IPTVSmartersPlayer" — on essaie d'abord
        // sans en-tête personnalisé, puis les signatures connues, jusqu'à obtenir une
        // réponse exploitable.
        // Fix (2026-07-25) : `isSuccessful` seul ne suffit PAS comme critère d'arrêt pour
        // get_live_categories/get_live_streams (continueOnEmptyArray = true côté appelant).
        // Beaucoup de panels Xtream (fréquent chez les revendeurs) ne bloquent pas un
        // User-Agent non reconnu par un code d'erreur franc : ils répondent 200 avec un
        // tableau JSON vide ("[]"), un "catalogue restreint" plutôt qu'un vrai refus. Sans
        // ce fix, la cascade s'arrêtait dès ce premier 200 "poli" et n'essayait jamais
        // IPTVSmartersPlayer/VLC/TiviMate, qui auraient débloqué le vrai catalogue. On
        // continue donc la cascade tant que le corps est un tableau JSON vide ; si TOUTES
        // les tentatives renvoient [], lastOutcome contient quand même ce dernier [] — un
        // compte réellement sans chaînes/catégories reste géré normalement en aval
        // (voir parseLiveStreams/parseCategories).
        var lastOutcome: GetOutcome = GetOutcome.NetworkError("Aucune tentative effectuée")
        for (userAgent in NetworkConstants.USER_AGENT_FALLBACKS) {
            val requestBuilder = Request.Builder().url(url).get()
            if (userAgent != null) requestBuilder.header("User-Agent", userAgent)
            val outcome = httpClient.newCall(requestBuilder.build()).execute().use { response ->
                if (!response.isSuccessful) {
                    GetOutcome.HttpError(response.code)
                } else {
                    val body = response.body
                    val bytes = body?.bytes() ?: ByteArray(0)
                    val declaredCharset = RobustTextDecoder.charsetFromContentType(body?.contentType()?.toString())
                    GetOutcome.Body(RobustTextDecoder.decode(bytes, declaredCharset))
                }
            }
            lastOutcome = outcome
            if (outcome is GetOutcome.Body) {
                if (continueOnEmptyArray && isEmptyJsonArray(outcome.text)) {
                    continue
                }
                break
            }
        }
        lastOutcome
    } catch (e: IOException) {
        GetOutcome.NetworkError(e.message ?: "Erreur réseau")
    } catch (e: IllegalArgumentException) {
        // URL malformée (adresse serveur invalide saisie par l'utilisateur, §4.2).
        GetOutcome.NetworkError(e.message ?: "Adresse de serveur invalide")
    }

    /**
     * Détecte un corps `"[]"` (éventuellement entouré d'espaces) : le cas "catalogue
     * restreint" décrit ci-dessus. Un corps non-tableau (objet d'erreur, HTML...) ou un
     * tableau non vide renvoie `false` — on ne veut continuer la cascade QUE sur ce cas
     * précis, pas masquer d'autres formes de réponse qui doivent être traitées ailleurs
     * (voir parseLiveStreams pour la gestion de "{}"/vide/"null").
     */
    private fun isEmptyJsonArray(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        return try {
            JSONArray(trimmed).length() == 0
        } catch (e: JSONException) {
            false
        }
    }

    private sealed class GetOutcome {
        data class Body(val text: String) : GetOutcome()
        data class HttpError(val code: Int) : GetOutcome()
        data class NetworkError(val message: String) : GetOutcome()
    }

    private fun httpErrorMessage(code: Int) = "Le serveur a répondu avec le code $code"

    // --- Parsing JSON (tolérant : l'API Xtream mélange types string/int selon les panels) ---

    private fun parseAuthBody(body: String): XtreamResult<XtreamUserInfo> {
        val json = try {
            JSONObject(body)
        } catch (e: JSONException) {
            return XtreamResult.ServerError("Réponse du serveur illisible (JSON invalide)")
        }

        val userInfo = json.optJSONObject("user_info")
            ?: return XtreamResult.InvalidCredentials()

        if (userInfo.optIntFlexible("auth") != 1) {
            return XtreamResult.InvalidCredentials()
        }

        val status = userInfo.optString("status", "Active").ifBlank { "Active" }
        if (!status.equals("Active", ignoreCase = true)) {
            return XtreamResult.AccountInactive(status)
        }

        val expDateSeconds = userInfo.optStringOrNull("exp_date")?.toLongOrNull()

        return XtreamResult.Success(
            XtreamUserInfo(
                username = userInfo.optString("username"),
                status = status,
                expDateMillis = expDateSeconds?.times(1000L),
                isTrial = userInfo.optIntFlexible("is_trial") == 1,
                maxConnections = userInfo.optStringOrNull("max_connections")?.toIntOrNull()
            )
        )
    }

    private fun parseCategories(body: String): Map<String, String> = try {
        val array = JSONArray(body)
        buildMap {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optStringOrNull("category_id") ?: continue
                val name = obj.optStringOrNull("category_name") ?: continue
                put(id, name)
            }
        }
    } catch (e: JSONException) {
        emptyMap()
    }

    private fun parseLiveStreams(
        body: String,
        credentials: XtreamCredentials,
        playlistId: String,
        categoryNames: Map<String, String>
    ): Pair<List<Channel>, Int>? {
        val trimmed = body.trim()
        // Certains panels renvoient "{}" ou une chaîne vide quand le compte n'a
        // simplement aucune chaîne live (plutôt que "[]") : ce n'est pas une erreur.
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed.equals("null", ignoreCase = true)) {
            return emptyList<Channel>() to 0
        }

        val array = try {
            JSONArray(trimmed)
        } catch (e: JSONException) {
            // Le serveur peut répondre par un objet JSON (page d'erreur/auth du panel,
            // action non reconnue...) plutôt qu'un tableau, ou par du HTML/texte brut
            // (mauvais port, reverse-proxy, etc.) : dans les deux cas, ce n'est PAS la
            // même chose qu'"aucune chaîne" et l'appelant doit pouvoir le distinguer
            // (voir [XtreamClient.fetchLiveChannels]) plutôt que de recevoir silencieusement
            // une liste vide indiscernable d'un compte réellement sans chaîne.
            return null
        }

        val channels = mutableListOf<Channel>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val streamId = obj.optStringOrNull("stream_id") ?: continue
            val containerExtension = obj.optStringOrNull("container_extension") ?: DEFAULT_STREAM_EXTENSION
            val categoryId = obj.optStringOrNull("category_id")
            val sequentialNumber = i + 1

            // Étape R1 (replay/catch-up) : `tv_archive` vaut "1"/"0" chez la plupart des
            // panels, mais certains renvoient un entier JSON natif plutôt qu'une chaîne —
            // optIntFlexible (déjà utilisé pour `num` ci-dessous) couvre les deux cas.
            // `tv_archive_duration` est le nombre de jours d'historique conservés ; absent
            // ou 0 chez un panel qui annonce `tv_archive=1` sans préciser la durée ne doit
            // pas être confondu avec "pas de replay" (tvArchive reste déterminé par
            // tv_archive seul) — mais un `tv_archive_duration` de 0 associé à
            // `tv_archive=1` n'apporte aucune borne exploitable pour l'Étape R2 : ramené à
            // `null` dans ce cas plutôt que de propager un "0 jour" trompeur.
            val tvArchive = obj.optIntFlexible("tv_archive") == 1
            val tvArchiveDurationDays = obj.optIntFlexible("tv_archive_duration")
                .takeIf { tvArchive && it > 0 }

            channels += Channel(
                // Id déterministe (plutôt qu'un UUID aléatoire comme M3uParser) : un
                // rafraîchissement Xtream doit retrouver la même chaîne pour ne pas
                // perdre la numérotation personnalisée (§5.3) ou la dernière chaîne
                // regardée (§4.3) qui lui sont associées ailleurs (étape 4).
                id = "xtream-$playlistId-$streamId",
                playlistId = playlistId,
                name = obj.optStringOrNull("name") ?: "Chaîne $streamId",
                streamUrl = buildStreamUrl(credentials, streamId, containerExtension),
                logoUrl = obj.optStringOrNull("stream_icon"),
                category = categoryId?.let { categoryNames[it] },
                tvgId = obj.optStringOrNull("epg_channel_id"),
                originalNumber = obj.optIntFlexible("num").takeIf { it > 0 } ?: sequentialNumber,
                tvArchive = tvArchive,
                tvArchiveDurationDays = tvArchiveDurationDays,
                // Retenu séparément de streamUrl (voir la doc de Channel.xtreamStreamId) :
                // l'URL de replay (Étape R3) aura besoin de ce même stream_id sur un chemin
                // différent (timeshift.php), pas la peine de re-parser streamUrl plus tard.
                xtreamStreamId = streamId
            )
        }
        return channels to array.length()
    }

    /**
     * Parse la réponse `get_short_epg`/`get_simple_data_table` (même clé `epg_listings`
     * chez les deux actions) en [ReplayProgram]. `null` = réponse illisible (distinct
     * d'une liste vide légitime), pour laisser [fetchShortEpg] décider s'il faut tenter le
     * repli sur l'autre action.
     */
    private fun parseEpgListings(body: String): List<ReplayProgram>? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed == "{}" || trimmed.equals("null", ignoreCase = true)) {
            return emptyList()
        }

        val listings = try {
            JSONObject(trimmed).optJSONArray("epg_listings")
        } catch (e: JSONException) {
            // Repli : certains panels renvoient directement un tableau, sans objet
            // enveloppe `{"epg_listings": [...]}`.
            try {
                JSONArray(trimmed)
            } catch (e2: JSONException) {
                null
            }
        } ?: return null

        val programs = mutableListOf<ReplayProgram>()
        for (i in 0 until listings.length()) {
            val obj = listings.optJSONObject(i) ?: continue
            val title = obj.optStringOrNull("title")?.let { decodeEpgText(it) } ?: continue
            val startMillis = epgMillis(obj, "start_timestamp", "start")
            val endMillis = epgMillis(obj, "stop_timestamp", "end")
            if (startMillis == null || endMillis == null || endMillis <= startMillis) continue
            programs += ReplayProgram(title = title, startMillis = startMillis, endMillis = endMillis)
        }
        return programs
    }

    /**
     * Horodatage d'un champ EPG : essaie d'abord [timestampKey] (epoch secondes, présent
     * chez la plupart des panels — insensible au fuseau horaire), puis retombe sur
     * [dateTimeKey] (chaîne `"yyyy-MM-dd HH:mm:ss"`, interprétée dans le fuseau horaire par
     * défaut de l'appareil faute de mieux — un panel qui n'expose QUE ce format sans
     * timestamp n'indique de toute façon jamais le sien).
     */
    private fun epgMillis(obj: JSONObject, timestampKey: String, dateTimeKey: String): Long? {
        obj.optStringOrNull(timestampKey)?.toLongOrNull()?.let { return it * 1000L }
        val dateTime = obj.optStringOrNull(dateTimeKey) ?: return null
        return try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).parse(dateTime)?.time
        } catch (e: java.text.ParseException) {
            null
        }
    }

    /**
     * Le titre d'un programme EPG Xtream est très souvent encodé en base64 (comme pour
     * l'EPG XMLTV classique, voir `EpgXmlParser`) — mais pas systématiquement selon le
     * panel. `Base64.decode` rejette (IllegalArgumentException) tout ce qui n'est pas un
     * base64 valide (espace, accents, longueur non multiple de 4...), ce qui couvre en
     * pratique la plupart des titres déjà en clair : on garde alors la valeur brute plutôt
     * que de perdre le titre. À vérifier sur un panel réel (voir README) : un panel qui
     * encoderait un titre déjà "base64-compatible" par coïncidence resterait mal décodé.
     */
    private fun decodeEpgText(raw: String): String = try {
        String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
            .trim()
            .ifBlank { raw.trim() }
    } catch (e: IllegalArgumentException) {
        raw.trim()
    }

    /**
     * Message d'erreur affiché quand `get_live_streams` ne renvoie pas un JSON
     * exploitable : inclut un extrait de la réponse brute pour permettre à
     * l'utilisateur (ou à nous, en debug) d'identifier la vraie cause (page d'erreur
     * HTML du panel, mauvais port, action bloquée par un reverse-proxy...) plutôt que
     * de se retrouver avec un simple "0 chaînes" sans explication.
     */
    private fun unparsableStreamsMessage(rawBody: String): String {
        val snippet = rawBody.trim().take(120).ifBlank { "(réponse vide)" }
        return "Réponse du serveur illisible pour la liste des chaînes : $snippet"
    }

    // Fix (robustesse "n'importe quel panel") : beaucoup d'utilisateurs collent une
    // adresse de serveur sans schéma ("monpanel.com:8080") ou en copiant carrément un
    // lien complet déjà fourni par leur revendeur (avec /player_api.php, /get.php ou une
    // query string en trop) — sans schéma, OkHttp lève IllegalArgumentException avant
    // même la première requête ("expected scheme") ; avec un chemin/une query en trop,
    // playerApiUrl produirait une URL invalide (double player_api.php, ?username=...
    // dupliqué). On normalise donc une bonne fois ici, seul point de passage commun à
    // authenticate/fetchLiveChannels/buildStreamUrl.
    private fun baseUrl(server: String): String {
        var normalized = server.trim().trimEnd('/')
        // Schéma manquant : http:// par défaut (le cas très largement majoritaire pour
        // ces panels ; l'utilisateur reste libre de saisir explicitement https://).
        if (!normalized.contains("://")) {
            normalized = "http://$normalized"
        }
        // Retire un chemin d'API ou une query string collés par erreur avec l'hôte,
        // pour ne garder que schéma+hôte+port.
        normalized = normalized.substringBefore("?")
        normalized = Regex("""(?i)/(player_api|get)\.php.*$""").replace(normalized, "")
        return normalized.trimEnd('/')
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    /** Encodage correct pour un segment de CHEMIN d'URL (contrairement à [encode], prévu
     *  pour une query string) - voir la doc de [buildStreamUrl] pour le bug que ça corrige.
     *  `android.net.Uri.encode` encode un espace en "%20", jamais en "+". */
    private fun encodePathSegment(value: String): String = android.net.Uri.encode(value)

    /** Lit un champ pouvant être un `Int`, un `Boolean` ou une `String` selon le panel Xtream. */
    private fun JSONObject.optIntFlexible(key: String): Int {
        if (!has(key) || isNull(key)) return 0
        return when (val value = get(key)) {
            is Int -> value
            is Boolean -> if (value) 1 else 0
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }
    }

    /** Lit un champ texte en normalisant les valeurs absentes/vides/`null` JSON en `null` Kotlin. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        return optString(key, "").takeIf { it.isNotBlank() }
    }

    private companion object {
        const val DEFAULT_STREAM_EXTENSION = "m3u8"

        /** Nombre d'entrées demandées à `get_short_epg` (Étape R2) — généreux : la plupart
         *  des panels annoncent quelques jours d'historique (voir `Channel.tvArchiveDurationDays`,
         *  Étape R1), largement plus que le nombre par défaut (souvent 4) que renverrait
         *  un appel sans `limit`. */
        const val SHORT_EPG_LIMIT = 200
    }
}
