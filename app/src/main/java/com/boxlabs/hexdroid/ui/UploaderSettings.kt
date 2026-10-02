package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.*

@Composable
internal fun UploaderSettings(s: UiSettings, update: (UiSettings.() -> UiSettings) -> Unit, saveToken: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var token by remember(s.uploadProvider, s.uploadEndpoint) { mutableStateOf("") }
    var saved by remember(s.uploadProvider, s.uploadEndpoint) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("File uploader", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Enable file and image uploads")
            Switch(s.uploadsEnabled, { value -> update { copy(uploadsEnabled = value) } })
        }
        Text("Uploads are off until enabled. Choose where attachments and snippets are hosted.", style = MaterialTheme.typography.bodySmall)
        if (s.uploadsEnabled) {
            Text("Images are stripped of metadata and converted to PNG before uploading. Animated images become a still frame; unsupported images are blocked.", style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Encrypt uploads with age")
                Switch(s.uploadAgeEnabled, { value -> update { copy(uploadAgeEnabled = value) } })
            }
            if (s.uploadAgeEnabled) {
                OutlinedTextField(s.uploadAgeRecipients, { value -> update { copy(uploadAgeRecipients = value) } }, label = { Text("Recipient public age1 keys, one per line") }, modifier = Modifier.fillMaxWidth())
                Text("Add the recipient's public key and your own if you also need access. Private keys stay with their owners.", style = MaterialTheme.typography.bodySmall)
                Text("To decrypt: download as attachment.age, then run age -d -i key.txt -o attachment attachment.age. Rename the decrypted file to .png for an image, or its original extension for a document.", style = MaterialTheme.typography.bodySmall)
            }

            Box {
                OutlinedButton(onClick = { expanded = true }) { Text(s.uploadProvider.label) }
                DropdownMenu(expanded, { expanded = false }) {
                    UploadProvider.entries.forEach { provider ->
                        DropdownMenuItem(text = { Text(provider.label) }, onClick = {
                            update { copy(uploadProvider = provider, uploadAllowHttp = false) }; expanded = false
                        })
                    }
                }
            }
            if (s.uploadProvider == UploadProvider.DROPFO) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Upload through Tor (Orbot)", modifier = Modifier.weight(1f))
                    Switch(s.uploadDropFoTor, { value -> update { copy(uploadDropFoTor = value) } })
                }
                if (s.uploadDropFoTor) {
                    Text("Start Orbot in the same Android profile. Uploads use drop.fo’s onion service through 127.0.0.1:9050, independently of the IRC network proxy. If Orbot is unavailable, uploads stop.", style = MaterialTheme.typography.bodySmall)
                }
            }
            val custom = s.uploadProvider == UploadProvider.CUSTOM
            val selfHosted = custom || s.uploadProvider == UploadProvider.RUSTYPASTE
            if (selfHosted) {
                OutlinedTextField(s.uploadEndpoint, { value -> update { copy(uploadEndpoint = value) } }, label = { Text("Upload endpoint URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Allow HTTP for self-hosted server")
                    Switch(s.uploadAllowHttp, { value -> update { copy(uploadAllowHttp = value) } })
                }
                OutlinedTextField(token, { token = it; saved = false }, label = { Text("Authorization header (optional)") }, placeholder = { Text("Token or Bearer token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(enabled = s.uploadConfig().validate() == null, onClick = { saveToken(token); token = ""; saved = true }) { Text("Save token / clear if blank") }
                if (saved) Text("Authorization updated")
            } else Text(s.uploadConfig().endpoint, style = MaterialTheme.typography.bodySmall)
            if (custom) {
                OutlinedTextField(s.uploadFileField, { value -> update { copy(uploadFileField = value) } }, label = { Text("Multipart file field") }, singleLine = true)
                UploadResponse.entries.forEach { response ->
                    Row {
                        RadioButton(s.uploadResponse == response, { update { copy(uploadResponse = response) } })
                        Text(when (response) { UploadResponse.TEXT_URL -> "URL in response text"; UploadResponse.LOCATION -> "Location header"; UploadResponse.JSON_URL -> "URL in JSON" })
                    }
                }
                if (s.uploadResponse == UploadResponse.JSON_URL) OutlinedTextField(s.uploadJsonKey, { value -> update { copy(uploadJsonKey = value) } }, label = { Text("JSON URL key, e.g. data.url") }, singleLine = true)
            }
            s.uploadConfig().validate()?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
