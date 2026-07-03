package com.example.service

import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.example.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class RoutingVpnService : VpnService() {
    private var vpnInterface: ParcelFileDescriptor? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP") {
            stopVpn()
        } else {
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        serviceScope.launch {
            try {
                // Ensure existing interface is closed
                vpnInterface?.close()

                val db = AppDatabase.getDatabase(applicationContext)
                // Read all active and enabled rules
                val activeRules = db.routingRuleDao().getAllRules().first()

                val builder = Builder()
                    .setSession("Aegis Private Tunnel")
                    .setMtu(1400)
                    .addAddress("10.137.0.2", 24)
                    .addRoute("10.137.0.1", 32) // Tunnel to sys-vpn gateway
                    .addDnsServer("10.137.0.1")

                var appsAdded = 0
                for (rule in activeRules) {
                    if (rule.isEnabled) {
                        try {
                            builder.addAllowedApplication(rule.packageName)
                            appsAdded++
                            Log.d("RoutingVpnService", "Routing ${rule.appName} (${rule.packageName}) -> ${rule.destination}")
                        } catch (e: PackageManager.NameNotFoundException) {
                            Log.e("RoutingVpnService", "App not found: ${rule.packageName}", e)
                        }
                    }
                }

                // If no specific apps selected, route default fallback (e.g., self) to allow seamless connection
                if (appsAdded == 0) {
                    try {
                        builder.addAllowedApplication(packageName)
                    } catch (e: Exception) {}
                }

                vpnInterface = builder.establish()
                Log.d("RoutingVpnService", "Aegis VPN Interface Established with $appsAdded app-specific tunnels.")
            } catch (e: Exception) {
                Log.e("RoutingVpnService", "Failed to start VPN service", e)
            }
        }
    }

    private fun stopVpn() {
        try {
            vpnInterface?.close()
            vpnInterface = null
            stopSelf()
        } catch (e: Exception) {
            Log.e("RoutingVpnService", "Error stopping VPN interface", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopVpn()
    }
}
