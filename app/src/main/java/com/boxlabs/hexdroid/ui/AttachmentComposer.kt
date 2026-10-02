package com.boxlabs.hexdroid.ui

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.boxlabs.hexdroid.IrcViewModel
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal data class AttachmentRecord(val name: String, val url: String, val snippet: Boolean)
internal data class AttachmentJob(val uri: Uri, val cleanup: File? = null, val snippet: Boolean = false, val networkId: String, val bufferKey: String)

@Stable
internal class AttachmentActions {
    var uploading by mutableStateOf(false)
    var menuOpen by mutableStateOf(false)
    var showSnippet by mutableStateOf(false)
    var historyFilter by mutableStateOf<Boolean?>(null)
    var historyOpen by mutableStateOf(false)
    var snippetText by mutableStateOf("")
    var records by mutableStateOf<List<AttachmentRecord>>(emptyList())
    var paste: () -> Boolean = { false }
    var photo: () -> Unit = {}
    var video: () -> Unit = {}
    var images: () -> Unit = {}
    var documents: () -> Unit = {}
}

/** Clipboard attachments are content/file URIs, never text that happens to look like a URL. */
internal fun attachmentUris(clip: android.content.ClipData?): List<Uri> =
    if (clip == null) emptyList() else (0 until clip.itemCount).mapNotNull {
        clip.getItemAt(it).uri?.takeIf { uri -> uri.scheme == "content" || uri.scheme == "file" }
    }.distinct()

@Composable
internal fun rememberAttachmentActions(
    viewModel: IrcViewModel?, networkId: String, bufferKey: String,
    onLink: (bufferKey: String, url: String) -> Unit,
): AttachmentActions {
    val ctx = LocalContext.current
    val actions = remember { AttachmentActions() }
    val liveLink by rememberUpdatedState(onLink)
    val jobs = remember(actions) { mutableStateListOf<AttachmentJob>() }
    val prefs = remember(ctx) { ctx.getSharedPreferences("attachment_history", Context.MODE_PRIVATE) }
    var pickerTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var captureFile by remember { mutableStateOf<File?>(null) }
    var captureTarget by remember { mutableStateOf<Pair<String, AttachmentActions>?>(null) }

    fun message(text: String) = Toast.makeText(ctx, text, Toast.LENGTH_LONG).show()
    fun saveHistory(record: AttachmentRecord) {
        val arr = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrElse { JSONArray() }
        val next = JSONArray().put(JSONObject().put("name", record.name).put("url", record.url).put("snippet", record.snippet))
        for (i in 0 until minOf(arr.length(), 49)) next.put(arr.getJSONObject(i))
        prefs.edit().putString("items", next.toString()).apply()
    }
    fun refreshHistory() {
        actions.records = runCatching {
            val arr = JSONArray(prefs.getString("items", "[]"))
            (0 until arr.length()).map { i -> arr.getJSONObject(i).let {
                AttachmentRecord(it.optString("name", "File"), it.getString("url"), it.optBoolean("snippet"))
            } }
        }.getOrDefault(emptyList())
    }

    // A job owns its original buffer; changing chats never inserts its link into a different draft.
    fun pump() {
        if (actions.uploading) return
        val job = jobs.firstOrNull() ?: return
        val vm = viewModel ?: return
        actions.uploading = true
        vm.uploadFileToFilehost(job.networkId, job.uri) { url, error ->
            if (url != null) {
                val name = job.cleanup?.name ?: runCatching {
                    ctx.contentResolver.query(job.uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                }.getOrNull() ?: "File"
                saveHistory(AttachmentRecord(name, url, job.snippet))
                liveLink(job.bufferKey, url)
            } else message(error ?: "Upload failed")
            job.cleanup?.delete()
            jobs.remove(job)
            actions.uploading = false
            pump()
        }
    }

    fun enqueue(uris: List<Uri>, target: Pair<String, String> = networkId to bufferKey) {
        if (viewModel == null) { message("Connect to a network before uploading"); return }
        uris.forEach { jobs.add(AttachmentJob(it, networkId = target.first, bufferKey = target.second)) }
        pump()
    }
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { enqueue(it, pickerTarget ?: (networkId to bufferKey)); pickerTarget = null }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { enqueue(it, pickerTarget ?: (networkId to bufferKey)); pickerTarget = null }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val file = captureFile
        val target = captureTarget
        if (file != null && success && target != null) {
            // Retain the destination selected when capture launched.
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            target.second.uploading = true
            viewModel?.uploadFileToFilehost(target.first.substringBefore("::"), uri) { url, error ->
                if (url != null) { saveHistory(AttachmentRecord(file.name, url, false)); liveLink(target.first, url) }
                else message(error ?: "Upload failed")
                file.delete(); target.second.uploading = false; pump()
            }
        } else file?.delete()
        captureFile = null; captureTarget = null
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { success ->
        val file = captureFile
        val target = captureTarget
        if (file != null && success && target != null) {
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            target.second.uploading = true
            viewModel?.uploadFileToFilehost(target.first.substringBefore("::"), uri) { url, error ->
                if (url != null) { saveHistory(AttachmentRecord(file.name, url, false)); liveLink(target.first, url) }
                else message(error ?: "Upload failed")
                file.delete(); target.second.uploading = false; pump()
            }
        } else file?.delete()
        captureFile = null; captureTarget = null
    }
    fun capture(extension: String, launch: (Uri) -> Unit) {
        runCatching {
            val dir = File(ctx.cacheDir, "attachments").apply { mkdirs() }
            val file = File.createTempFile("capture-", extension, dir)
            captureFile = file; captureTarget = bufferKey to actions
            launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file))
        }.onFailure { captureFile?.delete(); captureFile = null; captureTarget = null; message(it.message ?: "Camera unavailable") }
    }
    actions.photo = { capture(".jpg") { camera.launch(it) } }
    actions.video = { capture(".mp4") { video.launch(it) } }
    actions.images = { pickerTarget = networkId to bufferKey; imagePicker.launch("image/*") }
    actions.documents = { pickerTarget = networkId to bufferKey; documentPicker.launch(arrayOf("*/*")) }
    actions.paste = {
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val uris = attachmentUris(clipboard.primaryClip)
        if (uris.isEmpty()) false else { enqueue(uris); true }
    }

    if (actions.showSnippet) {
        AlertDialog(onDismissRequest = { actions.showSnippet = false }, title = { Text("Post a text snippet") },
            text = { OutlinedTextField(actions.snippetText, { actions.snippetText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), placeholder = { Text("Paste or type text") }) },
            confirmButton = { TextButton(enabled = actions.snippetText.isNotBlank(), onClick = {
                runCatching {
                    val dir = File(ctx.cacheDir, "attachments").apply { mkdirs() }
                    val file = File.createTempFile("snippet-", ".txt", dir)
                    file.writeText(actions.snippetText)
                    jobs.add(AttachmentJob(FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file), file, true, networkId, bufferKey))
                    actions.snippetText = ""; actions.showSnippet = false
                    pump()
                }.onFailure { message(it.message ?: "Could not create snippet") }
            }) { Text("Upload") } }, dismissButton = { TextButton(onClick = { actions.showSnippet = false }) { Text("Cancel") } })
    }
    if (actions.historyOpen) {
        LaunchedEffect(actions.historyOpen) { refreshHistory() }
        val records = actions.records.filter { actions.historyFilter == null || it.snippet == actions.historyFilter }
        AlertDialog(onDismissRequest = { actions.historyOpen = false },
            title = { Text(if (actions.historyFilter == true) "Text snippets" else "File uploads") },
            text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (records.isEmpty()) Text("No uploads yet")
                records.forEach { record -> TextButton(onClick = { liveLink(bufferKey, record.url); actions.historyOpen = false }) { Text(record.name) } }
                Text("Links may expire according to drop.fo retention.")
            } }, confirmButton = { TextButton(onClick = { actions.historyOpen = false }) { Text("Close") } })
    }
    return actions
}

