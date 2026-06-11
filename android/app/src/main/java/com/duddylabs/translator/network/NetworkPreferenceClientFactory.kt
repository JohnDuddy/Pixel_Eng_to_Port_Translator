package com.duddylabs.translator.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import okhttp3.Dns
import okhttp3.OkHttpClient
import java.net.InetAddress
import java.net.URI
import java.net.UnknownHostException

class NetworkPreferenceClientFactory(context: Context) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun clientFor(baseClient: OkHttpClient, preferCellularData: Boolean, targetUrl: String): OkHttpClient {
        if (!NetworkPreferencePolicy.shouldPreferCellular(preferCellularData, targetUrl)) {
            return baseClient
        }

        val network = cellularNetwork() ?: return baseClient
        return baseClient.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(NetworkDns(network))
            .build()
    }

    private fun cellularNetwork(): Network? =
        connectivityManager.allNetworks.firstOrNull { network ->
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private class NetworkDns(private val network: Network) : Dns {
        @Throws(UnknownHostException::class)
        override fun lookup(hostname: String): List<InetAddress> =
            network.getAllByName(hostname).toList()
    }
}

object NetworkPreferencePolicy {
    fun shouldPreferCellular(preferCellularData: Boolean, targetUrl: String): Boolean =
        preferCellularData && !isLocalOrPrivateUrl(targetUrl)

    fun isLocalOrPrivateUrl(targetUrl: String): Boolean {
        val host = extractHost(targetUrl).lowercase()
        if (host.isBlank()) {
            return false
        }

        if (host == "localhost" || host.endsWith(".local")) {
            return true
        }

        if (host == "::1" ||
            host.startsWith("fe80:") ||
            host.startsWith("fc") ||
            host.startsWith("fd")
        ) {
            return true
        }

        val octets = host.split(".").map { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it == null || it !in 0..255 }) {
            return false
        }

        val first = octets[0]!!
        val second = octets[1]!!
        return first == 0 ||
            first == 10 ||
            first == 127 ||
            first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 169 && second == 254) ||
            (first == 172 && second in 16..31) ||
            (first == 192 && second == 168)
    }

    private fun extractHost(targetUrl: String): String {
        val trimmed = targetUrl.trim()
        val uriHost = runCatching { URI(trimmed).host }.getOrNull()
        if (!uriHost.isNullOrBlank()) {
            return uriHost.trim('[', ']')
        }

        return trimmed
            .substringAfter("://", trimmed)
            .substringBefore("/")
            .substringBefore(":")
            .trim('[', ']')
    }
}
