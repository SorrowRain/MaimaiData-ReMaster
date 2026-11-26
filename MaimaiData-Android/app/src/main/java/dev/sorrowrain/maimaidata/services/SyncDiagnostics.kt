package dev.sorrowrain.maimaidata.services

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object SyncDiagnostics {
    private const val MAX_ENTRIES = 200
    private const val MAX_FILE_BYTES = 64 * 1024
    private const val TRIMMED_FILE_BYTES = 48 * 1024

    private val lock = Any()
    private var logFile: File? = null
    private val entries = ArrayDeque<String>()

    fun configure(directory: File) {
        val file = File(directory, "sync_diagnostics.log")
        synchronized(lock) {
            logFile = file
            if (file.exists()) {
                val loaded = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull().orEmpty()
                    .filter { it.isNotBlank() }
                entries.clear()
                loaded.takeLast(MAX_ENTRIES).forEach { entries.addLast(it) }
            }
        }
    }

    fun record(
        operation: String,
        channel: String? = null,
        statusCode: Int? = null,
        errorCode: String? = null,
        message: String,
        uploadedCount: Int? = null,
        skippedCount: Int? = null,
        sampleSkippedIds: List<Int> = emptyList()
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val parts = mutableListOf("[$timestamp]", "op=${sanitize(operation, 40)}")
        if (!channel.isNullOrBlank()) {
            parts += "ch=${sanitize(channel, 30)}"
        }
        if (statusCode != null) {
            parts += "http=$statusCode"
        }
        if (!errorCode.isNullOrBlank()) {
            parts += "code=${sanitize(errorCode, 60)}"
        }
        if (uploadedCount != null) {
            parts += "up=$uploadedCount"
        }
        if (skippedCount != null && skippedCount > 0) {
            parts += "skip=$skippedCount"
        }
        if (sampleSkippedIds.isNotEmpty()) {
            val ids = sampleSkippedIds.take(5).joinToString(",")
            parts += "skipIds=[$ids]"
        }
        val cleanedMsg = sanitize(message, 280)
        if (cleanedMsg.isNotEmpty()) {
            parts += "msg=\"$cleanedMsg\""
        }
        val line = parts.joinToString(" ")

        runCatching { Log.i("MaimaiSync", line) }

        synchronized(lock) {
            entries.addLast(line)
            while (entries.size > MAX_ENTRIES) {
                entries.removeFirst()
            }
            appendLineToFileLocked(line)
        }
    }

    fun exportText(): String = synchronized(lock) {
        if (entries.isNotEmpty()) {
            return entries.joinToString("\n")
        }
        val file = logFile ?: return ""
        if (!file.exists()) return ""
        return runCatching { file.readText(Charsets.UTF_8).trim() }.getOrDefault("")
    }

    private fun appendLineToFileLocked(line: String) {
        val file = logFile ?: return
        runCatching {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { out ->
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
            if (file.length() > MAX_FILE_BYTES) {
                val bytes = file.readBytes()
                val start = (bytes.size - TRIMMED_FILE_BYTES).coerceAtLeast(0)
                val firstNewline = (start until bytes.size).firstOrNull { bytes[it] == '\n'.code.toByte() }
                val sliceStart = if (firstNewline != null) firstNewline + 1 else start
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeBytes(bytes.copyOfRange(sliceStart, bytes.size))
                if (!tmp.renameTo(file)) {
                    file.writeBytes(tmp.readBytes())
                    tmp.delete()
                }
            }
        }
    }

    internal fun sanitize(raw: String, limit: Int = 280): String {
        var singleLine = raw
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('"', '\'')
        singleLine = SGWCMAID_REGEX.replace(singleLine, "[REDACTED]")
        singleLine = BEARER_REGEX.replace(singleLine, "Bearer [REDACTED]")
        singleLine = JWT_COOKIE_REGEX.replace(singleLine, "jwt_token=[REDACTED]")
        singleLine = KEY_VALUE_SECRET_REGEX.replace(singleLine) { match ->
            "${match.groupValues[1]}=[REDACTED]"
        }
        singleLine = LONG_HEX_REGEX.replace(singleLine, "[REDACTED]").trim()
        return if (singleLine.length <= limit) singleLine else singleLine.take(limit) + "…"
    }

    private val SGWCMAID_REGEX = Regex("SGWCMAID[A-Za-z0-9+/=_-]*")
    private val BEARER_REGEX = Regex("Bearer\\s+\\S+", RegexOption.IGNORE_CASE)
    private val JWT_COOKIE_REGEX = Regex("jwt_token=[^;\\s]+", RegexOption.IGNORE_CASE)
    private val KEY_VALUE_SECRET_REGEX = Regex("\\b(token|secret|password|cookie)\\s*[:=]\\s*\\S+", RegexOption.IGNORE_CASE)
    private val LONG_HEX_REGEX = Regex("\\b[A-Fa-f0-9]{32,}\\b")
}
