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
 * Écran de verrouillage / activation — partagé mobile + TV (voir [isTv]).
 *
 * Flux :
 * - LOCKED (jamais déverrouillé, ou période expirée) → champ de saisie de code
 * - ACTIVE (code valide) → onUnlocked()
 */
@Composable
fun LockScreen(
    accessRepository: AccessRepository,
    onUnlocked: () -> Unit,
    isTv: Boolean = false
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

            // Si l'accès devient valide pendant la saisie, on sort
            androidx.compose.runtime.LaunchedEffect(user) {
                if (user.isAccessValid) onUnlocked()
            }

            fun submitCode() {
                if (loading || code.isBlank()) return
                scope.launch {
                    loading = true
                    error = null
                    successMessage = null
                    when (accessRepository.redeemCode(code)) {
                        RedeemResult.Success -> {
                            successMessage = "Code activé avec succès !"
                            // currentUser est mis à jour immédiatement → LaunchedEffect sortira
                        }
                        RedeemResult.InvalidCode -> error = "Code invalide."
                    }
                    loading = false
                }
            }

            fun contactProvider() {
                // TV : pas d'app WhatsApp installée dans l'immense majorité des cas, et
                // tenter ACTION_VIEW pourrait ouvrir un navigateur TV de façon peu naturelle
                // à la télécommande — on affiche directement le numéro en grand plutôt que
                // de rediriger vers quoi que ce soit.
                if (isTv) {
                    showPhoneNumber = true
                    return
                }
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

            val statusText = "Entrez votre code d'activation pour continuer."

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

                Spacer(modifier = Modifier.height(28.dp))

                // Champ code (masqué une fois l'accès valide, le temps que
                // onUnlocked() navigue ailleurs)
                if (!user.isAccessValid) {
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            // Ne pas forcer la casse en majuscules : "Mamanzefa"
                            // doit garder sa casse exacte. Les codes Porushd
                            // restent valides quelle que soit la casse saisie
                            // grâce au fallback dans redeemCode().
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
                        style = if (isTv) MaterialTheme.typography.headlineSmall
                            else MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = AccessRepository.ADMIN_WHATSAPP_DISPLAY,
                        color = DpFlixColors.OnBackground,
                        style = if (isTv) MaterialTheme.typography.displayMedium
                            else MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}
