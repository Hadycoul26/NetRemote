package com.example.netremote

import android.content.Context
import java.security.SecureRandom

/** Etat d'activation du serveur, port et cle d'acces. */
object Prefs {

    private const val FILE = "netremote_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"
    private const val KEY_TOKEN = "token"

    const val DEFAULT_PORT = 8080

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context) = sp(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_ENABLED, value).apply()

    fun port(context: Context) = sp(context).getInt(KEY_PORT, DEFAULT_PORT)

    /**
     * Cle generee au premier usage, puis conservee.
     *
     * Toute personne connectee au point d'acces peut joindre le serveur : sans
     * cle, elle pourrait couper la connexion du telephone.
     */
    fun token(context: Context): String {
        sp(context).getString(KEY_TOKEN, null)?.let { return it }

        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val token = (1..6).map { alphabet[random.nextInt(alphabet.length)] }.joinToString("")

        sp(context).edit().putString(KEY_TOKEN, token).apply()
        return token
    }

    fun regenerateToken(context: Context): String {
        sp(context).edit().remove(KEY_TOKEN).apply()
        return token(context)
    }

    fun isTokenValid(context: Context, candidate: String?): Boolean =
        candidate != null && candidate.equals(token(context), ignoreCase = true)
}
