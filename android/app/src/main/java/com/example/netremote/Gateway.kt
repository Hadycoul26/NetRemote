package com.example.netremote

import android.content.Context
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import java.net.Inet4Address

/**
 * Adresse de l'appareil qui partage la connexion.
 *
 * Quand un telephone heberge un point d'acces, il est la passerelle par defaut
 * de ses clients. Il n'y a donc rien a decouvrir ni a saisir : la reponse est
 * deja dans la table de routage.
 */
object Gateway {

    fun find(context: Context): String? = fromLinkProperties(context) ?: fromDhcp(context)

    /** Voie moderne : la route par defaut du reseau actif. */
    private fun fromLinkProperties(context: Context): String? = try {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val network = manager?.activeNetwork
        val properties = network?.let { manager.getLinkProperties(it) }

        properties?.routes
            ?.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway
            ?.hostAddress
    } catch (e: Exception) {
        null
    }

    /** Repli : certaines surcouches n'exposent pas la route par defaut. */
    @Suppress("DEPRECATION")
    private fun fromDhcp(context: Context): String? = try {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        val gateway = wifi?.dhcpInfo?.gateway ?: 0
        if (gateway == 0) {
            null
        } else {
            // DhcpInfo stocke l'adresse en little-endian.
            "%d.%d.%d.%d".format(
                gateway and 0xFF,
                gateway shr 8 and 0xFF,
                gateway shr 16 and 0xFF,
                gateway shr 24 and 0xFF
            )
        }
    } catch (e: Exception) {
        null
    }
}
