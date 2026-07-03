package com.example.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey val id: String,
    val text: String,
    val isFromMe: Boolean,
    val timestamp: String,
    val isEncrypted: Boolean = true,
    val protocolName: String = "sys-vpn (WireGuard)"
)
