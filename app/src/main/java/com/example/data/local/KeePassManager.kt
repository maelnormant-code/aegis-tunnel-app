package com.example.data.local

import com.example.data.model.VaultEntry
import org.linguafranca.pwdb.kdbx.KdbxCreds
import org.linguafranca.pwdb.kdbx.simple.SimpleDatabase
import org.linguafranca.pwdb.kdbx.simple.SimpleEntry
import org.linguafranca.pwdb.kdbx.simple.SimpleGroup
import java.io.InputStream
import java.util.UUID

object KeePassManager {
    fun decryptVault(inputStream: InputStream, password: String): List<VaultEntry> {
        val creds = KdbxCreds(password.toByteArray())
        val db = SimpleDatabase.load(creds, inputStream)
        val entries = mutableListOf<VaultEntry>()
        val rootGroup = db.rootGroup
        traverseGroup(rootGroup, entries)
        return entries
    }

    private fun traverseGroup(group: SimpleGroup, outList: MutableList<VaultEntry>) {
        for (entry in group.entries) {
            val title = entry.title ?: "Untitled"
            val notes = entry.notes ?: ""
            
            // Determine category based on title, group, or notes
            val category = when {
                title.contains("SSH", ignoreCase = true) || notes.contains("private key", ignoreCase = true) -> "SSH Keys"
                title.contains("Card", ignoreCase = true) || title.contains("Visa", ignoreCase = true) || title.contains("Mastercard", ignoreCase = true) -> "Credit & Debit Cards"
                notes.contains("secure note", ignoreCase = true) || notes.length > 200 && entry.password.isEmpty() -> "Secure Notes"
                else -> "Logins"
            }

            // Extract TOTP secret if any
            var totpSecret = ""
            val otpUrl = entry.getProperty("otp") ?: ""
            if (otpUrl.startsWith("otpauth://", ignoreCase = true)) {
                totpSecret = extractSecretFromOtpUrl(otpUrl)
            } else if (notes.contains("secret=", ignoreCase = true)) {
                val match = Regex("secret=([A-Z2-7]+)", RegexOption.IGNORE_CASE).find(notes)
                if (match != null) {
                    totpSecret = match.groupValues[1]
                }
            }

            outList.add(
                VaultEntry(
                    id = entry.uuid?.toString() ?: UUID.randomUUID().toString(),
                    title = title,
                    username = entry.username ?: "",
                    password = entry.password ?: "",
                    url = entry.url ?: "",
                    notes = notes,
                    category = category,
                    totpSecret = totpSecret
                )
            )
        }

        for (subgroup in group.groups) {
            traverseGroup(subgroup, outList)
        }
    }

    private fun extractSecretFromOtpUrl(otpUrl: String): String {
        try {
            val queryStart = otpUrl.indexOf('?')
            if (queryStart > 0) {
                val query = otpUrl.substring(queryStart + 1)
                val params = query.split("&")
                for (param in params) {
                    val pair = param.split("=", limit = 2)
                    if (pair.size == 2) {
                        if (pair[0].trim().lowercase() == "secret") {
                            return pair[1].trim()
                        }
                    }
                }
            }
        } catch (e: Exception) {}
        return ""
    }
}
