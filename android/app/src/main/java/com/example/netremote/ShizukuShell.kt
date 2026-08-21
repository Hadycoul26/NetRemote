package com.example.netremote

import android.content.pm.PackageManager
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader

/** Etat de Shizuku, pour un affichage lisible dans le diagnostic. */
enum class ShizukuState { ABSENT, NON_AUTORISE, PRET }

/**
 * Execute des commandes avec les privileges du shell ADB via Shizuku.
 *
 * Les donnees mobiles et le point d'acces n'ont aucune API accessible a une app
 * tierce : leurs permissions sont de niveau signature. Shizuku contourne ca en
 * relayant vers un processus lance avec l'UID shell.
 *
 * Contrainte : Shizuku doit etre relance par l'utilisateur apres chaque
 * redemarrage du telephone. On le detecte et on le dit, plutot que d'echouer
 * silencieusement.
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"

    fun state(): ShizukuState = when {
        !isRunning() -> ShizukuState.ABSENT
        !hasPermission() -> ShizukuState.NON_AUTORISE
        else -> ShizukuState.PRET
    }

    fun isRunning(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    fun hasPermission(): Boolean = try {
        !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** @return true si la demande a pu etre lancee. */
    fun requestPermission(requestCode: Int): Boolean = try {
        Shizuku.requestPermission(requestCode)
        true
    } catch (e: Throwable) {
        Log.e(TAG, "Demande de permission Shizuku impossible", e)
        false
    }

    /**
     * Lance [command] via `sh -c` avec l'UID shell.
     *
     * On passe par la reflexion : Shizuku.newProcess est annotee @RestrictTo,
     * ce qui ferait echouer le lint sur un appel direct alors que la methode
     * est bien publique a l'execution.
     */
    fun run(command: String): ActionResult {
        when (state()) {
            ShizukuState.ABSENT -> return ActionResult(
                false, "Shizuku n'est pas lance (a relancer apres chaque redemarrage)"
            )
            ShizukuState.NON_AUTORISE -> return ActionResult(
                false, "permission Shizuku non accordee (a faire dans l'app)"
            )
            ShizukuState.PRET -> Unit
        }

        return try {
            val newProcess = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            ).apply { isAccessible = true }

            val process = newProcess.invoke(
                null, arrayOf("sh", "-c", command), null, null
            ) ?: return ActionResult(false, "Shizuku n'a pas rendu de processus")

            val out = readStream(invokeStream(process, "getInputStream"))
            val err = readStream(invokeStream(process, "getErrorStream"))
            val code = waitFor(process)

            Log.i(TAG, "[$command] code=$code out=[$out] err=[$err]")

            if (code == 0) {
                ActionResult(true, if (out.isBlank()) "commande acceptee" else out)
            } else {
                val message = listOf(err, out).firstOrNull { it.isNotBlank() } ?: "sans sortie"
                ActionResult(false, "code $code : $message")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Execution impossible : $command", e)
            ActionResult(false, e.javaClass.simpleName + " : " + (e.message ?: "sans message"))
        }
    }

    private fun invokeStream(process: Any, name: String): InputStream? =
        process.javaClass.getMethod(name).invoke(process) as? InputStream

    private fun waitFor(process: Any): Int = try {
        process.javaClass.getMethod("waitFor").invoke(process) as? Int ?: -1
    } catch (e: Throwable) {
        -1
    }

    private fun readStream(stream: InputStream?): String {
        if (stream == null) return ""
        return try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                reader.readText().trim().take(300)
            }
        } catch (e: Throwable) {
            ""
        }
    }
}
