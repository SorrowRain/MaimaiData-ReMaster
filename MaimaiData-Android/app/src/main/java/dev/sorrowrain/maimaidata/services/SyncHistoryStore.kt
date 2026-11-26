package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SyncHistoryEntry
import dev.sorrowrain.maimaidata.models.SyncMode
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Serialized append-only JSONL history store (`history_identity_<uuid>.jsonl`) for Rating/score
 * trend tracking. Matches iOS `SyncHistoryStore.swift` 1:1.
 */
class SyncHistoryStore(private val directory: File) {
    private val lock = Any()
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    fun historyFile(identityId: String): File =
        File(directory, "history_identity_${identityId.lowercase(Locale.ROOT)}.jsonl")

    fun appendSnapshot(
        identityId: String,
        syncMode: SyncMode,
        records: List<ProberRecord>,
        songsById: Map<Int, SongData>,
        timestampEpochMs: Long = System.currentTimeMillis()
    ) {
        val lowerId = identityId.lowercase(Locale.ROOT)
        val snapshot = ScoreSyncEngine.computeCNRatingSnapshot(records = records, songsById = songsById)
        val playedSongsCount = records.mapTo(HashSet(records.size)) { it.songId }.size

        synchronized(lock) {
            if (!directory.exists()) {
                directory.mkdirs()
            }
            val file = historyFile(lowerId)
            val existing = loadEntriesUnlocked(file)
            val hasAuthoritativeBaseline = existing.any { it.isBadgeBaseline }
            val isBaseline = (syncMode == SyncMode.FULL) && !hasAuthoritativeBaseline

            val entry = SyncHistoryEntry(
                identityId = lowerId,
                timestampEpochMs = timestampEpochMs,
                syncMode = syncMode,
                cnRating = snapshot.cnRating,
                b35Rating = snapshot.b35Rating,
                b15Rating = snapshot.b15Rating,
                recordCount = records.size,
                playedSongsCount = playedSongsCount,
                isBadgeBaseline = isBaseline
            )

            var prefixNewline = false
            if (file.exists() && file.length() > 0L) {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    raf.seek(file.length() - 1L)
                    if (raf.read() != '\n'.code) {
                        prefixNewline = true
                    }
                }
            }
            val rawBytes = json.encodeToString(entry).toByteArray(Charsets.UTF_8)
            val newline = '\n'.code.toByte()
            val encodedLine = if (prefixNewline) {
                byteArrayOf(newline) + rawBytes + byteArrayOf(newline)
            } else {
                rawBytes + byteArrayOf(newline)
            }
            FileOutputStream(file, true).use { fos ->
                fos.write(encodedLine)
            }
        }
    }

    fun loadHistory(identityId: String): List<SyncHistoryEntry> {
        val file = historyFile(identityId.lowercase(Locale.ROOT))
        return synchronized(lock) {
            loadEntriesUnlocked(file)
        }
    }

    fun deleteHistory(identityId: String) {
        val file = historyFile(identityId.lowercase(Locale.ROOT))
        synchronized(lock) {
            if (file.exists()) {
                file.delete()
            }
        }
    }

    private fun loadEntriesUnlocked(file: File): List<SyncHistoryEntry> {
        if (!file.exists()) return emptyList()
        val lines = runCatching { file.readLines(Charsets.UTF_8) }.getOrNull() ?: return emptyList()
        return lines.mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            runCatching { json.decodeFromString<SyncHistoryEntry>(trimmed) }.getOrNull()
        }
    }
}
