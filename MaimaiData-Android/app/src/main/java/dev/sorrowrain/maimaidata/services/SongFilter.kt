package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import dev.sorrowrain.maimaidata.models.SongSortOption
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pure stateless filtering and sorting logic for the song catalog (1:1 parity with iOS `SongFilter.swift`).
 */
object SongFilter {
    fun prunedCriteria(
        criteria: SongFilterCriteria,
        region: MaimaiConstants.ServerRegion
    ): SongFilterCriteria {
        if (criteria.selectedVersions.isEmpty()) return criteria
        val allowed = MaimaiConstants.searchVersionChips(region).toSet()
        val pruned = criteria.selectedVersions.intersect(allowed)
        return if (pruned == criteria.selectedVersions) {
            criteria
        } else {
            criteria.copy(selectedVersions = pruned)
        }
    }

    fun apply(
        songs: List<SongData>,
        criteria: SongFilterCriteria,
        region: MaimaiConstants.ServerRegion = MaimaiConstants.ServerRegion.CN,
        favoriteIds: Set<String> = emptySet(),
        enableAliasSearch: Boolean = true,
        enableCharterSearch: Boolean = false
    ): List<SongData> {
        val query = normalizeSearchText(criteria.keyword)
        val queryNumericId = query.toIntOrNull()?.takeIf { it > 0 }
        val expandedVersions = MaimaiConstants.expandVersionFilter(criteria.selectedVersions, region)
        val targetDS = if (criteria.useExactDS) {
            (criteria.exactDS * 10.0).roundToInt() / 10.0
        } else {
            null
        }
        val levelFilter = if (criteria.useExactDS) null else criteria.selectedLevel
        val seqDiff = criteria.sortOption.filterDifficulty

        val filtered = songs.filter { song ->
            // 1. Genre (default excludes 宴会場 when no genre is selected)
            if (criteria.selectedGenres.isEmpty()) {
                if (song.isUtage) return@filter false
            } else if (song.basicInfo.genre !in criteria.selectedGenres) {
                return@filter false
            }

            // 2. Version
            if (expandedVersions.isNotEmpty() && song.basicInfo.from !in expandedVersions) {
                return@filter false
            }

            // 3. Favorites
            if (criteria.onlyFavorites && song.id !in favoriteIds) {
                return@filter false
            }

            // 4. Keyword (title, alias, charter, or exact ID / numericId)
            if (query.isNotEmpty()) {
                val titleMatch = normalizeSearchText(song.title).contains(query)
                val aliasMatch = enableAliasSearch &&
                    (song.alias?.any { normalizeSearchText(it).contains(query) } == true)
                val charterMatch = enableCharterSearch &&
                    song.charts.any { normalizeSearchText(it.charter).contains(query) }
                val idMatch = normalizeSearchText(song.id) == query ||
                    (queryNumericId != null && song.numericId == queryNumericId)
                if (!(titleMatch || aliasMatch || charterMatch || idMatch)) {
                    return@filter false
                }
            }

            // 5. Level or Exact DS
            if (targetDS != null) {
                if (song.ds.none { abs(it - targetDS) < 0.001 }) {
                    return@filter false
                }
            } else if (levelFilter != null && levelFilter != "ALL") {
                if (seqDiff != null) {
                    val idx = seqDiff.rawValue
                    if (idx !in song.level.indices || song.level[idx] != levelFilter) {
                        return@filter false
                    }
                } else if (!song.level.contains(levelFilter)) {
                    return@filter false
                }
            }

            true
        }

        return when (criteria.sortOption) {
            SongSortOption.DEFAULT_DESC ->
                filtered.sortedByDescending { it.defaultSortKey }
            SongSortOption.EXPERT_ASC ->
                filtered.sortedBy { it.ds.getOrElse(2) { 0.0 } }
            SongSortOption.EXPERT_DESC ->
                filtered.sortedByDescending { it.ds.getOrElse(2) { 0.0 } }
            SongSortOption.MASTER_ASC ->
                filtered.sortedBy { it.ds.getOrElse(3) { 0.0 } }
            SongSortOption.MASTER_DESC ->
                filtered.sortedByDescending { it.ds.getOrElse(3) { 0.0 } }
            SongSortOption.REMASTER_ASC ->
                filtered.sortedWith { a, b -> compareRemaster(a, b, ascending = true) }
            SongSortOption.REMASTER_DESC ->
                filtered.sortedWith { a, b -> compareRemaster(a, b, ascending = false) }
        }
    }

    private fun compareRemaster(a: SongData, b: SongData, ascending: Boolean): Int {
        if (a === b) return 0
        val aHas = a.ds.size >= 5
        val bHas = b.ds.size >= 5
        if (aHas != bHas) return if (aHas) -1 else 1
        val aDs = a.ds.getOrElse(4) { 0.0 }
        val bDs = b.ds.getOrElse(4) { 0.0 }
        val cmp = if (ascending) aDs.compareTo(bDs) else bDs.compareTo(aDs)
        if (cmp != 0) return cmp
        return b.defaultSortKey.compareTo(a.defaultSortKey)
    }

    /** Normalizes full-width ASCII (`Ａ`..`Ｚ`, `０`..`９`, `　`) to half-width and lowercases. */
    internal fun normalizeSearchText(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            val mapped = when {
                ch == '\u3000' -> ' '
                ch in '\uFF01'..'\uFF5E' -> (ch.code - 0xFEE0).toChar()
                else -> ch
            }
            sb.append(mapped)
        }
        return sb.toString().trim().lowercase(Locale.ROOT)
    }
}
