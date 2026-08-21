package com.example.netremote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import androidx.core.content.ContextCompat

/**
 * Service de premier plan hebergeant le serveur HTTP.
 *
 * Le premier plan n'est pas decoratif : sans lui, le systeme tue le processus
 * apres quelques minutes en arriere-plan et le serveur devient injoignable
 * exactement quand on en a besoin.
 */
class ServerService : Service() {

    private var server: WebServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Obligatoire dans les 5 s suivant startForegroundService().
        startForeground(NOTIF_ID, buildNotification())

        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        reconcile()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopServer()
        super.onDestroy()
    }

    /** Aligne l'etat reel du serveur sur la preference, dans les deux sens. */
    private fun reconcile() {
        val shouldRun = Prefs.isEnabled(this)
        if (shouldRun == (server != null)) return

        if (shouldRun) startServer() else stopServer()
        updateNotification()
    }

    private fun startServer() {
        try {
            server = WebServer(this, Prefs.port(this)).apply {
                start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            }
            lastError = null
        } catch (e: Exception) {
            server = null
            lastError = e.javaClass.simpleName + " : " + (e.message ?: "port occupé ?")
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
        } catch (e: Exception) {
            // Le serveur part de toute facon.
        }
        server = null
    }

    // --- Notification ----------------------------------------------------

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun updateNotification() {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, buildNotification())
        } catch (e: Exception) {
            // Notifications refusees : le serveur fonctionne quand meme.
        }
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, ServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )

        val address = LocalAddresses.mostLikelyHotspot()
        val detail = if (server == null) {
            lastError ?: getString(R.string.notif_stopped)
        } else if (address == null) {
            getString(R.string.notif_no_address)
        } else {
            "http://" + address + ":" + Prefs.port(this) + "  •  " + MobileData.describe(this)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_signal)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail))
            .setContentIntent(openApp)
            .addAction(0, getString(R.string.action_stop), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "netremote_server"
        const val NOTIF_ID = 1
        const val ACTION_STOP = "com.example.netremote.action.STOP"

        @Volatile
        var isRunning: Boolean = false
            internal set

        @Volatile
        var lastError: String? = null
            internal set

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context, Intent(context, ServerService::class.java)
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ServerService::class.java))
        }
    }
}
