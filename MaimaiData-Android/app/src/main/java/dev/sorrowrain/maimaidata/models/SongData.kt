package dev.sorrowrain.maimaidata.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.abs

@Serializable
data class SongData(
    @SerialName("basic_info") val basicInfo: BasicInfo,
    val charts: List<RawChart>,
    val ds: List<Double>,
    @SerialName("old_ds") val oldDs: List<Double>? = null,
    val id: String,
    val level: List<String>,
    val title: String,
    val type: String,
    val alias: List<String>? = null,
    val comment: String? = null,
    @SerialName("remote_cover") val remoteCoverName: String? = null,
    @SerialName("prefer_remote_cover") val preferRemoteCover: Boolean? = null,
    @SerialName("sort_order") val sortOrder: Int? = null,
    @SerialName("is_disabled") val isDisabled: Boolean? = null
) {
    val numericId: Int
        get() = id.toIntOrNull() ?: 0

    val defaultSortKey: Int
        get() = sortOrder ?: numericId

    val baseJacketId: Int
        get() {
            val rem = numericId % 10000
            return if (rem > 0) rem else numericId
        }

    val isUtage: Boolean
        get() = basicInfo.genre == MaimaiConstants.GENRE_UTAGE

    val isDX: Boolean
        get() = type == MaimaiConstants.CHART_TYPE_DX

    val resolvedCharts: List<ResolvedChart>
        get() = charts.indices.map { idx ->
            val raw = charts[idx]
            val isSD = type == MaimaiConstants.CHART_TYPE_SD
            val notes = raw.notes
            val tap = notes.getOrElse(0) { 0 }
            val hold = notes.getOrElse(1) { 0 }
            val slide = notes.getOrElse(2) { 0 }
            val touch = if (isSD) 0 else notes.getOrElse(3) { 0 }
            val breakCount = if (isSD) notes.getOrElse(3) { 0 } else notes.getOrElse(4) { 0 }
            val difficulty = DifficultyType.from(genre = basicInfo.genre, index = idx)
            val currentDs = ds.getOrElse(idx) { 0.0 }
            val rawOldDs = oldDs?.getOrNull(idx)
            val meaningfulOldDs = rawOldDs?.takeIf { it > 0.0 && abs(it - currentDs) >= 0.05 }
            ResolvedChart(
                index = idx,
                difficulty = difficulty,
                ds = currentDs,
                oldDs = meaningfulOldDs,
                level = level.getOrElse(idx) { "-" },
                charter = raw.charter,
                kanji = raw.kanji,
                tap = tap,
                hold = hold,
                slide = slide,
                touch = touch,
                breakCount = breakCount,
                totalNotes = notes.sum()
            )
        }

    /**
     * Ordered CDN cover URLs: domestic LXNS / Diving-Fish for CN-indexed songs,
     * with Zetaraku CloudFront cover CDN directly for JP/INTL-exclusive songs (and as fallback).
     */
    val coverCandidateUrls: List<String>
        get() {
            if (numericId <= 0) {
                return listOfNotNull(zetarakuCoverUrl(remoteCoverName))
            }
            val urls = mutableListOf<String>()
            if (preferRemoteCover == true) {
                zetarakuCoverUrl(remoteCoverName)?.let { urls += it }
            }
            urls += "https://assets2.lxns.net/maimai/jacket/$baseJacketId.png"
            val paddedFull = String.format(Locale.US, "%05d", numericId)
            urls += "https://www.diving-fish.com/covers/$paddedFull.png"
            if (baseJacketId != numericId) {
                val paddedBase = String.format(Locale.US, "%05d", baseJacketId)
                urls += "https://www.diving-fish.com/covers/$paddedBase.png"
            }
            if (preferRemoteCover != true) {
                zetarakuCoverUrl(remoteCoverName)?.let { urls += it }
            }
            return urls
        }

    val coverCacheKey: String
        get() {
            if (numericId > 0 && preferRemoteCover != true) {
                return "jacket_$baseJacketId"
            }
            val remote = remoteCoverName?.takeIf { it.isNotEmpty() }
            if (remote != null) {
                val sanitized = remote.replace("/", "_")
                val stem = sanitized.substringBeforeLast('.')
                return "jacket_remote_${stem.ifEmpty { sanitized }}"
            }
            if (numericId > 0) {
                return "jacket_$baseJacketId"
            }
            return "jacket_$id"
        }

    @Serializable
    data class BasicInfo(
        val artist: String,
        val bpm: Int,
        val from: String,
        val genre: String,
        @SerialName("is_new") val isNew: Boolean,
        val title: String,
        val version: String
    )

    @Serializable
    data class RawChart(
        val charter: String,
        val notes: List<Int>,
        val kanji: String? = null
    )

    companion object {
        private fun zetarakuCoverUrl(fileName: String?): String? {
            if (fileName.isNullOrEmpty()) return null
            val encoded = URLEncoder.encode(fileName, Charsets.UTF_8.name()).replace("+", "%20")
            return "https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/$encoded"
        }

        /**
         * Synchronizes song-level community aliases across SD <-> DX counterpart pairs (`id ± 10000`),
         * while keeping explicit chart-type prefixes (`标...` vs `dx...`) on their respective side.
         */
        fun mergingCounterpartAliases(songs: List<SongData>): List<SongData> {
            val indexByNumericId = HashMap<Int, Int>(songs.size)
            songs.forEachIndexed { idx, song ->
                if (!song.isUtage && song.numericId > 0) {
                    indexByNumericId[song.numericId] = idx
                }
            }

            val result = songs.toMutableList()
            for (idx in result.indices) {
                val song = result[idx]
                if (song.isUtage || song.numericId <= 0 || song.numericId >= 10_000) continue
                val dxIdx = indexByNumericId[song.numericId + 10_000] ?: continue
                val dxSong = result[dxIdx]
                if (dxSong.isUtage || dxSong.title != song.title || dxSong.isDX == song.isDX) continue

                val sdOwn = song.alias.orEmpty()
                val dxOwn = dxSong.alias.orEmpty()

                val sdShareable = sdOwn.filterNot { isStandardOnlyAlias(it) }
                val dxShareable = dxOwn.filterNot { isDXOnlyAlias(it) }

                val mergedSD = mergeUniqueAliases(sdOwn, dxShareable)
                val mergedDX = mergeUniqueAliases(dxOwn, sdShareable)

                if (mergedSD.isNotEmpty()) result[idx] = song.copy(alias = mergedSD)
                if (mergedDX.isNotEmpty()) result[dxIdx] = dxSong.copy(alias = mergedDX)
            }
            return result
        }

        private fun isStandardOnlyAlias(raw: String): Boolean {
            val lower = raw.trim().lowercase(Locale.ROOT)
            return lower.startsWith("标") || lower.startsWith("sd")
        }

        private fun isDXOnlyAlias(raw: String): Boolean {
            val lower = raw.trim().lowercase(Locale.ROOT)
            return lower.startsWith("dx")
        }

        private fun mergeUniqueAliases(primary: List<String>, secondary: List<String>): List<String> {
            val seen = HashSet<String>()
            val merged = mutableListOf<String>()
            for (item in primary + secondary) {
                val trimmed = item.trim()
                if (trimmed.isEmpty()) continue
                if (seen.add(trimmed.lowercase(Locale.ROOT))) {
                    merged += trimmed
                }
            }
            return merged
        }
    }
}

data class ResolvedChart(
    val index: Int,
    val difficulty: DifficultyType,
    val ds: Double,
    val oldDs: Double?,
    val level: String,
    val charter: String,
    val kanji: String?,
    val tap: Int,
    val hold: Int,
    val slide: Int,
    val touch: Int,
    val breakCount: Int,
    val totalNotes: Int
) {
    val totalWeightedScore: Int
        get() = (tap + touch) + hold * 2 + slide * 3 + breakCount * 5
}
