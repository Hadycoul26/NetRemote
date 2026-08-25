package com.example.netremote

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.ByteArrayInputStream

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
 *
 * Deux familles d'API cohabitent :
 *
 *  - le PILOTAGE (`/api/screen`, `/api/tap`, `/api/key`…), qui ne comprend rien
 *    a ce qu'il montre et se contente de transmettre image et gestes. C'est la
 *    voie principale : elle ne peut pas se tromper d'element, puisque c'est
 *    l'utilisateur qui regarde et qui vise.
 *  - la BASCULE directe (`/api/set`), qui reste pour les cas ou elle marche,
 *    mais qui depend de l'interface de l'appareil et n'est donc plus le socle.
 */
class WebServer(
    private val context: Context,
    port: Int
) : NanoHTTPD(port) {

    override fun serve(session: IHTTPSession): Response {
        return try {
            val p = session.parameters
            when (session.uri) {
                "/", "/index.html" -> servePage()

                "/api/info" -> json(buildInfo())
                "/api/state" -> json(buildState())
                "/api/set" -> json(apply((p["on"]?.firstOrNull() ?: "1") == "1"))

                "/api/screen" -> serveScreen(int(p["w"]?.firstOrNull(), 540))

                "/api/tap" -> json(
                    done(RemoteControl.tap(int(p["x"]?.firstOrNull(), -1), int(p["y"]?.firstOrNull(), -1)))
                )

                "/api/press" -> json(
                    done(RemoteControl.longPress(int(p["x"]?.firstOrNull(), -1), int(p["y"]?.firstOrNull(), -1)))
                )

                "/api/swipe" -> json(
                    done(
                        RemoteControl.swipe(
                            int(p["x1"]?.firstOrNull(), -1), int(p["y1"]?.firstOrNull(), -1),
                            int(p["x2"]?.firstOrNull(), -1), int(p["y2"]?.firstOrNull(), -1),
                            int(p["ms"]?.firstOrNull(), 250).toLong()
                        )
                    )
                )

                "/api/key" -> json(done(RemoteControl.key(p["name"]?.firstOrNull().orEmpty())))

                "/api/wake" -> json(
                    JSONObject().put("ok", true).put("detail", Wake.wake(context))
                )

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

    // --- Pilotage ----------------------------------------------------------

    /**
     * L'image de l'ecran, telle quelle.
     *
     * Sans cache cote client : deux images identiques a une seconde d'intervalle
     * ne doivent pas se confondre, sinon on croit l'ecran fige.
     */
    private fun serveScreen(maxWidth: Int): Response {
        // Trace d'entree : le passage precedent n'a rien laisse dans le journal
        // alors que la reponse etait bien la notre. Sans preuve que le
        // gestionnaire s'execute, on ne peut pas savoir ou ca casse.
        Log.i(TAG, "/api/screen demandé, largeur " + maxWidth)
        val jpeg = RemoteControl.screenshot(maxWidth)
            ?: return json(
                JSONObject()
                    .put("error", "capture indisponible")
                    .put("detail", captureProblem()),
                Response.Status.SERVICE_UNAVAILABLE
            )

        Log.i(TAG, "/api/screen : " + jpeg.size + " octets servis")
        val response = newFixedLengthResponse(
            Response.Status.OK, "image/jpeg",
            ByteArrayInputStream(jpeg), jpeg.size.toLong()
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun captureProblem(): String = when {
        !RemoteControl.ready() ->
            "Service d'accessibilité inactif : Réglages → Accessibilité → NetRemote."
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
            "La capture d'écran par accessibilité demande Android 11 ou plus récent."
        !RemoteControl.canCapture() ->
            "Le service n'a pas la capacité de capture. Désactivez puis réactivez " +
                "NetRemote dans Réglages → Accessibilité : la capacité est lue au " +
                "moment où le service est activé."
        else ->
            "Le système a refusé la capture. L'écran est-il allumé ?"
    }

    private fun buildInfo(): JSONObject {
        val (width, height) = RemoteControl.screenSize()
        return JSONObject()
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("android", Build.VERSION.RELEASE)
            .put("width", width)
            .put("height", height)
            .put("ready", RemoteControl.ready())
            .put("canCapture", RemoteControl.canCapture())
            .put("data", MobileData.describe(context))
            .put("problem", if (RemoteControl.ready() && RemoteControl.canCapture()) "" else captureProblem())
    }

    private fun done(ok: Boolean): JSONObject = JSONObject()
        .put("ok", ok)
        .put(
            "detail",
            if (ok) "geste envoyé"
            else "refusé — service d'accessibilité inactif ou coordonnées invalides"
        )

    private fun int(raw: String?, fallback: Int): Int =
        raw?.trim()?.toIntOrNull() ?: fallback

    // --- Bascule directe ---------------------------------------------------

    private fun buildState(): JSONObject {
        // Deux voies independantes basculent les donnees. L'avertissement ne
        // doit alarmer que si les DEUX manquent : signaler l'absence de Shizuku
        // alors que l'accessibilite fait le travail est un mensonge.
        val shizukuReady = ShizukuShell.state() == ShizukuState.PRET
        val accessibilityReady = DataToggleService.isRunning()

        val method = when {
            shizukuReady -> "Shizuku"
            accessibilityReady -> "accessibilité"
            else -> "aucune"
        }

        val warning = if (shizukuReady || accessibilityReady) {
            ""
        } else {
            "Aucune voie de bascule active sur l'appareil cible. Activez le " +
                "service d'accessibilité de NetRemote dans Réglages → Accessibilité. " +
                "Shizuku est facultatif."
        }

        return JSONObject()
            .put("platform", "android")
            .put("device", Build.MANUFACTURER + " " + Build.MODEL)
            .put("connected", MobileData.isEnabled(context) == true)
            .put("detail", MobileData.describe(context))
            .put("method", method)
            .put("warning", warning)
    }

    private fun apply(enable: Boolean): JSONObject {
        val result = MobileData.set(context, enable)
        return JSONObject()
            .put("ok", result.ok)
            .put("detail", result.detail)
    }

    // --- Outils ------------------------------------------------------------

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
