package com.example.service

import android.app.assist.AssistStructure
import android.os.CancellationSignal
import android.service.autofill.*
import android.view.View
import android.view.autofill.AutofillId
import android.view.autofill.AutofillValue
import android.widget.RemoteViews
import com.example.R
import com.example.data.local.KeePassManager
import com.example.data.model.VaultEntry

class AegisAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback
    ) {
        val contexts = request.fillContexts
        if (contexts.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        val structure = contexts[contexts.size - 1].structure
        val usernameFields = mutableListOf<AssistStructure.ViewNode>()
        val passwordFields = mutableListOf<AssistStructure.ViewNode>()

        // Recursively traverse structure to locate login fields
        val nodesCount = structure.windowNodeCount
        for (i in 0 until nodesCount) {
            val node = structure.getWindowNodeAt(i).rootViewNode
            locateLoginFields(node, usernameFields, passwordFields)
        }

        if (usernameFields.isEmpty() && passwordFields.isEmpty()) {
            callback.onSuccess(null)
            return
        }

        // We found fields! Build Autofill Response
        val responseBuilder = FillResponse.Builder()
        
        // Mock entries if vault is not fully loaded in service thread, or load active decrypted list
        // In real use, this fetches unlocked items held in memory
        val activeEntries = listOf(
            VaultEntry("1", "Signal Desktop", "aegis_user", "WhonixSecuredPassword1", "signal.org"),
            VaultEntry("2", "Heimdall Admin Portal", "admin", "CopilotHandshakeSuperSecret", "heimdall.local")
        )

        for (entry in activeEntries) {
            val datasetBuilder = Dataset.Builder()
            var added = false

            val presentation = RemoteViews(packageName, android.R.layout.simple_list_item_1).apply {
                setTextViewText(android.R.id.text1, "Aegis: ${entry.title} (${entry.username})")
            }

            if (usernameFields.isNotEmpty() && entry.username.isNotEmpty()) {
                val userFieldId = usernameFields[0].autofillId
                if (userFieldId != null) {
                    datasetBuilder.setValue(userFieldId, AutofillValue.forText(entry.username), presentation)
                    added = true
                }
            }

            if (passwordFields.isNotEmpty() && entry.password.isNotEmpty()) {
                val passFieldId = passwordFields[0].autofillId
                if (passFieldId != null) {
                    datasetBuilder.setValue(passFieldId, AutofillValue.forText(entry.password), presentation)
                    added = true
                }
            }

            if (added) {
                responseBuilder.addDataset(datasetBuilder.build())
            }
        }

        callback.onSuccess(responseBuilder.build())
    }

    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        // Handle credential capture and prompt to save in Aegis .kdbx
        callback.onSuccess()
    }

    private fun locateLoginFields(
        node: AssistStructure.ViewNode,
        usernameFields: MutableList<AssistStructure.ViewNode>,
        passwordFields: MutableList<AssistStructure.ViewNode>
    ) {
        val hints = node.autofillHints
        if (hints != null) {
            for (hint in hints) {
                if (hint.contains("username", ignoreCase = true) || hint.contains("email", ignoreCase = true)) {
                    usernameFields.add(node)
                } else if (hint.contains("password", ignoreCase = true)) {
                    passwordFields.add(node)
                }
            }
        }

        val className = node.className
        val idEntry = node.idEntry
        if (idEntry != null) {
            if (idEntry.contains("username", ignoreCase = true) || idEntry.contains("email", ignoreCase = true) || idEntry.contains("login", ignoreCase = true)) {
                if (!usernameFields.contains(node)) usernameFields.add(node)
            } else if (idEntry.contains("password", ignoreCase = true) || idEntry.contains("passwd", ignoreCase = true)) {
                if (!passwordFields.contains(node)) passwordFields.add(node)
            }
        }

        // Check view input type
        val inputType = node.inputType
        if (inputType != 0) {
            if (inputType and android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD != 0 || 
                inputType and android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD != 0 || 
                inputType and android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD != 0 || 
                inputType and android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD != 0) {
                if (!passwordFields.contains(node)) passwordFields.add(node)
            }
        }

        for (i in 0 until node.childCount) {
            locateLoginFields(node.getChildAt(i), usernameFields, passwordFields)
        }
    }
}
