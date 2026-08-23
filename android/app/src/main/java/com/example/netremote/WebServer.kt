package com.example.netremote

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/**
 * Serveur HTTP local, heberge par [ServerService].
 *
 * Aucune authentification : le point d'acces est prive et son proprietaire
 * choisit qui s'y connecte. La barriere est le mot de passe du point d'acces,
 * pas une cle supplementaire.
 *
 * Couper la cible ne deconnecte pas le client : on coupe les donnees mobiles,
 * pas le point d'acces. Le Wi-Fi local reste debout, donc le client garde la
 * main pour rallumer.
 */
class WebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            when (session.uri) {
                "/", "/index.html" -> servePage()
                "/api/state" -> json(buildState())
                "/api/set" -> {
                    val on = (session.parameters["on"]?.firstOrNull() ?: "1") == "1"
                    json(apply(on))
                }
                else -> json(JSONObject().put("error", "not found"), Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Requete en erreur", e)
            json(
                JSONObject().put("error", e.javaClass.simpleName),
                Response.Status.INTERNAL_ERROR
            )
        }
    }

    private fun buildState(): JSONObject {
        val warning = when (ShizukuShell.state()) {
            ShizukuState.ABSENT ->
                "Shizuku n'est pas lancé : l'état est lisible, mais le basculement échouera."
            ShizukuState.NON_AUTORISE ->
                "Permission Shizuku non accordée sur l'appareil cible."
            ShizukuState.PRET -> ""
        }

        return JSONObject()
            .put("platform", "android")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("connected", MobileData.isEnabled(context) == true)
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

    private fun json(
        payload: JSONObject,
        status: Response.Status = Response.Status.OK
    ): Response = newFixedLengthResponse(
        status, "application/json; charset=utf-8", payload.toString()
    )

    private companion object {
        const val TAG = "WebServer"
    }
}
