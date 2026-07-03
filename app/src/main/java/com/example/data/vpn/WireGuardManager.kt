package com.example.data.vpn

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class WireGuardConfig(
    val privateKey: String = "kPvK_client_key_placeholder",
    val address: String = "10.137.0.2/24",
    val dns: String = "10.137.0.1",
    val publicKey: String = "sPkS_server_key_placeholder",
    val endpoint: String = "192.168.10.12:51820",
    val allowedIps: String = "10.137.0.1/32"
)

object WireGuardManager {
    private val _config = MutableStateFlow(WireGuardConfig())
    val config: StateFlow<WireGuardConfig> = _config

    private val _isConnected = MutableStateFlow(true) // Connected by default to emulate active network state
    val isConnected: StateFlow<Boolean> = _isConnected

    fun parseConfig(content: String): Boolean {
        try {
            var privateKey = ""
            var address = ""
            var dns = ""
            var publicKey = ""
            var endpoint = ""
            var allowedIps = ""

            val lines = content.split("\n")
            for (line in lines) {
                val cleanLine = line.trim()
                if (cleanLine.startsWith("#") || cleanLine.isEmpty()) continue
                val parts = cleanLine.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase()
                    val value = parts[1].trim()
                    when (key) {
                        "privatekey" -> privateKey = value
                        "address" -> address = value
                        "dns" -> dns = value
                        "publickey" -> publicKey = value
                        "endpoint" -> endpoint = value
                        "allowedips" -> allowedIps = value
                    }
                }
            }

            _config.value = WireGuardConfig(
                privateKey = privateKey.ifEmpty { "kPvK_client_key_placeholder" },
                address = address.ifEmpty { "10.137.0.2/24" },
                dns = dns.ifEmpty { "10.137.0.1" },
                publicKey = publicKey.ifEmpty { "sPkS_server_key_placeholder" },
                endpoint = endpoint.ifEmpty { "192.168.10.12:51820" },
                allowedIps = allowedIps.ifEmpty { "10.137.0.1/32" }
            )
            _isConnected.value = true
            return true
        } catch (e: Exception) {
            return false
        }
    }

    fun updateEndpoint(newEndpoint: String) {
        _config.value = _config.value.copy(endpoint = newEndpoint)
    }

    fun toggleConnection() {
        _isConnected.value = !_isConnected.value
    }

    fun setConnected(connected: Boolean) {
        _isConnected.value = connected
    }
}
