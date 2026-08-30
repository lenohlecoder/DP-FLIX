package com.dpflix.android.onboarding

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.dpflix.android.model.Playlist
import com.dpflix.android.model.PlaylistType
import com.dpflix.android.network.XtreamClient
import com.dpflix.android.network.XtreamCredentials
import com.dpflix.android.network.XtreamResult
import com.dpflix.android.parser.M3uParser
import com.dpflix.android.repository.AddPlaylistResult
import com.dpflix.android.repository.AppRepository
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Logique de l'écran d'onboarding (§4.2 du cahier des charges, étape 6b).
 *
 * Orchestre ce que [XtreamClient] et [M3uParser] documentent explicitement comme hors de
 * leur périmètre respectif : décider s'il faut appeler `fetchLiveChannels` selon la case
 * "Inclure les chaînes de télévision" (voir la doc de `XtreamClient`), et récupérer le
 * contenu texte (téléchargement URL ou lecture fichier local) avant de le passer à
 * `M3uParser.parse` (fonction pure, voir sa doc). Cette orchestration n'existait dans
 * aucune couche avant cette sous-étape — elle vit ici plutôt que dans les repositories
 * (4a-4d) pour rester propre à l'écran qui la déclenche ; elle sera réutilisée telle
 * quelle par l'écran "Ajouter une playlist" de Réglages → Playlists (§4.3, étape 6f).
 *
 * @param appContext Contexte **application** (pas Activity) : uniquement utilisé pour
 * lire un fichier importé via [android.content.ContentResolver] et le recopier dans le
 * stockage privé de l'app (`filesDir/playlists/`), aucune référence longue durée à une UI.
 */
