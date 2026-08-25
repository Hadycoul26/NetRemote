package com.example.netremote

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Rallumer l'ecran a distance.
 *
 * Necessaire parce que le pilotage passe par des gestes : un geste envoye sur
 * un ecran eteint ne touche rien. C'est la seule dependance qui reste entre le
 * telephone et le monde physique.
 *
 * Deux voies, essayees dans cet ordre, parce qu'aucune n'est garantie :
 *
 *  1. Un verrou d'ecran avec ACQUIRE_CAUSES_WAKEUP. Deprecie depuis longtemps,
 *     toujours efficace sur beaucoup d'appareils, et il ne coute qu'une
 *     permission ordinaire.
 *  2. Une notification a intention plein ecran, qui allume l'ecran puis
 *     disparait aussitot. C'est le chemin sanctionne par le systeme : on ne
 *     lance pas l'activite nous-memes, on demande au systeme de le faire.
 */
object Wake {

    private const val TAG = "Wake"
    private const val CHANNEL = "netremote_wake"
    private const val NOTIF_ID = 7311

    fun wake(context: Context): String {
        val power = context.getSystemService(PowerManager::class.java)
        val avant = power?.isInteractive == true
        if (avant) return "écran déjà allumé"

        val notes = mutableListOf<String>()

        try {
            @Suppress("DEPRECATION")
            val lock = power?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "netremote:wake"
            )
            lock?.acquire(6000L)
            notes += "verrou d'écran"
        } catch (e: Exception) {
            notes += "verrou refusé (" + e.javaClass.simpleName + ")"
        }

        if (power?.isInteractive != true) {
            notes += fullScreenNotification(context)
        }

        Thread.sleep(600)
        val apres = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        return (if (apres) "écran allumé" else "écran toujours éteint") +
            " (" + notes.joinToString(", ") + ")"
    }

    /**
     * La notification n'est la que pour son effet de bord : le systeme allume
     * l'ecran pour l'afficher. Elle est retiree tout de suite apres — la garder
     * afficherait un avis vide dont l'utilisateur n'a rien a faire.
     */
    private fun fullScreenNotification(context: Context): String {
        val manager = context.getSystemService(NotificationManager::class.java)
            ?: return "gestionnaire de notifications absent"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            !manager.canUseFullScreenIntent()
        ) {
            return "intention plein écran non autorisée"
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Réveil", NotificationManager.IMPORTANCE_HIGH)
            )
        }

        val intent = Intent(context, WakeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        val pending = PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_signal)
            .setContentTitle(context.getString(R.string.app_name))
            .setCategory(Notification.CATEGORY_ALARM)
            .setFullScreenIntent(pending, true)
            .setAutoCancel(true)
            .build()

        return try {
            manager.notify(NOTIF_ID, notification)
            Handler(Looper.getMainLooper()).postDelayed({ manager.cancel(NOTIF_ID) }, 1500L)
            "notification plein écran"
        } catch (e: Exception) {
            Log.w(TAG, "Notification refusée", e)
            "notification refusée (" + e.javaClass.simpleName + ")"
        }
    }
}

/**
 * Une activite qui n'affiche rien et ne vit qu'un instant.
 *
 * Son unique role est de porter setTurnScreenOn : c'est l'activite affichee qui
 * allume l'ecran, pas la notification elle-meme. Elle se termine des qu'elle a
 * servi.
 */
class WakeActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 800L)
    }
}
