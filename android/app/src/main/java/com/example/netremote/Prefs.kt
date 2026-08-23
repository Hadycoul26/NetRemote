package com.example.netremote

import android.content.Context

/** Etat d'activation du serveur et port d'ecoute. */
object Prefs {

    private const val FILE = "netremote_prefs"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_PORT = "port"

    const val DEFAULT_PORT = 8080

    private fun sp(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** true = ce telephone partage sa connexion et accepte les commandes. */
    fun isServing(context: Context) = sp(context).getBoolean(KEY_ENABLED, false)

    fun setServing(context: Context, value: Boolean) =
        sp(context).edit().putBoolean(KEY_ENABLED, value).apply()

    fun port(context: Context) = sp(context).getInt(KEY_PORT, DEFAULT_PORT)
}