@Composable
internal fun AttachmentButton(actions: AttachmentActions) {
    Box {
        if (actions.uploading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else IconButton(onClick = { actions.menuOpen = true }, modifier = Modifier.size(28.dp)) {
            Icon(Icons.Default.AttachFile, contentDescription = "Attach file or image", modifier = Modifier.size(18.dp))
        }
        DropdownMenu(actions.menuOpen, { actions.menuOpen = false }) {
            fun choose(action: () -> Unit) { actions.menuOpen = false; action() }
            DropdownMenuItem(text = { Text("Take a photo") }, onClick = { choose(actions.photo) })
            DropdownMenuItem(text = { Text("Record a video") }, onClick = { choose(actions.video) })
            DropdownMenuItem(text = { Text("Choose existing photos") }, onClick = { choose(actions.images) })
            DropdownMenuItem(text = { Text("Choose existing documents") }, onClick = { choose(actions.documents) })
            DropdownMenuItem(text = { Text("Paste file or image") }, onClick = { choose { actions.paste() } })
            DropdownMenuItem(text = { Text("Post a text snippet") }, onClick = { choose { actions.showSnippet = true } })
            DropdownMenuItem(text = { Text("Text snippets") }, onClick = { choose { actions.historyFilter = true; actions.historyOpen = true } })
            DropdownMenuItem(text = { Text("File uploads") }, onClick = { choose { actions.historyFilter = null; actions.historyOpen = true } })
        }
    }
}
