package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.IrcViewModel
import com.boxlabs.hexdroid.WraithCommands

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WraithPanel(vm: IrcViewModel, bufferKey: String, peer: String, dismiss: () -> Unit) {
    var hub by remember(bufferKey) { mutableStateOf("") }
    var reviewRelay by remember(bufferKey) { mutableStateOf(false) }
    var reviewFish by remember(bufferKey) { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Wraith · $peer", style = MaterialTheme.typography.titleLarge)
            Text("Uses this DCC chat. Authenticate with Wraith first. Replies appear in the chat window; hub commands require a hub session.",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = { reviewFish = true }) {
                Text("Exchange FiSH key for bot PM")
            }
            Text("FiSH protects IRC private messages with the leaf bot, not this DCC session or a relayed hub.",
                style = MaterialTheme.typography.bodySmall)
            WraithCommands.queries.forEach { (label, command) ->
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    vm.sendWraithCommand(bufferKey, command)
                    dismiss()
                }) { Text(label) }
            }
            OutlinedTextField(hub, { hub = it }, label = { Text("Hub bot name") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Button(enabled = WraithCommands.line("relay", hub.trim()) != null,
                onClick = { reviewRelay = true }) { Text("Relay to hub") }
            Text("Use the DCC composer for other Wraith commands. A relay may prompt you to log in again.",
                style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
        }
    }
    if (reviewFish) AlertDialog(onDismissRequest = { reviewFish = false },
        title = { Text("Exchange FiSH key with $peer?") },
        text = { Text("This replaces the bot’s private-chat key on success and opens its IRC PM. Legacy DH1080 does not authenticate identity; verify the resulting key fingerprint with the peer. DCC is unchanged.") },
        confirmButton = { TextButton(onClick = {
            vm.startFishKeyExchange(bufferKey.substringBefore("::"), peer)
            reviewFish = false
            dismiss()
        }) { Text("Exchange") } },
        dismissButton = { TextButton(onClick = { reviewFish = false }) { Text("Cancel") } })
    if (reviewRelay) AlertDialog(onDismissRequest = { reviewRelay = false },
        title = { Text("Relay to ${hub.trim()}?") },
        text = { Text("Ask $peer to relay this DCC session to the selected hub.") },
        confirmButton = { TextButton(onClick = {
            vm.sendWraithCommand(bufferKey, "relay", hub.trim())
            reviewRelay = false
            dismiss()
        }) { Text("Relay") } },
        dismissButton = { TextButton(onClick = { reviewRelay = false }) { Text("Cancel") } })
}
