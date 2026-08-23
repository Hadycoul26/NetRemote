package com.example.netremote

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Relance le serveur apres un redemarrage ou une mise a jour de l'app. */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in HANDLED) return
        if (!Prefs.isServing(context)) return

        ServerService.start(context)
    }

    private companion object {
        val HANDLED = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
