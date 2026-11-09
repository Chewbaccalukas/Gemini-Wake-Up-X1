package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun CreatePromptDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, promptText: String) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var promptText by remember { mutableStateOf("") }
    var titleError by remember { mutableStateOf(false) }
    var textError by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Create Custom Prompt Shortcut", style = MaterialTheme.typography.titleMedium)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add a reusable prompt shortcut button to access directly from the chat screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = title,
                    onValueChange = {
                        title = it
                        titleError = false
                    },
                    label = { Text("Shortcut Label") },
                    placeholder = { Text("e.g. Bug Finder, Key Takeaways") },
                    singleLine = true,
                    isError = titleError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_prompt_title_input")
                )

                OutlinedTextField(
                    value = promptText,
                    onValueChange = {
                        promptText = it
                        textError = false
                    },
                    label = { Text("Prompt Instructions") },
                    placeholder = { Text("e.g. Analyze the text and find 3 bugs with solutions...") },
                    minLines = 3,
                    maxLines = 6,
                    isError = textError,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("custom_prompt_text_input")
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (title.isBlank()) {
                        titleError = true
                        return@Button
                    }
                    if (promptText.isBlank()) {
                        textError = true
                        return@Button
                    }
                    onSave(title.trim(), promptText.trim())
                },
                modifier = Modifier.testTag("save_custom_prompt_button")
            ) {
                Text("Save Shortcut")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
