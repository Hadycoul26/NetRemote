package com.example.netremote

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serveur HTTP local, heberge par [ServerService].
 *
 * Expose exactement la meme API que le serveur PC : le client web est un seul
 * fichier, servi ici depuis les assets et la-bas depuis le disque.
 *
 * A la difference du serveur PC, couper la cible ne deconnecte pas le client :
 * on coupe les donnees mobiles, pas le point d'acces. Le Wi-Fi local reste
 * debout, donc la page reste joignable pour rallumer. Aucun garde-fou
 * anti-auto-deconnexion n'est necessaire de ce cote.
 */
class WebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val path = session.uri
            if (path == "/" || path == "/index.html") return servePage()

            val token = session.parameters["k"]?.firstOrNull()
            if (!Prefs.isTokenValid(context, token)) {
                return json(Response.Status.UNAUTHORIZED, JSONObject().put("error", "unauthorized"))
            }

            when (path) {
                "/api/state" -> json(Response.Status.OK, buildState())
                "/api/set" -> {
                    val on = (session.parameters["on"]?.firstOrNull() ?: "1") == "1"
                    json(Response.Status.OK, apply(on))
                }
                else -> json(Response.Status.NOT_FOUND, JSONObject().put("error", "not found"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Requete en erreur", e)
            json(
                Response.Status.INTERNAL_ERROR,
                JSONObject().put("error", e.javaClass.simpleName)
            )
        }
    }

    private fun buildState(): JSONObject {
        val enabled = MobileData.isEnabled(context)

        val warning = when (ShizukuShell.state()) {
            ShizukuState.ABSENT ->
                "Shizuku n'est pas lancé : la lecture fonctionne, le basculement échouera. " +
                    "À relancer après chaque redémarrage du téléphone."
            ShizukuState.NON_AUTORISE ->
                "Permission Shizuku non accordée : ouvrez NetRemote sur le téléphone."
            ShizukuState.PRET -> ""
        }

        return JSONObject()
            .put("platform", "android")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("connected", enabled == true)
            .put("detail", MobileData.describe(context))
            .put("warning", warning)
    }

    private fun apply(enable: Boolean): JSONObject {
        val result = MobileData.set(context, enable)
        return JSONObject()
            .put("ok", result.ok)
            .put("detail", result.detail)
    }

    private fun servePage(): Response {
        return try {
            val html = context.assets.open("index.html").use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            }
            newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                "Client web introuvable dans les assets : " + e.javaClass.simpleName
            )
        }
    }

    private fun json(status: Response.Status, payload: JSONObject): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString())

    private companion object {
        const val TAG = "WebServer"
    }
}
