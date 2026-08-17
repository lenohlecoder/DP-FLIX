package com.djamylova.tvflix

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/**
 * Écran / dialogue « À propos » – obligation de licence TV Bro.
 *
 * Étape 10 :
 * Toute redistribution modifiée doit afficher quelque part dans l’UI
 * que le projet utilise les sources de TV Bro et le lien
 * https://github.com/truefedex/tv-bro
 *
 * @see <a href="https://github.com/truefedex/tv-bro">TV Bro</a>
 */
object TvFlixAbout {

    const val TV_BRO_URL = "https://github.com/truefedex/tv-bro"
    const val TV_BRO_LICENSE_NOTE =
        "Uses sources from TV Bro (https://github.com/truefedex/tv-bro)"

    /**
     * Affiche un dialogue « À propos » conforme à la licence.
     * À appeler depuis un menu, un bouton long, etc.
     */
    fun show(context: Context) {
        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.tvflix_about_title)
            .setMessage(R.string.tvflix_about_message)
            .setPositiveButton(R.string.tvflix_about_ok, null)
            .setNeutralButton(R.string.tvflix_about_open_link) { _, _ ->
                openTvBroPage(context)
            }

        try {
            builder.show()
        } catch (e: Exception) {
            // Fallback si pas de thème Dialog (certains contextes TV)
            Toast.makeText(
                context,
                context.getString(R.string.tvflix_about_credit),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Ouvre la page GitHub de TV Bro dans le navigateur / app capable.
     */
    fun openTvBroPage(context: Context) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(TV_BRO_URL))
            if (context !is Activity) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                TV_BRO_URL,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * Texte court prêt à coller dans un écran « À propos » existant du host.
     */
    fun getCreditText(context: Context): String {
        return context.getString(R.string.tvflix_about_credit)
    }

    /**
     * Texte multiligne pour un écran À propos plus complet.
     */
    fun getFullAboutText(context: Context): String {
        return context.getString(R.string.tvflix_about_message)
    }
}
