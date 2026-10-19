package org.adaway.ui.compose

import java.net.Inet4Address
import java.net.NetworkInterface

internal data class ListenerAddressSuggestion(val network: String, val address: String, val ipv4: Boolean)

/** Enumerate assigned addresses only; never recommend an external DNS or guessed LAN address. */
internal fun listenerAddressSuggestions(): List<ListenerAddressSuggestion> = runCatching {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && !it.isPointToPoint && !it.isVirtual }
        .flatMap { network ->
            network.inetAddresses.toList()
                .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress && !it.isLinkLocalAddress && !it.isMulticastAddress }
                .mapNotNull { address ->
                    address.hostAddress?.takeIf { '%' !in it }?.let {
                        ListenerAddressSuggestion(network.name, it, address is Inet4Address)
                    }
                }
        }.distinctBy { it.address }.sortedWith(compareBy({ it.network }, { !it.ipv4 }, { it.address }))
}.getOrDefault(emptyList()) // API 30 can throw for virtual interfaces without a parent.
