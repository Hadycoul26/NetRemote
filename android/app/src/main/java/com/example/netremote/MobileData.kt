package com.example.netremote

import android.content.Context
import android.provider.Settings
import android.telephony.TelephonyManager

/** Resultat d'une action, avec le motif exact en cas d'echec. */
data class ActionResult(val ok: Boolean, val detail: String)

/**
 * Lecture et bascule des donnees mobiles.
 *
 * La lecture est libre. L'ecriture passe obligatoirement par Shizuku :
 * TelephonyManager.setDataEnabled() exige MODIFY_PHONE_STATE, permission de
 * niveau signature reservee aux apps systeme. Aucune app tierce ne peut
 * l'obtenir, quelle que soit son architecture.
 */
object MobileData {

    /** null si l'etat est illisible sur cet appareil. */
    fun isEnabled(context: Context): Boolean? {
        // Settings.Global "mobile_data" est lisible sans permission et suit
        // l'interrupteur systeme sur la grande majorite des appareils.
        try {
            val value = Settings.Global.getInt(context.contentResolver, "mobile_data", -1)
            if (value >= 0) return value == 1
        } catch (e: Exception) {
            // On tente la voie suivante.
        }

        return try {
            val telephony = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            telephony?.isDataEnabled
        } catch (e: Exception) {
            null
        }
    }

    fun set(context: Context, enable: Boolean): ActionResult {
        val command = if (enable) "svc data enable" else "svc data disable"
        val result = ShizukuShell.run(command)

        if (!result.ok) return result

        return ActionResult(
            true,
            if (enable) "données mobiles activées" else "données mobiles coupées"
        )
    }

    fun describe(context: Context): String = when (isEnabled(context)) {
        true -> "données mobiles activées"
        false -> "données mobiles coupées"
        null -> "état des données illisible sur cet appareil"
    }
}
