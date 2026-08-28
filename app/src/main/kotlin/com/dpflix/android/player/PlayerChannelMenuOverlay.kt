package com.dpflix.android.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpflix.android.model.Channel
import com.dpflix.android.ui.ChannelLogo
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Menu de chaînes pendant la lecture (touche Menu de la télécommande) : liste des chaînes
 * de la catégorie en cours ([PlayerZapping.sameCategory]), affichée à côté de la vidéo
 * plutôt qu'en plein écran — la vidéo reste visible et en lecture pendant la navigation
 * dans le menu, comme sur un vrai boîtier IPTV.
 *
 * Calque distinct de [PlayerOsd]/[PlayerZapEntryOverlay] (même principe : pur rendu, tout
 * l'état — visibilité, sélection, minuteur d'auto-masquage propre à ce menu — est géré par
 * [PlayerScreen]). Navigation/sélection au D-pad uniquement (HAUT/BAS déplacent
 * [selectedIndex], OK valide) : câblée dans `PlayerScreen.buildPlayerViewKeyListener`,
 * pas via le système de focus Compose, pour rester cohérente avec le reste de l'écran de
 * lecture (voir la doc de [PlayerScreen] sur `useController`/`setOnKeyListener`).
 *
 * Se ferme sur une seconde pression de Menu OU après 5 secondes sans navigation dans le
 * menu (toutes deux pilotées par `PlayerScreen`, pas ce composable).
 */
@Composable
fun PlayerChannelMenuOverlay(
    visible: Boolean,
    channels: List<Channel>,
    selectedIndex: Int,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(320.dp)
                .background(Color.Black.copy(alpha = 0.85f))
        ) {
            val listState = rememberLazyListState()

            // Garde le choix courant visible dès l'ouverture (chaîne en cours) et à chaque
            // déplacement HAUT/BAS — un LaunchedEffect(selectedIndex) suffit, il se
            // redéclenche automatiquement à chaque changement de sélection.
            LaunchedEffect(selectedIndex, channels) {
                if (channels.isEmpty()) return@LaunchedEffect
                listState.animateScrollToItem(selectedIndex.coerceIn(0, channels.lastIndex))
            }

            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                itemsIndexed(channels, key = { _, ch -> ch.id }) { index, ch ->
                    ChannelMenuRow(channel = ch, isSelected = index == selectedIndex)
                }
            }
        }
    }
}

@Composable
private fun ChannelMenuRow(channel: Channel, isSelected: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) DpFlixColors.Red.copy(alpha = 0.35f) else Color.Transparent)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            channel.displayNumber?.let { number ->
                Text(
                    text = "$number",
                    color = Color.White.copy(alpha = 0.7f),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
            ChannelLogo(channel = channel, size = 28.dp)
            Text(
                text = channel.name,
                color = Color.White,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
            )
        }
    }
}
