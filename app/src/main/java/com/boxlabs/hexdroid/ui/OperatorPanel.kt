package com.boxlabs.hexdroid.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.boxlabs.hexdroid.IrcViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OperatorPanel(vm: IrcViewModel, netId: String, network: String, nick: String,
                           oper: Boolean, connected: Boolean, dismiss: () -> Unit) {
    var login by remember(netId) { mutableStateOf("") }
    // Deliberately not rememberSaveable: never persist an operator password.
    var password by remember(netId) { mutableStateOf("") }
    var command by remember(netId) { mutableStateOf("STATS") }
    var arguments by remember(netId) { mutableStateOf("u") }
    var expanded by remember { mutableStateOf(false) }
    var pending by remember(netId) { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = { password = ""; dismiss() }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp).imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("IRC Operator · $network", style = MaterialTheme.typography.titleLarge)
            Text(if (!connected) "Connect to this server first." else if (oper) "Operator privileges confirmed by server" else "Not authenticated as an operator")
            TextButton(onClick = { vm.openOperEvents(netId); dismiss() }) { Text("Open Oper Events") }
            Text("Events include operator broadcasts and server notices received while oper. Use chat search to filter them.", style = MaterialTheme.typography.bodySmall)
            if (!oper) {
                OutlinedTextField(login, { login = it }, label = { Text("Oper name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it }, label = { Text("Oper password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Button(enabled = connected && login.isNotBlank() && password.isNotEmpty(), onClick = {
                    vm.operLogin(netId, login.trim(), password); password = ""
                }) { Text("Log in") }
                Text("Password is not saved in drafts or command history. Login results appear in the server window.", style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedButton(onClick = { pending = "MODE $nick -o" }) { Text("Remove oper privileges") }
                Text("Commands and notice masks vary by IRCd and installed modules. Support is not advertised reliably; the server validates each request. Enter arguments using your server’s syntax.", style = MaterialTheme.typography.bodySmall)
                Box {
                    OutlinedButton(onClick = { expanded = true }) { Text(command) }
                    DropdownMenu(expanded, { expanded = false }) {
                        listOf("STATS", "KILL", "KLINE", "GLINE", "ZLINE", "UNKLINE", "UNGLINE", "SHUN", "REHASH", "MODE", "WALLOPS", "GLOBOPS", "LOCOPS").forEach { value ->
                            DropdownMenuItem(text = { Text(value) }, onClick = { command = value; arguments = ""; expanded = false })
                        }
                    }
                }
                OutlinedTextField(arguments, { arguments = it }, label = { Text("Command arguments (server-specific)") },
                    supportingText = { Text(when (command) {
                        "KILL" -> "nick :reason"
                        "STATS" -> "u (uptime), or your server’s STATS selector"
                        "MODE" -> "$nick +s +mask (notice masks on supporting IRCds)"
                        "REHASH" -> "Optional server-specific flags or target"
                        else -> "Use your IRCd’s documented arguments; add : before a trailing message."
                    }) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Button(enabled = connected, onClick = { pending = "$command ${arguments.trim()}".trim() }) { Text("Review command") }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
    pending?.let { line ->
        AlertDialog(onDismissRequest = { pending = null }, title = { Text("Send operator command?") },
            text = { Text("Server: $network\n\n$line\n\nThis may affect other users or server configuration.") },
            confirmButton = { TextButton(enabled = connected && oper, onClick = { vm.sendOperCommand(netId, line); pending = null }) { Text("Send") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } })
    }
}
