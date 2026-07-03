package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "routing_rules")
data class RoutingRule(
    @PrimaryKey val packageName: String,
    val appName: String,
    val destination: String, // "sys-vpn", "sys-whonix", "sys-tor", "sys-i2p"
    val notes: String = "",
    val isEnabled: Boolean = true
)
