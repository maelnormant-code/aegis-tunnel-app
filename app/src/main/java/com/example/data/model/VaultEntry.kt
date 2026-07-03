package com.example.data.model

data class VaultEntry(
    val id: String,
    val title: String,
    val username: String = "",
    val password: String = "",
    val url: String = "",
    val notes: String = "",
    val category: String = "Logins", // "Logins", "Credit & Debit Cards", "SSH Keys", "Secure Notes"
    val totpSecret: String = ""
)
