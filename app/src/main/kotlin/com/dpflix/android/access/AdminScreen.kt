package com.dpflix.android.access

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dpflix.android.ui.theme.DpFlixTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Espace administrateur (mobile).
 *
 * - Formulaire de génération de code (durée + Stream1/2)
 * - Liste des codes générés
 * - Liste simple des utilisateurs (statut, expiration, permissions)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminScreen(
    accessRepository: AccessRepository,
    onEnterApp: () -> Unit,
    onBackToLock: (() -> Unit)? = null
) {
    DpFlixTheme {
        val scope = rememberCoroutineScope()

        var durationDays by remember { mutableIntStateOf(30) }
        var stream1 by remember { mutableStateOf(true) }
        var stream2 by remember { mutableStateOf(true) }
        var generatedCode by remember { mutableStateOf<String?>(null) }
        var error by remember { mutableStateOf<String?>(null) }
        var loading by remember { mutableStateOf(false) }

        var codes by remember { mutableStateOf<List<ActivationCode>>(emptyList()) }
        var users by remember { mutableStateOf<List<UserAccess>>(emptyList()) }

        fun refresh() {
            scope.launch {
                try {
                    codes = accessRepository.listActivationCodes()
                    users = accessRepository.listUsers()
                } catch (e: Exception) {
                    error = e.message
                }
            }
        }

        LaunchedEffect(Unit) { refresh() }

        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Administration DP-FLIX") },
                    navigationIcon = {
                        if (onBackToLock != null) {
                            OutlinedButton(
                                onClick = onBackToLock,
                                modifier = Modifier.padding(start = 8.dp)
                            ) {
                                Text("Retour")
                            }
                        }
                    },
                    actions = {
                        OutlinedButton(onClick = onEnterApp) {
                            Text("Entrer dans l'app")
                        }
                    }
                )
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                // ── Génération de code ──────────────────────────────
                Text(
                    text = "Générer un code d'activation",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = durationDays.toString(),
                    onValueChange = { durationDays = it.toIntOrNull() ?: 30 },
                    label = { Text("Durée (jours)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = stream1, onCheckedChange = { stream1 = it })
                    Text("Stream 1")
                    Spacer(modifier = Modifier.width(16.dp))
                    Checkbox(checked = stream2, onCheckedChange = { stream2 = it })
                    Text("Stream 2")
                }

                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            generatedCode = null
                            try {
                                val code = accessRepository.generateActivationCode(
                                    durationDays = durationDays,
                                    stream1 = stream1,
                                    stream2 = stream2
                                )
                                generatedCode = code
                                refresh()
                            } catch (e: Exception) {
                                error = e.message
                            }
                            loading = false
                        }
                    },
                    enabled = !loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (loading) "Génération…" else "Générer le code")
                }

                if (generatedCode != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("Code généré :", style = MaterialTheme.typography.bodySmall)
                            Text(
                                text = generatedCode!!,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                if (error != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(text = error!!, color = MaterialTheme.colorScheme.error)
                }

                Spacer(modifier = Modifier.height(28.dp))

                // ── Liste des codes ─────────────────────────────────
                Text(
                    text = "Codes (${codes.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                codes.sortedByDescending { it.createdAt?.toDate()?.time ?: 0 }.forEach { c ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("${c.code}  ·  ${c.durationDays} j", fontWeight = FontWeight.Bold)
                            Text(
                                "Statut : ${c.status}  |  S1=${c.stream1Enabled}  S2=${c.stream2Enabled}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(28.dp))

                // ── Liste des utilisateurs ──────────────────────────
                Text(
                    text = "Utilisateurs (${users.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                val dateFmt = remember {
                    SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE)
                }

                users.sortedByDescending { it.createdAt?.toDate()?.time ?: 0 }.forEach { u ->
                    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "${u.pseudo.ifBlank { "(sans pseudo)" }}  ·  ${u.status}",
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "UID : ${u.uid.take(8)}…  |  S1=${u.stream1Enabled}  S2=${u.stream2Enabled}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            u.subscriptionEnd?.let {
                                Text(
                                    "Expire : ${dateFmt.format(it.toDate())}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                OutlinedButton(
                    onClick = { refresh() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Actualiser")
                }
            }
        }
    }
}
