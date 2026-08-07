package com.dpflix.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpflix.android.model.Channel
import com.dpflix.android.model.ReplayProgram
import com.dpflix.android.ui.ChannelLogo
import com.dpflix.android.ui.theme.DpFlixColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Calque OSD superposé à la vidéo (§4.5, étape 8). Un seul calque cohérent qui
 * s'enrichit au fil des sous-étapes plutôt qu'une collection de widgets indépendants :
 * - 8a : squelette — apparition/disparition + minuteur d'auto-masquage (géré par
 *   [PlayerScreen], pas ici) + affichage minimal (logo + nom).
 * - **8b (ce livrable)** : heure courante, écart au direct (§5.5/§6), programme en cours
 *   si l'EPG est disponible pour la chaîne (§4.6).
 * - **8c (ce livrable)** : le numéro affiché (`Channel.displayNumber`, §5.3) rejoint le nom
 *   dans le bandeau — sert aussi de zone tappable pour ouvrir le clavier numérique virtuel
 *   sur mobile (voir [onRequestNumericEntry]). L'overlay de saisie en tant que tel (numéro
 *   en cours de frappe + clavier) vit dans un calque séparé, [PlayerZapEntryOverlay] — pas
 *   ici : il doit rester visible même quand ce bandeau est masqué par le minuteur (8a),
 *   les deux calques n'ont pas le même cycle de vie.
 * - **8d1** : premier contrôle visible, lecture/pause — bouton dans le bandeau, tap mobile
 *   (voir [onTogglePlayPause]). D-pad TV (8d2).
 * - **8d3** : rendu et interaction du curseur de volume, sans branchement réel derrière
 *   (état local temporaire, voir le README de 8d3).
 * - **8d4 (ce livrable)** : branchement réel du curseur sur le volume système
 *   (`AudioManager.STREAM_MUSIC`, décision détaillée dans [PlayerScreen]) — le
 *   `remember` interne de 8d3 disparaît, remplacé par [volumeFraction]/[onVolumeChange],
 *   même schéma que [isPlaying]/[onTogglePlayPause]. Qualité manuelle suit dans les
 *   prochaines sous-étapes de 8d ; l'agencement définitif de la rangée de contrôles
 *   arrive à 8d9 — pour l'instant, tout est placé au plus simple.
 * - **8d7** : affichage de [availableQualities] (§8d6) sous forme de menu déroulant
 *   (voir [QualitySelector]) — "Auto" + une entrée par résolution disponible. Absent du
 *   bandeau si la chaîne ne propose qu'un seul débit (liste vide, même logique que le
 *   log témoin de 8d6). À cette sous-étape, la sélection restait un `remember` interne à
 *   [QualitySelector], sans effet réel sur la lecture.
 * - **8d8** : [selectedQuality]/[onQualityChange] remplacent ce `remember`
 *   interne — même schéma que [isPlaying]/[onTogglePlayPause] et
 *   [volumeFraction]/[onVolumeChange] — désormais portés par `PlayerController`
 *   (`selectedQuality`, `setQualityOverride`), qui applique réellement le plafond de
 *   résolution au décodeur (`DefaultTrackSelector`) et remet "Auto" à chaque zap (voir
 *   la doc de `PlayerController.playChannel`). Conséquence directe : la sélection
 *   survit désormais normalement au masquage de l'OSD par le minuteur (portée par
 *   `PlayerController`, pas par ce calque), contrairement à 8d7.
 * - **8d9 (ce livrable)** : agencement définitif — deux zones distinctes plutôt que tout
 *   empilé verticalement au fil des sous-étapes précédentes. **Bandeau d'info** en haut
 *   (logo+numéro+nom, heure, écart au direct, programme en cours — 8a/8b/8c, inchangé sur
 *   le fond) et **barre de contrôles** en bas (lecture/pause, volume, qualité — désormais
 *   une seule rangée horizontale au lieu de trois rangées empilées), chacune avec son
 *   propre dégradé (`Brush.verticalGradient`, direction inversée pour la barre du bas,
 *   assombrissement du bord d'écran le plus proche dans les deux cas) — même logique
 *   visuelle, juste dédoublée. Toujours une seule [AnimatedVisibility] pour les deux
 *   zones (un seul calque cohérent qui apparaît/disparaît ensemble, comme depuis 8a) :
 *   pas deux minuteurs d'auto-masquage indépendants. Mise en page uniquement — aucune
 *   logique de focus D-pad ici (l'ordre de traversée entre les contrôles de la barre du
 *   bas arrive à 8d10).
 *
 * [visible] est piloté depuis [PlayerScreen] (tap mobile / D-pad TV + minuteur) plutôt
 * que géré ici : ce composable reste un pur rendu, sans état ni logique d'entrée —
 * cohérent avec le fait qu'il sera partagé par mobile ET TV (comme [PlayerScreen] lui-même
 * depuis l'étape 5/7, contrairement aux autres écrans dédoublés en `*Tv.kt` à l'étape 7).
 * [nowMillis]/[liveEdgeOffsetSeconds] sont aussi calculés par [PlayerScreen] (voir sa doc
 * sur la boucle de rafraîchissement à 1 s) plutôt qu'ici, pour la même raison.
 *
 * [onRequestNumericEntry] : `null` tant que le zapping n'est pas disponible dans ce
 * contexte (mini-lecteur de l'accueil, `osdEnabled = false` — cet OSD n'y est de toute
 * façon jamais rendu, voir [PlayerScreen]). En plein écran, ouvre le clavier virtuel
 * mobile ([PlayerZapEntryOverlay]) — seule façon d'y saisir un numéro, faute de
 * télécommande numérique physique. Sans effet côté TV (la télécommande numérique frappe
 * directement, sans avoir besoin de taper ce bandeau) mais rien n'empêche techniquement
 * un boîtier TV tactile de s'en servir aussi.
 *
 * [isPlaying]/[onTogglePlayPause] (8d1) : [isPlaying] pilote uniquement l'icône affichée
 * (▶ vs ⏸), calculé par [PlayerScreen] à partir de `PlayerUiState` — voir sa doc pour le
 * détail du repli pendant `Buffering`/`Error`. [onTogglePlayPause] reste non nul dès que
 * ce calque est rendu (contrairement à [onRequestNumericEntry], qui dépend de la présence
 * de `appRepository`) : la lecture/pause ne dépend d'aucun contexte de zapping, elle est
 * pertinente partout où [PlayerOsd] apparaît (donc jamais dans le mini-lecteur, qui ne
 * rend de toute façon jamais ce composable — voir `osdEnabled` dans [PlayerScreen]).
 *
 * [volumeFraction]/[onVolumeChange] (8d4) : `0f..1f`, reflète et pilote le volume système
 * (`AudioManager.STREAM_MUSIC`) — voir [PlayerScreen] pour le détail de la décision et de
 * la conversion vers/depuis l'index `AudioManager` réel. Contrairement à 8d3 (curseur en
 * `remember` interne, perdu à chaque recomposition du parent), la position affichée
 * survit désormais normalement au masquage de l'OSD et au zap, portée par
 * [PlayerScreen] comme le reste de l'état de cet écran.
 *
 * [availableQualities] (8d7) : liste brute transmise telle quelle depuis
 * `PlayerController.availableQualities` (§8d6, `StateFlow` recalculé à chaque
 * `onTracksChanged`) — voir [QualitySelector] pour le rendu.
 *
 * [selectedQuality]/[onQualityChange] (8d8) : `null` = "Auto", reflète et pilote
 * `PlayerController.selectedQuality`/`setQualityOverride` — même schéma que
 * [volumeFraction]/[onVolumeChange]. Voir la doc de `PlayerController.playChannel` pour
 * la décision "remis à Auto à chaque zap" (à la différence du volume, délibérément pas
 * remis à zéro par chaîne).
 *
 * [playbackMode]/[replayProgram]/[onExitReplay] (Étape R5b, replay/catch-up) : quand
 * [playbackMode] vaut [PlaybackMode.REPLAY], le bandeau d'info remplace la ligne
 * "écart au direct"/"programme en cours" (8b) par le titre et les horaires de
 * [replayProgram] (jamais `null` dans ce cas, voir `PlayerController.playReplay`), et un
 * bouton "Retour au direct" apparaît en tête de la barre de contrôles du bas
 * ([onExitReplay], `null` en mini-lecteur comme [onOpenSettings] — le replay n'y est de
 * toute façon jamais atteignable). Le reste du bandeau (logo, numéro, nom, heure) et de la
 * barre de contrôles (lecture/pause, volume, qualité) ne change pas de comportement en
 * différé — seul le tampon/l'écart au direct/le zapping sont neutralisés, dans
 * `PlayerController`/`PlayerScreen` (Étape R5a), pas ici.
 *
 * [replayPositionMs]/[replayDurationMs]/[onSeekReplay] (Étape R5c) : alimentent
 * [ReplaySeekBar], rendue au-dessus de la barre de contrôles UNIQUEMENT si
 * `playbackMode == REPLAY` — première vraie barre de progression du lecteur avec
 * `seekTo`, voir sa doc pour le détail du glissement en deux temps. `0L` par défaut
 * (jamais lus tant que [playbackMode] reste [PlaybackMode.LIVE], voir `PlayerScreen`).
 *
 * [onOpenReplay] (Étape R6, point d'entrée) : bouton "Replay" dans la barre de contrôles
 * du bas, rendu UNIQUEMENT en direct ([PlaybackMode.LIVE], jamais en même temps que
 * [onExitReplay] — pas de sens à proposer d'ouvrir la liste des programmes passés pendant
 * qu'on en regarde déjà un) ET seulement si [channel] a du catch-up ([Channel.tvArchive],
 * Étape R1) — cohérent avec le découpage R1-R6 ("visible uniquement si
 * channel.tvArchive"). `null` par défaut comme [onOpenSettings]/[onRequestNumericEntry] :
 * jamais atteignable en mini-lecteur (voir [PlayerScreen]).
 */
@Composable
fun PlayerOsd(
    channel: Channel,
    visible: Boolean,
    nowMillis: Long,
    liveEdgeOffsetSeconds: Float?,
    currentProgramTitle: String?,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    volumeFraction: Float,
    onVolumeChange: (Float) -> Unit,
    availableQualities: List<QualityOption>,
    selectedQuality: QualityOption?,
    onQualityChange: (QualityOption?) -> Unit,
    playbackMode: PlaybackMode = PlaybackMode.LIVE,
    replayProgram: ReplayProgram? = null,
    replayPositionMs: Long = 0L,
    replayDurationMs: Long = 0L,
    onSeekReplay: ((Long) -> Unit)? = null,
    onExitReplay: (() -> Unit)? = null,
    onOpenReplay: (() -> Unit)? = null,
    onRequestNumericEntry: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Bandeau d'info (haut) — logo/numéro/nom, heure, écart au direct, programme
            // en cours. Contenu inchangé depuis 8a/8b/8c, seul le bouton lecture/pause en
            // est retiré (rejoint la barre de contrôles du bas, voir plus loin).
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.8f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = if (onRequestNumericEntry != null) {
                            Modifier.clickable(onClick = onRequestNumericEntry)
                        } else {
                            Modifier
                        }
                    ) {
                        ChannelLogo(channel = channel)
                        Text(
                            text = channelLabel(channel),
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = formatClock(nowMillis),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (playbackMode == PlaybackMode.REPLAY && replayProgram != null) {
                        // Étape R5b : plus de sens à afficher un écart au direct ou le
                        // "programme en cours" EPG sur un programme déjà terminé — le
                        // titre/horaires du ReplayProgram effectivement en train de jouer
                        // sont l'information pertinente ici.
                        Text(
                            text = replayInfoLabel(replayProgram),
                            color = DpFlixColors.OnBackgroundMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            text = liveEdgeOffsetLabel(liveEdgeOffsetSeconds),
                            color = DpFlixColors.OnBackgroundMuted,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (currentProgramTitle != null) {
                            Text(
                                text = "· $currentProgramTitle",
                                color = DpFlixColors.OnBackgroundMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Barre de contrôles (bas) — lecture/pause, volume, qualité : une seule
            // rangée horizontale (8d9) au lieu des trois rangées empilées de 8d1-8d8.
            // Dégradé inversé (assombrit le bas de l'écran plutôt que le haut), même
            // logique visuelle que le bandeau d'info ci-dessus.
            //
            // Étape R5c : en mode REPLAY, une rangée [ReplaySeekBar] s'ajoute AU-DESSUS de
            // cette rangée de contrôles plutôt qu'à l'intérieur — pleine largeur, seule
            // façon d'offrir assez d'espace de glissement pour un geste de seek précis
            // (contrairement au [VolumeSlider], volontairement étroit au milieu d'autres
            // contrôles). D'où la [Column] englobante ci-dessous : c'est elle qui porte
            // désormais le dégradé/padding partagé, plutôt que la [Row] seule comme avant
            // cette étape.
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))
                        )
                    )
                    .padding(horizontal = 24.dp, vertical = 20.dp)
            ) {
                if (playbackMode == PlaybackMode.REPLAY) {
                    ReplaySeekBar(
                        positionMs = replayPositionMs,
                        durationMs = replayDurationMs,
                        onSeek = onSeekReplay
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    PlayPauseButton(isPlaying = isPlaying, onClick = onTogglePlayPause)
                    if (playbackMode == PlaybackMode.REPLAY && onExitReplay != null) {
                        ExitReplayButton(onClick = onExitReplay)
                    }
                    // Étape R6 : point d'entrée replay, voir la doc de [onOpenReplay] plus haut.
                    if (playbackMode == PlaybackMode.LIVE && channel.tvArchive && onOpenReplay != null) {
                        OpenReplayButton(onClick = onOpenReplay)
                    }
                    VolumeSlider(volumeFraction = volumeFraction, onVolumeChange = onVolumeChange)
                    QualitySelector(
                        availableQualities = availableQualities,
                        selected = selectedQuality,
                        onSelect = onQualityChange
                    )
                    if (onOpenSettings != null) {
                        // Ouvre Réglages en incrustation par-dessus la vidéo (qui continue de
                        // jouer derrière, voir PlayerScreen) plutôt que de naviguer vers un
                        // écran séparé — ce qui arrêterait la lecture et figerait les
                        // métriques du Diagnostic (§5.5), alimentées uniquement pendant une
                        // lecture réellement active (voir PlayerMetricsBridge).
                        IconButton(onClick = onOpenSettings) {
                            Icon(imageVector = Icons.Filled.Settings, contentDescription = "Réglages", tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Curseur de volume (8d3, branché en 8d4) — voir la doc de [PlayerOsd] pour le détail de
 * [volumeFraction]/[onVolumeChange]. Composable de pur rendu depuis 8d4 (plus de
 * `remember` interne, contrairement à 8d3) : cohérent avec le reste de ce fichier.
 *
 * Placement (8d9) : rangée de contrôles du bas, entre le bouton lecture/pause et le
 * sélecteur de qualité — plus de rangée dédiée avec padding vertical propre (8d3/8d4),
 * cohérent avec le reste de la barre.
 */
@Composable
private fun VolumeSlider(volumeFraction: Float, onVolumeChange: (Float) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(imageVector = Icons.Filled.VolumeUp, contentDescription = "Volume", tint = Color.White)
        Slider(
            value = volumeFraction,
            onValueChange = onVolumeChange,
            modifier = Modifier.width(160.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = DpFlixColors.OnBackgroundMuted
            )
        )
    }
}

/**
 * Étape R5c — barre de progression + `seekTo` du programme en différé (§ test de sortie
 * R5c : "reculer/avancer dans le programme"). Première vraie barre de progression du
 * lecteur — jusqu'ici seul [VolumeSlider] utilisait un `Slider`, pour un réglage sans
 * notion de "position dans un contenu" (voir la doc de [PlayerOsd]).
 *
 * Ne se rend pas si [durationMs] est encore inconnue (`<= 0`, ex. juste après
 * [com.dpflix.android.player.PlayerController.playReplay], avant que [replayProgram] ne
 * soit retenu côté contrôleur) — même logique défensive que [QualitySelector] pour une
 * liste vide : rien d'utile à afficher plutôt qu'une barre à 0% trompeuse.
 *
 * Glissement en deux temps, comme n'importe quel lecteur vidéo : `onValueChange` ne fait
 * que déplacer le curseur localement ([isDragging]/[dragFraction]) pendant le geste, SANS
 * appeler [onSeek] à chaque pixel (un vrai `seekTo` par frame de glissement saccaderait la
 * lecture pour rien) ; seul `onValueChangeFinished` (relâchement du doigt/de la
 * télécommande) déclenche le [onSeek] réel. [positionMs] (poll ~1s, voir
 * `PlayerScreen`) ne doit d'ailleurs PAS écraser le curseur pendant ce glissement — d'où
 * [isDragging], qui fait temporairement primer la position locale sur celle reçue en
 * paramètre.
 *
 * `onSeek` nullable (comme [PlayerOsd.onExitReplay]) : cohérence de style avec le reste du
 * fichier pour un paramètre qui restera toujours non nul en pratique dès que
 * `playbackMode == REPLAY` (voir `PlayerScreen`), mais sans imposer cette garantie au
 * type lui-même.
 */
@Composable
private fun ReplaySeekBar(positionMs: Long, durationMs: Long, onSeek: ((Long) -> Unit)?) {
    if (durationMs <= 0L) return

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableStateOf(0f) }
    val shownFraction = if (isDragging) {
        dragFraction
    } else {
        (positionMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    }
    val shownPositionMs = (shownFraction * durationMs).toLong()

    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = formatReplayTime(shownPositionMs),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
        Slider(
            value = shownFraction,
            onValueChange = { fraction ->
                isDragging = true
                dragFraction = fraction
            },
            onValueChangeFinished = {
                onSeek?.invoke((dragFraction * durationMs).toLong())
                isDragging = false
            },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = DpFlixColors.OnBackgroundMuted
            )
        )
        Text(
            text = formatReplayTime(durationMs),
            color = Color.White,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

/** "H:MM:SS" au-delà d'une heure, "M:SS" en-deçà — [ms] toujours >= 0 ici ([ReplaySeekBar]
 *  ne reçoit que des positions déjà bornées, voir `PlayerController.seekToReplayPosition`/
 *  `currentReplayPositionMs`). */
private fun formatReplayTime(ms: Long): String {
    val totalSeconds = ms / 1000L
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/**
 * Sélecteur de qualité (rendu 8d7, sélection réelle branchée 8d8) — voir la doc de
 * [PlayerOsd] pour le détail de [availableQualities]/[selected]/[onSelect].
 *
 * Ne se rend pas du tout si [availableQualities] est vide (chaîne mono-débit, ou pistes
 * pas encore annoncées par le flux juste après un zap) — même logique que le log témoin
 * de 8d6, pas de contrôle à afficher quand il n'y a rien à choisir.
 *
 * "Auto" (résolution la plus haute que l'ABR juge soutenable) apparaît toujours en tête
 * de la liste déroulante, [selected] `null` le représentant plutôt qu'un [QualityOption]
 * dédié — cohérent avec `PlayerController.setQualityOverride`, où "Auto" correspond à
 * l'absence de plafond `DefaultTrackSelector` plutôt qu'à une résolution précise.
 *
 * `expanded` (ouverture/fermeture du menu) reste un `remember` **interne**, à la
 * différence de [selected] (8d8) : pur chrome d'interaction propre à ce composable, sans
 * intérêt pour `PlayerScreen` ou `PlayerController` — rien à hoister ici, contrairement à
 * la sélection elle-même qui pilote la lecture.
 *
 * Placement (8d9) : rangée de contrôles du bas, après le curseur de volume — plus de
 * rangée dédiée avec padding vertical propre (8d7), cohérent avec le reste de la barre.
 */
@Composable
private fun QualitySelector(
    availableQualities: List<QualityOption>,
    selected: QualityOption?,
    onSelect: (QualityOption?) -> Unit
) {
    if (availableQualities.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { expanded = true }
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(imageVector = Icons.Filled.HighQuality, contentDescription = "Qualité", tint = Color.White)
            Text(
                text = selected?.label ?: "Auto",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Auto") },
                leadingIcon = if (selected == null) {
                    { Icon(Icons.Filled.Check, contentDescription = null) }
                } else null,
                onClick = {
                    onSelect(null)
                    expanded = false
                }
            )
            availableQualities.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    leadingIcon = if (selected == option) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else null,
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * "N° · Nom" quand un numéro affiché existe (§5.3, personnalisé en priorité, voir
 * `Channel.displayNumber`), simple nom sinon (chaîne sans numéro connu — ex. import M3U
 * sans `tvg-chno` et jamais renumérotée manuellement). Ajouté à cette sous-étape (8c) :
 * le numéro donne un repère de zapping direct et sert de zone tappable pour le clavier
 * virtuel mobile (voir [onRequestNumericEntry][PlayerOsd]).
 */
private fun channelLabel(channel: Channel): String =
    channel.displayNumber?.let { number -> "$number · ${channel.name}" } ?: channel.name

/** Pas `java.time` (minSdk 23 du projet, pas de désucrage — même contrainte que
 *  `EpgXmlParser`/`SettingsScreen.formatEpgTimestamp`). */
private fun formatClock(nowMillis: Long): String =
    SimpleDateFormat("HH:mm", Locale.FRANCE).format(Date(nowMillis))

/**
 * `null` : le flux n'est pas (encore) reconnu comme direct par ExoPlayer, ou l'écart
 * n'est pas encore connu (voir `PlayerController.currentLiveEdgeOffsetSeconds`) — état
 * transitoire courant juste après un zapping, avant `STATE_READY`.
 * Un écart quasi nul (< 1 s, arrondi à l'affichage) est présenté comme "Direct" plutôt
 * que "0 s" : plus lisible, et cohérent avec le fait que le retard cible (§5.1/§6,
 * `PlayerSettings.bufferSafetyMarginSeconds`) peut légitimement valoir 0.
 */
private fun liveEdgeOffsetLabel(offsetSeconds: Float?): String = when {
    offsetSeconds == null -> "Écart au direct : indisponible"
    offsetSeconds.roundToInt() <= 0 -> "Direct"
    else -> "Écart au direct : ${offsetSeconds} s"
}

/**
 * "Titre (HH:mm–HH:mm)" (Étape R5b) — même fuseau horaire par défaut de l'appareil que
 * `ReplayScreen.formatProgramTimeRange` (Étape R4), pas de date ici (contrairement à R4,
 * qui liste des programmes pouvant s'étaler sur plusieurs jours) : ce bandeau n'affiche
 * QUE le programme en train de jouer à l'instant, la date du jour où il a été diffusé
 * n'apporte rien de plus une fois la lecture démarrée.
 */
private fun replayInfoLabel(program: ReplayProgram): String {
    val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
    val start = timeFormat.format(Date(program.startMillis))
    val end = timeFormat.format(Date(program.endMillis))
    return "${program.title} ($start–$end)"
}

/**
 * Bouton lecture/pause (§8d1) — tap mobile ici ; le D-pad TV (8d2) rappellera le même
 * [onClick] (`PlayerController.togglePlayPause`) depuis `PlayerScreen`, ce composable ne
 * gère que le rendu et le tap, pas la source de la commande.
 *
 * Zone tappable de 44dp (cohérente avec [ChannelLogo] dans le bandeau d'info) plutôt que
 * l'icône seule à sa taille naturelle, pour une cible tactile confortable. Style
 * volontairement minimal (icône sur fond transparent, pas de cadre). Premier élément de
 * la barre de contrôles du bas depuis 8d9 (voir [PlayerOsd]) — auparavant dans le
 * bandeau d'info du haut (8d1-8d8).
 */
@Composable
private fun PlayPauseButton(isPlaying: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Mettre en pause" else "Lecture",
            tint = Color.White
        )
    }
}

/**
 * Bouton "Retour au direct" (Étape R5b) — seul contrôle nouveau spécifique au replay dans
 * la barre du bas, rendu UNIQUEMENT en [PlaybackMode.REPLAY] (voir la garde dans
 * [PlayerOsd]). [onClick] rappelle `PlayerController.playChannel` côté `PlayerScreen` —
 * ce composable ne fait que le rendu, comme le reste de cette barre.
 *
 * Style "puce" (icône + libellé sur fond légèrement teinté) plutôt qu'une simple icône
 * isolée comme [PlayPauseButton] : contrairement aux autres contrôles de cette barre
 * (toujours présents, leur icône seule suffit à les reconnaître avec l'habitude), celui-ci
 * n'apparaît que ponctuellement — le libellé explicite évite toute ambiguïté sur ce que
 * fait ce bouton la première fois qu'un utilisateur le rencontre.
 */
@Composable
private fun ExitReplayButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DpFlixColors.Red.copy(alpha = 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(imageVector = Icons.Filled.LiveTv, contentDescription = null, tint = Color.White)
        Text(
            text = "Retour au direct",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Bouton "Replay" (Étape R6, point d'entrée) — même style "puce" que [ExitReplayButton]
 * (icône + libellé, jamais confondu avec les contrôles permanents de la barre) : les deux
 * ne sont d'ailleurs jamais visibles en même temps ([PlayerOsd] les rend sur des branches
 * mutuellement exclusives de [PlaybackMode]). Fond neutre (`DpFlixColors.Surface`) plutôt
 * que le rouge de [ExitReplayButton] : celui-ci ramène vers l'action "normale" (le
 * direct), celui-là ouvre une action secondaire optionnelle — la distinction de couleur
 * aide à ne pas les confondre visuellement si l'utilisateur passe rapidement de l'un à
 * l'autre au fil de ses lectures. [onClick] rappelle `onNavigateToReplay` côté
 * `PlayerScreen`, qui délègue lui-même la navigation réelle au `NavHost` appelant — ce
 * composable ne fait que le rendu, comme le reste de cette barre.
 */
@Composable
private fun OpenReplayButton(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(DpFlixColors.Surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Icon(imageVector = Icons.Filled.Replay, contentDescription = null, tint = Color.White)
        Text(
            text = "Replay",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

// [Fix logos accueil] `ChannelLogo` déplacé vers com.dpflix.android.ui.ChannelLogo.kt
// pour être partagé avec HomeScreen/HomeScreenTv (jusqu'ici seul l'OSD l'affichait —
// voir la doc dans ce nouveau fichier pour le contexte complet). Import ajouté
// ci-dessus ; comportement inchangé pour l'OSD.