class OnboardingViewModel(
    private val appRepository: AppRepository,
    private val appContext: Context,
    private val xtreamClient: XtreamClient = XtreamClient(),
    // Fix (2026-07-23) : un OkHttpClient() nu ici ne bénéficiait ni du TLS permissif
    // (panels HTTPS à certificat auto-signé) ni de la cascade de User-Agent
    // (com.dpflix.android.network.NetworkConstants.USER_AGENT_FALLBACKS), déjà en place
    // pour XtreamClient et la lecture vidéo (voir IptvHttpDataSourceFactory) depuis le
    // correctif du 2026-07-22 — alors que c'est justement ce même client qui télécharge
    // la playlist M3U elle-même : un panel qui filtre le User-Agent par défaut d'OkHttp,
    // ou sert un certificat auto-signé sur l'URL de playlist, faisait donc échouer le
    // téléchargement AVANT même d'arriver au parsing/à la lecture des flux HLS/TS,
    // quel que soit le schéma (http:// ou https://).
    private val httpClient: OkHttpClient = com.dpflix.android.network.IptvHttpDataSourceFactory.httpClient()
) : ViewModel() {

    private val _uiState = MutableStateFlow(OnboardingUiState())
    val uiState: StateFlow<OnboardingUiState> = _uiState.asStateFlow()

    fun selectType(type: PlaylistType) {
        val nextStep = if (type == PlaylistType.XTREAM) OnboardingStep.XtreamForm else OnboardingStep.M3uForm
        _uiState.update { it.copy(step = nextStep, errorMessage = null) }
    }

    fun backToChooseType() {
        _uiState.update { it.copy(step = OnboardingStep.ChooseType, errorMessage = null, isSubmitting = false) }
    }

    fun updateXtreamForm(update: (XtreamFormState) -> XtreamFormState) {
        _uiState.update { it.copy(xtreamForm = update(it.xtreamForm), errorMessage = null) }
    }

    fun updateM3uForm(update: (M3uFormState) -> M3uFormState) {
        _uiState.update { it.copy(m3uForm = update(it.m3uForm), errorMessage = null) }
    }

    /** Bouton "Suivant" du formulaire Xtream Codes (§4.2 Étape 2a). */
    fun submitXtream(onComplete: () -> Unit) {
        val form = _uiState.value.xtreamForm
        if (form.serverUrl.isBlank() || form.username.isBlank() || form.password.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Adresse du serveur, nom d'utilisateur et mot de passe sont obligatoires") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val credentials = XtreamCredentials(
                serverUrl = form.serverUrl.trim(),
                username = form.username.trim(),
                // Fix (30 août 2026, § demande utilisateur) : le mot de passe n'était pas
                // trimé comme les deux autres champs — un espace de fin (fréquent lors
                // d'un copier-coller depuis le message du fournisseur) était alors
                // considéré comme un caractère du mot de passe, faisant échouer
                // l'authentification Xtream.
                password = form.password.trim()
            )

            when (val authResult = xtreamClient.authenticate(credentials)) {
                is XtreamResult.Success -> Unit
                else -> {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = xtreamErrorMessage(authResult)) }
                    return@launch
                }
            }

            val playlist = Playlist(
                name = suggestedXtreamPlaylistName(form),
                type = PlaylistType.XTREAM,
                xtreamServerUrl = credentials.serverUrl,
                xtreamUsername = credentials.username,
                xtreamPassword = credentials.password,
                includeTvChannels = form.includeTvChannels
            )

            when (val addResult = appRepository.playlists.addPlaylist(playlist)) {
                is AddPlaylistResult.LimitReached -> {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = "Limite de 5 playlists atteinte") }
                }
                is AddPlaylistResult.Success -> {
                    // §4.2 : la case décide si les chaînes live sont importées ; VOD hors périmètre (§1).
                    val importSucceeded = if (form.includeTvChannels) {
                        importXtreamChannels(credentials, addResult.playlist)
                    } else {
                        true
                    }
                    _uiState.update { it.copy(isSubmitting = false) }
                    if (importSucceeded) {
                        onComplete()
                    }
                    // Sinon (échec d'import) : on reste volontairement sur ce formulaire,
                    // errorMessage affiche déjà la vraie cause (voir [importXtreamChannels])
                    // — la playlist est déjà enregistrée, l'utilisateur n'a rien à ressaisir.
                }
            }
        }
    }

    /**
     * @return `true` si l'import a réussi (chaînes persistées), `false` sinon — utilisé
     * par [submitXtream] pour décider de naviguer immédiatement ou de rester sur ce
     * formulaire pour montrer la vraie raison de l'échec (voir sa doc).
     */
    private suspend fun importXtreamChannels(credentials: XtreamCredentials, playlist: Playlist): Boolean {
        return when (val channelsResult = xtreamClient.fetchLiveChannels(credentials, playlist.id)) {
            is XtreamResult.Success -> {
                val data = channelsResult.data
                if (data.channels.isEmpty() && data.rawStreamCount > 0) {
                    // Le serveur A renvoyé des entrées (rawStreamCount > 0), mais aucune
                    // n'a pu être exploitée : très probablement un champ inattendu chez ce
                    // panel (ex. `stream_id` absent/renommé) plutôt qu'un compte
                    // réellement vide — voir la doc de [XtreamLiveChannelsData.rawStreamCount].
                    // On persiste quand même la playlist (déjà enregistrée) mais on reste
                    // sur ce formulaire pour le signaler, plutôt que de silencieusement
                    // valider un import qui n'a en réalité rien importé.
                    _uiState.update {
                        it.copy(
                            errorMessage = "Le serveur a renvoyé ${data.rawStreamCount} chaîne(s), " +
                                "mais aucune n'a pu être lue (format inattendu pour ce panel). " +
                                "Signale ce nombre pour qu'on ajuste l'app."
                        )
                    }
                    false
                } else if (data.channels.isEmpty() && data.rawStreamCount == 0) {
                    // Diagnostic temporaire (à retirer une fois la cause du "0 chaîne
                    // silencieux" identifiée) : ce cas était auparavant traité comme un
                    // compte réellement vide et validé sans un mot — importSucceeded=true,
                    // onComplete() immédiat, aucune trace pour l'utilisateur. Vu qu'on a
                    // confirmé côté serveur (fetch direct hors app) que ce panel a bien
                    // des milliers de chaînes, ce message permet de vérifier si cette
                    // branche précise est celle qui s'exécute réellement pour ce panel,
                    // plutôt que de suivre une piste sans preuve.
                    _uiState.update {
                        it.copy(
                            errorMessage = "Diagnostic : le serveur a répondu avec succès mais " +
                                "0 chaîne au total (rawStreamCount=0) — avant même la lecture " +
                                "des champs. Si tu sais que ce panel a des chaînes, garde cette " +
                                "capture d'écran, ça confirme que le blocage est ici plutôt " +
                                "qu'au parsing individuel des chaînes."
                        )
                    }
                    false
                } else {
                    appRepository.channels.refreshChannels(playlist.id, data.channels)
                    true
                }
            }
            else -> {
                // Contrairement à avant (erreur silencieusement ignorée) : la playlist
                // reste enregistrée (l'utilisateur ne perd pas sa saisie), mais on
                // affiche désormais la vraie cause de l'échec d'import plutôt que de
                // naviguer directement vers un accueil sans aucune chaîne et sans
                // aucune explication (voir la doc de [XtreamClient.fetchLiveChannels]
                // sur les erreurs désormais distinguables d'un compte réellement vide).
                _uiState.update {
                    it.copy(errorMessage = "Playlist enregistrée, mais l'import des chaînes a échoué : ${xtreamErrorMessage(channelsResult)}")
                }
                false
            }
        }
    }

    /** Bouton "Suivant" du formulaire M3U (§4.2 Étape 2b). */
    fun submitM3u(onComplete: () -> Unit) {
        val form = _uiState.value.m3uForm
        if (form.name.isBlank()) {
            _uiState.update { it.copy(errorMessage = "Le nom de la playlist est obligatoire") }
            return
        }
        if (form.url.isBlank() && form.localFileUri == null) {
            _uiState.update { it.copy(errorMessage = "Indiquez une URL ou importez un fichier local") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSubmitting = true, errorMessage = null) }

            val playlistId = UUID.randomUUID().toString()
            val sourceResult = loadM3uSource(playlistId, form)
            val source = sourceResult.getOrElse { error ->
                _uiState.update { it.copy(isSubmitting = false, errorMessage = error.message ?: "Erreur inconnue") }
                return@launch
            }

            val parseResult = try {
                M3uParser.parse(source.rawContent, playlistId)
            } catch (e: IllegalArgumentException) {
                _uiState.update { it.copy(isSubmitting = false, errorMessage = e.message ?: "Fichier M3U invalide") }
                return@launch
            }

            val playlist = Playlist(
                id = playlistId,
                name = form.name.trim(),
                type = PlaylistType.M3U,
                m3uUrl = form.url.trim().takeIf { it.isNotBlank() },
                m3uLocalFilePath = source.localFilePath
            )

            when (val addResult = appRepository.playlists.addPlaylist(playlist)) {
                is AddPlaylistResult.LimitReached -> {
                    _uiState.update { it.copy(isSubmitting = false, errorMessage = "Limite de 5 playlists atteinte") }
                }
                is AddPlaylistResult.Success -> {
                    appRepository.channels.refreshChannels(playlistId, parseResult.channels)
                    _uiState.update { it.copy(isSubmitting = false) }
                    onComplete()
                }
            }
        }
    }

    private data class M3uSource(val rawContent: String, val localFilePath: String?)

    private suspend fun loadM3uSource(playlistId: String, form: M3uFormState): Result<M3uSource> {
        val uri = form.localFileUri
        return if (uri != null) {
            copyLocalFileToPrivateStorage(playlistId, uri).map { (text, path) -> M3uSource(text, path) }
        } else {
            downloadM3u(form.url.trim()).map { text -> M3uSource(text, null) }
        }
    }

    // Fix (robustesse "n'importe quel caractère spécial") : `response.body?.string()`
    // décode toujours en UTF-8 par défaut quand le Content-Type ne déclare pas de
    // charset (cas très courant chez les panels IPTV) — un contenu réellement en
    // Windows-1252/ISO-8859-1 (noms de chaîne accentués) en ressortait corrompu sans
    // aucune erreur visible. On lit les octets bruts et on laisse [RobustTextDecoder]
    // décider (BOM explicite, charset déclaré, ou détection UTF-8/Windows-1252).
    private suspend fun downloadM3u(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Result.failure(IOException("Le serveur a répondu avec le code ${response.code}"))
                } else {
                    val body = response.body
                    val bytes = body?.bytes() ?: ByteArray(0)
                    val declaredCharset = com.dpflix.android.network.RobustTextDecoder
                        .charsetFromContentType(body?.contentType()?.toString())
                    Result.success(com.dpflix.android.network.RobustTextDecoder.decode(bytes, declaredCharset))
                }
            }
        } catch (e: IOException) {
            Result.failure(IOException(e.message ?: "Erreur réseau"))
        } catch (e: IllegalArgumentException) {
            Result.failure(IOException("URL de playlist invalide"))
        }
    }

    /**
     * Recopie le fichier sélectionné via le sélecteur système dans le stockage privé de
     * l'app (`filesDir/playlists/$playlistId.m3u`) : un `Uri` `OpenDocument` sans
     * permission persistante explicitement prise n'est pas garanti lisible après
     * redémarrage de l'app, alors que [Playlist.m3uLocalFilePath] doit rester
     * relisible indéfiniment (rafraîchissement manuel depuis Réglages, §5.2/étape 6f).
     */
    private suspend fun copyLocalFileToPrivateStorage(playlistId: String, uri: Uri): Result<Pair<String, String>> =
        withContext(Dispatchers.IO) {
            try {
                // Fix (robustesse "n'importe quel caractère spécial") : même raison que
                // [downloadM3u] — `bufferedReader()` décode en UTF-8 par défaut, un
                // fichier .m3u exporté en Windows-1252 (courant depuis des outils
                // Windows) en ressortait corrompu sans erreur visible.
                val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: return@withContext Result.failure(IOException("Impossible de lire le fichier sélectionné"))
                val text = com.dpflix.android.network.RobustTextDecoder.decode(bytes)
                val dir = File(appContext.filesDir, "playlists").apply { mkdirs() }
                val target = File(dir, "$playlistId.m3u")
                target.writeText(text)
                Result.success(text to target.absolutePath)
            } catch (e: IOException) {
                Result.failure(e)
            }
        }

    /**
     * Aucun champ "Nom" dans le formulaire Xtream (§4.2 Étape 2a, contrairement au
     * formulaire M3U) alors que [Playlist.name] est obligatoire : dérivé de l'hôte du
     * serveur (repris tel quel si l'URL n'est pas analysable), affiché ensuite dans
     * Réglages → Playlists (§4.3) et modifiable depuis là si besoin.
     */
    private fun suggestedXtreamPlaylistName(form: XtreamFormState): String {
        val host = try {
            URI(form.serverUrl.trim()).host
        } catch (e: Exception) {
            null
        }
        return "Xtream – ${host ?: form.serverUrl.trim()}"
    }

    private fun xtreamErrorMessage(result: XtreamResult<*>): String = when (result) {
        is XtreamResult.Success -> "" // inatteignable ici, voir l'appel dans submitXtream
        is XtreamResult.InvalidCredentials -> "Identifiants incorrects"
        is XtreamResult.AccountInactive -> "Compte inactif (statut : ${result.status})"
        is XtreamResult.ServerError -> result.message
        is XtreamResult.NetworkError -> result.message
    }
}

/**
 * [OnboardingViewModel] a besoin d'[AppRepository] et d'un `Context` application, donc pas
 * du constructeur sans argument attendu par défaut par `viewModel()` — une factory manuelle
 * suffit ici, cohérente avec l'absence de Hilt/Koin déjà actée pour [com.dpflix.android.di.AppContainer]
 * (étape 6a).
 */
class OnboardingViewModelFactory(
    private val appRepository: AppRepository,
    context: Context
) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return OnboardingViewModel(appRepository, appContext) as T
    }
}
