package com.boxlabs.hexdroid

import java.net.URI

enum class UploadProvider(val label: String, val endpoint: String) {
    DROPFO("drop.fo", "https://drop.fo/"),
    ZEROXZERO("0x0.st", "https://0x0.st/"),
    CATBOX("Catbox", "https://catbox.moe/user/api.php"),
    LITTERBOX("Litterbox (24 hours)", "https://litterbox.catbox.moe/resources/internals/api.php"),
    RUSTYPASTE("rustypaste (self-hosted)", ""),
    CUSTOM("Custom / self-hosted", ""),
}

enum class UploadResponse { TEXT_URL, LOCATION, JSON_URL }

internal data class UploaderConfig(
    val provider: UploadProvider,
    val endpoint: String,
    val field: String = "file",
    val response: UploadResponse = UploadResponse.TEXT_URL,
    val jsonKey: String = "url",
    val authorization: String? = null,
    val allowHttp: Boolean = false,
    val ageEnabled: Boolean = false,
    val ageRecipients: String = "",
) {
    fun validate(): String? {
        val uri = runCatching { URI(endpoint) }.getOrNull() ?: return "Invalid upload endpoint"
        if (uri.host.isNullOrBlank() || uri.rawUserInfo != null || uri.fragment != null) return "Invalid upload endpoint"
        if (uri.scheme != "https" && !(allowHttp && uri.scheme == "http")) return "Use HTTPS or explicitly enable HTTP for your self-hosted server"
        if (!field.matches(Regex("[a-zA-Z0-9_.-]+"))) return "Invalid multipart file field"
        if (authorization?.any { it == '\r' || it == '\n' } == true) return "Invalid authorization value"
        if (ageEnabled && ageRecipients.isBlank()) return "Add recipient age1 public keys before uploading"
        return null
    }
}

internal fun UiSettings.uploadConfig(token: String? = null) = UploaderConfig(
    provider = uploadProvider,
    ageEnabled = uploadAgeEnabled,
    ageRecipients = uploadAgeRecipients,
    endpoint = uploadProvider.endpoint.ifEmpty { uploadEndpoint.trim() },
    field = if (uploadProvider in listOf(UploadProvider.CATBOX, UploadProvider.LITTERBOX)) "fileToUpload"
        else if (uploadProvider == UploadProvider.CUSTOM) uploadFileField else "file",
    response = if (uploadProvider == UploadProvider.CUSTOM) uploadResponse else UploadResponse.TEXT_URL,
    jsonKey = uploadJsonKey,
    authorization = if (uploadProvider in listOf(UploadProvider.RUSTYPASTE, UploadProvider.CUSTOM)) token else null,
    allowHttp = uploadAllowHttp && uploadProvider in listOf(UploadProvider.RUSTYPASTE, UploadProvider.CUSTOM),
)
