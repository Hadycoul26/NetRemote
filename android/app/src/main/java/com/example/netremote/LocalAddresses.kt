package com.example.netremote

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Adresses IPv4 locales du telephone, pour afficher l'URL a taper.
 *
 * On ne devine pas l'adresse du point d'acces (elle varie selon les
 * constructeurs : 192.168.43.1 historiquement, autre chose ailleurs) : on
 * enumere ce que le systeme declare.
 */
object LocalAddresses {

    fun list(): List<String> = try {
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .map { it.hostAddress.orEmpty() }
            }
            .filter { it.isNotBlank() }
            .distinct()
    } catch (e: Exception) {
        emptyList()
    }

    /** L'interface du point d'acces porte presque toujours une adresse en .1 */
    fun mostLikelyHotspot(): String? =
        list().firstOrNull { it.endsWith(".1") } ?: list().firstOrNull()
}
