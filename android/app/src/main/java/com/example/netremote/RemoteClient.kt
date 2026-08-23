package com.example.netremote

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Ce que le client sait de l'appareil cible. */
data class RemoteState(
    val reachable: Boolean,
    val device: String,
    val connected: Boolean,
    val detail: String,
    val warning: String
)

/**
 * Parle au serveur NetRemote de l'appareil qui partage sa connexion.
 *
 * A appeler hors du thread principal : ce sont des appels reseau bloquants.
 */
object RemoteClient {

    private const val TAG = "RemoteClient"
    private const val TIMEOUT_MS = 4000

    fun state(host: String, port: Int): RemoteState {
        val body = get(host, port, "/api/state")
            ?: return RemoteState(false, "", false, "appareil injoignable", "")

        return try {
            val json = JSONObject(body)
            RemoteState(
                reachable = true,
                device = json.optString("device", "appareil inconnu"),
                connected = json.optBoolean("connected", false),
                detail = json.optString("detail", ""),
                warning = json.optString("warning", "")
            )
        } catch (e: Exception) {
            RemoteState(false, "", false, "réponse illisible", "")
        }
    }

    fun set(host: String, port: Int, on: Boolean): ActionResult {
        val body = get(host, port, "/api/set?on=" + if (on) "1" else "0")
            ?: return ActionResult(false, "appareil injoignable")

        return try {
            val json = JSONObject(body)
            ActionResult(json.optBoolean("ok", false), json.optString("detail", ""))
        } catch (e: Exception) {
            ActionResult(false, "réponse illisible")
        }
    }

    private fun get(host: String, port: Int, path: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL("http://$host:$port$path").openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                requestMethod = "GET"
                useCaches = false
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.w(TAG, "Appel $path echoue : " + e.javaClass.simpleName)
            null
        } finally {
            connection?.disconnect()
        }
    }
}
