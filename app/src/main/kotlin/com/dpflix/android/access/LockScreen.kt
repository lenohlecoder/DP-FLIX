package com.dpflix.android.access

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dpflix.android.ui.DpFlixBackground
import com.dpflix.android.ui.theme.DpFlixColors
import com.dpflix.android.ui.theme.DpFlixTheme
import kotlinx.coroutines.launch

/**
 * Écran de verrouillage / activation (mobile).
 *
 * Flux :
 * - PENDING  → message d'attente + champ de saisie de code d'activation
 * - EXPIRED / BLOCKED → message + champ code (prolongation possible)
 * - ACTIVE valide → onUnlocked() (ne devrait normalement pas arriver ici)
 * - role ADMIN → onAdminUnlocked()
 */
@Composable
fun LockScreen(
    accessRepository: AccessRepository,
    onUnlocked: () -> Unit,
    onAdminUnlocked: () -> Unit
) {
    DpFlixTheme {
        DpFlixBackground {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            val user by accessRepository.currentUser.collectAsState()

            var code by remember { mutableStateOf("") }
            var error by remember { mutableStateOf<String?>(null) }
            var loading by remember { mutableStateOf(false) }
            var showPhoneNumber by remember { mutableStateOf(false) }
            var successMessage by remember { mutableStateOf<String?>(null) }

            // Si l'utilisateur devient admin ou actif pendant l'écoute, on sort
            androidx.compose.runtime.LaunchedEffect(user) {
                val u = user ?: return@LaunchedEffect
                when {
                    u.isAdmin -> onAdminUnlocked()
                    u.isAccessValid -> onUnlocked()
                }
            }

            fun submitCode() {
                if (loading || code.isBlank()) return
                scope.launch {
                    loading = true
                    error = null
                    successMessage = null
                    when (val result = accessRepository.redeemCode(code)) {
                        RedeemResult.Success -> {
                            successMessage = "Code activé avec succès !"
                            // L'écoute temps réel mettra à jour currentUser → LaunchedEffect sortira
                        }
                        RedeemResult.InvalidCode -> error = "Code invalide."
                        RedeemResult.AlreadyUsed -> error = "Ce code a déjà été utilisé."
                        RedeemResult.NetworkError -> error = "Erreur réseau. Réessayez."
                        is RedeemResult.Error -> error = result.message
                    }
                    loading = false
                }
            }

            fun contactProvider() {
                val waUri = Uri.parse(
                    "https://wa.me/${AccessRepository.ADMIN_WHATSAPP_E164}" +
                        "?text=" + Uri.encode(
                        "Bonjour, je souhaite obtenir un code d'accès pour DP-FLIX."
                    )
                )
                try {
                    context.startActivity(Intent(Intent.ACTION_VIEW, waUri))
                } catch (_: ActivityNotFoundException) {
                    showPhoneNumber = true
                } catch (_: Exception) {
                    showPhoneNumber = true
                }
            }

            val statusText = when (user?.status) {
                AccessStatus.PENDING -> "Votre demande est en cours de traitement.\nSaisissez un code d'activation si vous en avez reçu un."
                AccessStatus.EXPIRED -> "Votre accès a expiré.\nSaisissez un nouveau code pour prolonger."
                AccessStatus.BLOCKED -> "Votre accès a été désactivé.\nContactez l'administrateur."
                else -> "Entrez votre code d'activation pour continuer."
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 28.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "DP-FLIX",
                    color = DpFlixColors.OnBackground,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = statusText,
                    color = DpFlixColors.OnBackgroundMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )

                if (user?.pseudo?.isNotBlank() == true) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Compte : ${user!!.pseudo}",
                        color = DpFlixColors.OnBackgroundMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(28.dp))

                // Champ code (sauf si BLOCKED sans possibilité de code)
                if (user?.status != AccessStatus.BLOCKED) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            // Fix : ne plus forcer la casse en majuscules — le
                            // code spécial admin ("Mamanzefa") doit garder sa
                            // casse exacte pour matcher l'ID Firestore. Les
                            // codes normaux restent valides quelle que soit la
                            // casse saisie grâce au fallback dans redeemCode().
                            code = it
                            error = null
                            successMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Code d'activation") },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { submitCode() })
                    )

                    if (error != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = error!!,
                            color = DpFlixColors.Red,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }
                    if (successMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = successMessage!!,
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))
                    Button(
                        onClick = { submitCode() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !loading && code.isNotBlank()
                    ) {
                        if (loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.height(20.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Activer le code")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { contactProvider() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Contacter le fournisseur")
                }

                if (showPhoneNumber) {
                    Spacer(modifier = Modifier.height(28.dp))
                    Text(
                        text = "Appelez ou écrivez au :",
                        color = DpFlixColors.OnBackgroundMuted,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AccessRepository.ADMIN_WHATSAPP_DISPLAY,
                        color = DpFlixColors.OnBackground,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
