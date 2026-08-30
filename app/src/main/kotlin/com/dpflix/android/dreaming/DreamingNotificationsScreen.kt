package com.dpflix.android.dreaming

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.dpflix.android.ui.theme.DpFlixTheme
import kotlinx.coroutines.launch

@Composable
fun DreamingNotificationsScreen(
    repository: DreamingNotificationRepository,
    onPlay: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var items by remember { mutableStateOf<List<DreamingNotification>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            error = null
            runCatching { repository.fetch() }
                .onSuccess { response -> items = response.items.filter { repository.isVisibleNow(it) } }
                .onFailure { error = it.message ?: "Impossible de charger les notifications." }
            loading = false
        }
    }

    val firstCardFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) { refresh() }
    // Fix : focus initial réellement demandé sur la première carte (l'ancien
    // FocusRequester était attaché à un Text qui n'écoute pas Enter, et n'était
    // jamais sollicité — rien n'avait le focus à l'entrée sur l'écran).
    LaunchedEffect(items) {
        if (items.isNotEmpty()) firstCardFocus.requestFocus()
    }

    // Fix : ce Column ne doit plus intercepter Enter/OK lui-même. Comme
    // onPreviewKeyEvent se propage de la racine vers le nœud focalisé, un
    // gestionnaire ici interceptait l'événement AVANT qu'il n'atteigne la carte
    // réellement sélectionnée, et jouait donc toujours items.first() quelle que
    // soit la carte mise en avant par le D-pad. La sélection appartient à chaque
    // carte (voir DreamingNotificationCard).
    // Fix (30 août 2026, correctif initial) : cet écran (et DreamingNotificationPopup
    // ci-dessous) n'était enveloppé dans aucun MaterialTheme.
    // Fix (30 août 2026, correctif complémentaire — le premier était insuffisant) :
    // MaterialTheme (donc DpFlixTheme, qui n'est qu'un MaterialTheme(colorScheme=...))
    // fournit une palette de couleurs mais NE positionne PAS LocalContentColor, la
    // couleur par défaut utilisée par Text()/Icon() quand aucune couleur explicite
    // n'est passée — seul un Surface (ou un composant qui en contient un, comme Card)
    // le fait. Les cartes d'annonces (DreamingNotificationCard, dans un Card) étaient
    // donc déjà correctement colorées, mais le titre "Notifications", l'icône et le
    // texte "Aucune notification pour le moment." vivent dans un simple Column sans
    // Surface : ils retombaient toujours sur le noir par défaut de Compose, sur fond de
    // fenêtre lui-même noir (android:windowBackground, voir themes.xml) — d'où l'écran
    // resté totalement noir malgré le premier correctif. Un Surface explicite (couleur
    // de fond du thème) autour du Column règle cela pour tous les Text/Icon internes
    // d'un coup, sans avoir à passer une couleur explicite à chacun.
    // Fix (30 août 2026, correctif n°3) : édge-to-edge (decorFitsSystemWindows = false,
    // voir MainActivity/themes.xml) signifie que le système ne réserve plus la place
    // des barres de statut/navigation — c'est à chaque écran d'ajouter lui-même le
    // padding nécessaire. HomeScreen l'obtient via DpFlixBackground (qui applique déjà
    // windowInsetsPadding(WindowInsets.systemBars) autour de son content), mais cet
    // écran n'a jamais eu ce traitement : le Row titre "Notifications" se dessinait donc
    // partiellement sous la barre de statut, d'où le titre rogné signalé une fois le
    // texte enfin visible.
    DpFlixTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars)
                    .padding(16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Notifications, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Notifications", style = MaterialTheme.typography.headlineSmall)
                }
                Spacer(Modifier.height(12.dp))
                when {
                    loading -> Text("Chargement…")
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    items.isEmpty() -> Text("Aucune notification pour le moment.")
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
                            DreamingNotificationCard(
                                item = item,
                                repository = repository,
                                onPlay = onPlay,
                                focusRequester = if (index == 0) firstCardFocus else null
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DreamingNotificationCard(
    item: DreamingNotification,
    repository: DreamingNotificationRepository,
    onPlay: (String) -> Unit,
    focusRequester: FocusRequester? = null
) {
    Card(
        modifier = Modifier
            .let { if (focusRequester != null) it.focusRequester(focusRequester) else it }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    if (item.videoUrl.isNotBlank()) { onPlay(item.videoUrl); true } else false
                } else false
            },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            val image = item.images.firstOrNull()
            if (image != null) {
                AsyncImage(
                    model = repository.imageUrl(image),
                    contentDescription = item.titre,
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(item.titre, style = MaterialTheme.typography.titleLarge)
            if (item.texte.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(item.texte, style = MaterialTheme.typography.bodyMedium)
            }
            if (item.videoUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Button(onClick = { onPlay(item.videoUrl) }) {
                    Icon(Icons.Default.PlayArrow, null)
                    Spacer(Modifier.size(6.dp))
                    Text(item.actionLabel.ifBlank { "Regarder" })
                }
            }
        }
    }
}

/**
 * Carte popup à afficher au-dessus du menu principal.
 * La fermeture est locale à l'appareil et empêche la réapparition de la même carte.
 */
@Composable
fun DreamingNotificationPopup(
    item: DreamingNotification,
    repository: DreamingNotificationRepository,
    state: DreamingNotificationState,
    onPlay: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Fix (30 août 2026) : même cause que DreamingNotificationsScreen ci-dessus — ce
    // popup est affiché en frère de HomeScreen dans DpFlixNavHost (pas à l'intérieur de
    // son DpFlixTheme), donc sans thème propre il héritait du même risque de texte noir
    // sur fond noir.
    DpFlixTheme {
        Card(modifier = modifier.padding(16.dp).focusable(), elevation = CardDefaults.cardElevation(10.dp)) {
            Box(Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    val image = item.images.firstOrNull()
                    if (image != null) {
                        AsyncImage(
                            model = repository.imageUrl(image),
                            contentDescription = item.titre,
                            modifier = Modifier.fillMaxWidth().height(210.dp),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(item.titre, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text(item.texte)
                    if (item.videoUrl.isNotBlank()) {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = { onPlay(item.videoUrl) }) {
                            Icon(Icons.Default.PlayArrow, null)
                            Spacer(Modifier.size(6.dp))
                            Text(item.actionLabel.ifBlank { "Regarder" })
                        }
                    }
                }
                IconButton(onClick = {
                    state.dismiss(item.id)
                    onDismiss()
                }, modifier = Modifier.align(Alignment.TopEnd)) {
                    Icon(Icons.Default.Close, contentDescription = "Fermer")
                }
            }
        }
    }
}
