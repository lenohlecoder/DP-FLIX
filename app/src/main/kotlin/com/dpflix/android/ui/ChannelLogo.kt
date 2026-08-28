package com.dpflix.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dpflix.android.model.Channel
import com.dpflix.android.ui.theme.DpFlixColors

/**
 * Logo d'une chaîne (`channel.logoUrl`, alimenté par `tvg-logo` en M3U ou
 * `stream_icon` en Xtream — voir [Channel]), avec repli sur l'initiale du nom si
 * absent ou en échec de chargement. Extrait de `PlayerOsd.ChannelLogo` (OSD lecteur)
 * pour être partagé avec les grilles de chaînes de l'accueil (`HomeScreen`,
 * `HomeScreenTv`), qui n'affichaient jusqu'ici que le numéro et le nom en texte —
 * aucune des deux ne consommait `channel.logoUrl` bien qu'il soit déjà correctement
 * rempli par le parsing M3U/Xtream et propagé jusqu'au modèle [Channel].
 */
@Composable
fun ChannelLogo(channel: Channel, size: Dp = 44.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(DpFlixColors.Surface),
        contentAlignment = Alignment.Center
    ) {
        val logoUrl = channel.logoUrl
        if (logoUrl != null) {
            AsyncImage(
                model = logoUrl,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            Text(
                text = channel.name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?",
                color = DpFlixColors.OnBackground,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
