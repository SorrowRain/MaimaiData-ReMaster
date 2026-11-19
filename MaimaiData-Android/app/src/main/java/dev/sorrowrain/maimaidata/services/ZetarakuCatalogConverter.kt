package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.SongData
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt

@Serializable
data class ZetarakuCatalog(
    val songs: List<ZetarakuSong> = emptyList(),
    val versions: List<ZetarakuVersionEntry>? = null,
    val updateTime: String? = null
)

@Serializable
data class ZetarakuVersionEntry(
    val version: String,
    val abbr: String? = null
)

@Serializable
data class ZetarakuSong(
    val songId: String,
    val category: String,
    val title: String,
    val artist: String,
    val bpm: Double? = null,
    val imageName: String = "",
    val version: String = "",
    val releaseDate: String? = null,
    val comment: String? = null,
    val sheets: List<ZetarakuSheet> = emptyList()
)

@Serializable
data class ZetarakuSheet(
    val type: String,
    val difficulty: String,
    val level: String,
    val levelValue: Double? = null,
    val internalLevelValue: Double? = null,
    val noteDesigner: String? = null,
    val noteCounts: ZetarakuNoteCounts? = null,
    val regions: ZetarakuRegions = ZetarakuRegions(),
    val regionOverrides: ZetarakuRegionOverrides? = null,
    val version: String? = null
)

@Serializable
data class ZetarakuNoteCounts(
    val tap: Int? = null,
    val hold: Int? = null,
    val slide: Int? = null,
    val touch: Int? = null,
    @SerialName("break") val breakCount: Int? = null,
    val total: Int? = null
)

@Serializable
data class ZetarakuRegions(
    val jp: Boolean? = null,
    val intl: Boolean? = null,
    val usa: Boolean? = null,
    val cn: Boolean? = null
)

@Serializable
data class ZetarakuRegionOverrides(
    val intl: ZetarakuSheetOverride? = null
)

@Serializable
data class ZetarakuSheetOverride(
    val level: String? = null,
    val levelValue: Double? = null,
    val internalLevelValue: Double? = null,
    val version: String? = null
)

/**
 * Compact persisted lookup table distilled from DXRating's live `dxdata` endpoint (`miruku.dxrating.net/api/v1/dxdata`).
 */
@Serializable
data class DXDataIdMap(
    val updatedAt: String? = null,
    val idsByKey: Map<String, Int> = emptyMap()
) {
    companion object {
        fun extract(raw: DXDataRawCatalog): DXDataIdMap {
            val map = HashMap<String, Int>(raw.songs.size + 256)
            for (song in raw.songs) {
                val cnGenre = ZetarakuCatalogConverter.mapCategoryToCNGenre(song.category)
                if (cnGenre == MaimaiConstants.GENRE_UTAGE) {
                    val rawId = song.sheets.mapNotNull { it.internalId }.firstOrNull { it > 0 }
                    if (rawId != null) {
                        val normalized = if (rawId < 100_000) 100_000 + rawId else rawId
                        map["utage|${song.title}"] = normalized
                    }
                } else {
                    for (sheet in song.sheets) {
                        val rawId = sheet.internalId ?: continue
                        if (rawId <= 0) continue
                        if (sheet.type == "std") {
                            val key = "${song.title}|$cnGenre|${MaimaiConstants.CHART_TYPE_SD}"
                            map.putIfAbsent(key, rawId)
                        } else if (sheet.type == "dx") {
                            val normalized = if (rawId < 10_000) 10_000 + rawId else rawId
                            val key = "${song.title}|$cnGenre|${MaimaiConstants.CHART_TYPE_DX}"
                            map.putIfAbsent(key, normalized)
                        }
                    }
                }
            }
            return DXDataIdMap(updatedAt = raw.updatedAt, idsByKey = map)
        }
    }
}

@Serializable
data class DXDataRawCatalog(
    val updatedAt: String? = null,
    val songs: List<DXDataRawSong> = emptyList()
)

@Serializable
data class DXDataRawSong(
    val category: String,
    val title: String,
    val sheets: List<DXDataRawSheet> = emptyList()
)

@Serializable
data class DXDataRawSheet(
    val type: String,
    val internalId: Int? = null
)

/**
 * Pure converter transforming Zetaraku `maimai/data.json` + optional live [DXDataIdMap] into [SongData]
 * for JP or INTL, joining CN IDs, aliases, Utage comments/BPMs, and CN baseline constants (`oldDs`).
 */
object ZetarakuCatalogConverter {
    data class MigratedIdPair(
        val legacyId: String,
        val resolvedId: String
    )

    data class ConversionOutput(
        val songs: List<SongData>,
        val migratedIdPairs: List<MigratedIdPair>
    )

    private data class CNLookupKey(
        val title: String,
        val genre: String,
        val type: String
    )

    private data class UtageMeta(
        val comment: String?,
        val kanjis: List<String?>
    )

    private val difficultyOrder: Map<String, Int> = mapOf(
        "basic" to 0,
        "advanced" to 1,
        "expert" to 2,
        "master" to 3,
        "remaster" to 4
    )

    fun mapCategoryToCNGenre(category: String): String = when (category) {
        "POPS＆アニメ" -> "流行&动漫"
        "niconico＆ボーカロイド" -> "niconico & VOCALOID"
        "東方Project" -> "东方Project"
        "ゲーム＆バラエティ" -> "其他游戏"
        "maimai" -> "舞萌"
        "オンゲキ＆CHUNITHM" -> "音击&中二节奏"
        "宴会場" -> MaimaiConstants.GENRE_UTAGE
        else -> category
    }

    /**
     * Post-`CiRCLE` SEGA rule: `B15` covers the active version plus 1 immediately preceding half-version.
     * Dynamically advances when newer versions appear in `catalog.versions` or INTL sheets, with static registry fallback.
     */
    fun activeB15Versions(
        region: MaimaiConstants.ServerRegion,
        orderedVersions: List<String>,
        presentVersions: Set<String> = emptySet()
    ): Set<String> = when (region) {
        MaimaiConstants.ServerRegion.CN -> emptySet()
        MaimaiConstants.ServerRegion.JP -> orderedVersions.takeLast(2).toSet()
        MaimaiConstants.ServerRegion.INTL -> {
            val defaultMaxIdx = MaimaiConstants.jpVersionRegistry.indexOfLast { it.isIntlAvailableByDefault }
                .coerceAtLeast(0)
            val presentMaxIdx = orderedVersions.indexOfLast { it in presentVersions }
                .takeIf { it >= 0 } ?: defaultMaxIdx
            val latestIdx = maxOf(defaultMaxIdx, presentMaxIdx)
            if (latestIdx < orderedVersions.size) {
                val startIdx = (latestIdx - 1).coerceAtLeast(0)
                orderedVersions.subList(startIdx, latestIdx + 1).toSet()
            } else {
                orderedVersions.takeLast(2).toSet()
            }
        }
    }

    fun isB15NewVersion(version: String, region: MaimaiConstants.ServerRegion): Boolean {
        val ordered = MaimaiConstants.jpVersionRegistry.map { it.name }
        return version in activeB15Versions(region, ordered)
    }

    private fun buildOrderedVersionNames(catalog: ZetarakuCatalog): List<String> {
        val ordered = MaimaiConstants.jpVersionRegistry.mapTo(ArrayList()) { it.name }
        val seen = ordered.toHashSet()
        catalog.versions.orEmpty().forEach { entry ->
            if (entry.version.isNotEmpty() && seen.add(entry.version)) {
                ordered += entry.version
            }
        }
        return ordered
    }

    /** Backfills Utage `comment` ("谱面说明") and `kanji` badges onto CN Utage songs using cached Zetaraku metadata. */
    fun enrichCNSongsWithUtageMetadata(
        cnSongs: List<SongData>,
        catalog: ZetarakuCatalog?
    ): List<SongData> {
        if (catalog == null) return cnSongs
        val utageMetaByTitle = HashMap<String, UtageMeta>()
        for (zSong in catalog.songs) {
            if (zSong.category != "宴会場") continue
            val trimmedComment = cleanedOptionalText(zSong.comment)
            val kanjis = zSong.sheets
                .filter { it.type == "utage" }
                .map { cleanedOptionalText(it.difficulty) }
            utageMetaByTitle[zSong.title] = UtageMeta(trimmedComment, kanjis)
        }
        if (utageMetaByTitle.isEmpty()) return cnSongs

        return cnSongs.map { song ->
            if (!song.isUtage) return@map song
            val meta = utageMetaByTitle[song.title] ?: return@map song
            val updatedCharts = song.charts.mapIndexed { idx, chart ->
                if (chart.kanji == null) {
                    val resolvedKanji = meta.kanjis.getOrNull(idx) ?: meta.kanjis.firstOrNull()
                    chart.copy(kanji = resolvedKanji)
                } else {
                    chart
                }
            }
            song.copy(
                charts = updatedCharts,
                comment = song.comment ?: meta.comment
            )
        }
    }

    fun convertWithMigrations(
        catalog: ZetarakuCatalog,
        region: MaimaiConstants.ServerRegion,
        cnReference: List<SongData>,
        dxIdMap: DXDataIdMap? = null
    ): ConversionOutput {
        val enrichedCN = SongData.mergingCounterpartAliases(cnReference)

        val cnByExactKey = HashMap<CNLookupKey, SongData>(enrichedCN.size)
        val cnShareableAliasesBySong = HashMap<String, List<String>>()
        val cnUtageByTitle = HashMap<String, SongData>()
        val baseBPMByNormalizedTitle = HashMap<String, Int>()
        val baseNumericIdByNormalizedTitle = HashMap<String, Int>()

        for (cnSong in enrichedCN) {
            if (cnSong.isUtage) {
                cnUtageByTitle[cnSong.title] = cnSong
            } else {
                val key = CNLookupKey(
                    title = cnSong.title,
                    genre = cnSong.basicInfo.genre,
                    type = cnSong.type
                )
                cnByExactKey[key] = cnSong
                val normTitle = normalizeTitleForBaseLookup(cnSong.title)
                if (cnSong.basicInfo.bpm > 0) {
                    baseBPMByNormalizedTitle[normTitle] = cnSong.basicInfo.bpm
                }
                if (cnSong.numericId in 1 until 100_000) {
                    // Prefer SD (<10000) when both SD and DX exist, matching SEGA's 100000 + baseId convention.
                    val existing = baseNumericIdByNormalizedTitle[normTitle]
                    if (existing == null || cnSong.numericId < existing) {
                        baseNumericIdByNormalizedTitle[normTitle] = cnSong.numericId
                    }
                }
                val aliases = cnSong.alias
                if (!aliases.isNullOrEmpty()) {
                    val songGroupKey = "${cnSong.title}|${cnSong.basicInfo.genre}"
                    val filtered = aliases.filterNot { SongData.isChartTypeScopedAlias(it) }
                    if (filtered.isNotEmpty() && !cnShareableAliasesBySong.containsKey(songGroupKey)) {
                        cnShareableAliasesBySong[songGroupKey] = filtered
                    }
                }
            }
        }

        // Index BPMs and dxdata IDs from all non-Utage Zetaraku songs so JP-only Utages recover BPM & 100000+baseId
        for (zSong in catalog.songs) {
            if (zSong.category == "宴会場") continue
            val normTitle = normalizeTitleForBaseLookup(zSong.title)
            val bpm = zSong.bpm
            if (bpm != null && bpm > 0.0 && !baseBPMByNormalizedTitle.containsKey(normTitle)) {
                baseBPMByNormalizedTitle[normTitle] = bpm.roundToInt()
            }
            if (!baseNumericIdByNormalizedTitle.containsKey(normTitle) && dxIdMap != null) {
                val mappedGenre = mapCategoryToCNGenre(zSong.category)
                val sdKey = "${zSong.title}|$mappedGenre|${MaimaiConstants.CHART_TYPE_SD}"
                val dxKey = "${zSong.title}|$mappedGenre|${MaimaiConstants.CHART_TYPE_DX}"
                val sdId = dxIdMap.idsByKey[sdKey]
                val dxId = dxIdMap.idsByKey[dxKey]
                if (sdId != null) {
                    baseNumericIdByNormalizedTitle[normTitle] = sdId
                } else if (dxId != null) {
                    baseNumericIdByNormalizedTitle[normTitle] = dxId
                }
            }
        }

        val orderedVersions = buildOrderedVersionNames(catalog)
        val versionRank = HashMap<String, Int>(orderedVersions.size)
        orderedVersions.forEachIndexed { idx, name ->
            versionRank[name] = idx
        }

        val presentVersions = HashSet<String>()
        if (region == MaimaiConstants.ServerRegion.INTL) {
            for (zSong in catalog.songs) {
                if (zSong.category == "宴会場") continue
                for (sh in zSong.sheets) {
                    if (sh.type != "utage" && isSheetAvailable(sh, region)) {
                        presentVersions += effectiveVersion(sh, zSong.version, region)
                    }
                }
            }
        }
        val b15Versions = activeB15Versions(region, orderedVersions, presentVersions)

        val output = ArrayList<SongData>(catalog.songs.size + 256)
        val migratedPairs = ArrayList<MigratedIdPair>()

        catalog.songs.forEachIndexed { catalogIdx, zSong ->
            val activeSheets = zSong.sheets.filter { isSheetAvailable(it, region) }
            if (activeSheets.isEmpty()) return@forEachIndexed

            val mappedGenre = mapCategoryToCNGenre(zSong.category)

            for (sheetType in listOf("std", "dx", "utage")) {
                val rawGroup = activeSheets.filter { it.type == sheetType }
                if (rawGroup.isEmpty()) continue

                if (sheetType == "utage") {
                    val matchedCN = cnUtageByTitle[zSong.title]
                    val fromVersion = effectiveVersion(rawGroup[0], zSong.version, region)
                    val vRank = versionRank[fromVersion] ?: 0
                    val sortOrder = vRank * 100_000 + catalogIdx * 2
                    val legacyId = "zeta_utage_${zSong.songId}"

                    val resolvedId = matchedCN?.id
                        ?: dxIdMap?.idsByKey?.get("utage|${zSong.title}")?.toString()
                        ?: deriveUtageNumericId(zSong.title, baseNumericIdByNormalizedTitle)?.toString()
                        ?: legacyId

                    if (matchedCN == null && resolvedId != legacyId) {
                        migratedPairs += MigratedIdPair(legacyId = legacyId, resolvedId = resolvedId)
                    }

                    val resolvedBPM = resolveUtageBPM(zSong, matchedCN, baseBPMByNormalizedTitle)

                    val charts = rawGroup.mapIndexed { idx, sh ->
                        val charter = cleanedCharter(sh.noteDesigner)
                        val kanji = cleanedOptionalText(sh.difficulty)
                        val nc = sh.noteCounts
                        if (nc != null && nc.total != null) {
                            SongData.RawChart(
                                charter = charter,
                                notes = listOf(
                                    nc.tap ?: 0,
                                    nc.hold ?: 0,
                                    nc.slide ?: 0,
                                    nc.touch ?: 0,
                                    nc.breakCount ?: 0
                                ),
                                kanji = kanji
                            )
                        } else {
                            val cnChart = matchedCN?.charts?.getOrNull(idx)
                            if (cnChart != null) {
                                SongData.RawChart(
                                    charter = cnChart.charter,
                                    notes = cnChart.notes,
                                    kanji = kanji ?: cnChart.kanji
                                )
                            } else {
                                SongData.RawChart(
                                    charter = charter,
                                    notes = listOf(0, 0, 0, 0, 0),
                                    kanji = kanji
                                )
                            }
                        }
                    }

                    val levels = rawGroup.map { effectiveLevel(it, region) }
                    val dsValues = rawGroup.map { effectiveDS(it, region) }

                    val basic = SongData.BasicInfo(
                        artist = zSong.artist,
                        bpm = resolvedBPM,
                        from = fromVersion,
                        genre = MaimaiConstants.GENRE_UTAGE,
                        isNew = false,
                        title = zSong.title,
                        version = fromVersion
                    )

                    output += SongData(
                        basicInfo = basic,
                        charts = charts,
                        ds = dsValues,
                        oldDs = null,
                        id = resolvedId,
                        level = levels,
                        title = zSong.title,
                        type = MaimaiConstants.CHART_TYPE_DX,
                        alias = matchedCN?.alias,
                        comment = cleanedOptionalText(zSong.comment),
                        remoteCoverName = zSong.imageName,
                        preferRemoteCover = if (matchedCN == null) true else null,
                        sortOrder = sortOrder
                    )
                } else {
                    val sortedSheets = rawGroup.sortedBy { difficultyOrder[it.difficulty] ?: 99 }
                    val isDX = sheetType == "dx"
                    val mappedType = if (isDX) MaimaiConstants.CHART_TYPE_DX else MaimaiConstants.CHART_TYPE_SD
                    val cnLookupTitle = if (zSong.songId == "Link (2)") "Link(CoF)" else zSong.title
                    val matchedCN = cnByExactKey[CNLookupKey(title = cnLookupTitle, genre = mappedGenre, type = mappedType)]

                    val legacyId = "zeta_${sheetType}_${zSong.songId}"
                    val dxMapKey = "${zSong.title}|$mappedGenre|$mappedType"
                    val resolvedId = matchedCN?.id
                        ?: dxIdMap?.idsByKey?.get(dxMapKey)?.toString()
                        ?: legacyId

                    if (matchedCN == null && resolvedId != legacyId) {
                        migratedPairs += MigratedIdPair(legacyId = legacyId, resolvedId = resolvedId)
                    }

                    val fromVersion = effectiveVersion(sortedSheets[0], zSong.version, region)
                    val vRank = versionRank[fromVersion] ?: 0
                    val sortOrder = vRank * 100_000 + catalogIdx * 2 + if (isDX) 1 else 0
                    val bpmInt = (zSong.bpm ?: 0.0).roundToInt()

                    val charts = sortedSheets.map { sh ->
                        val nc = sh.noteCounts
                        val tap = nc?.tap ?: 0
                        val hold = nc?.hold ?: 0
                        val slide = nc?.slide ?: 0
                        val touch = nc?.touch ?: 0
                        val brk = nc?.breakCount ?: 0
                        val notes = if (isDX) listOf(tap, hold, slide, touch, brk) else listOf(tap, hold, slide, brk)
                        SongData.RawChart(
                            charter = cleanedCharter(sh.noteDesigner),
                            notes = notes
                        )
                    }

                    val levels = sortedSheets.map { effectiveLevel(it, region) }
                    val dsValues = sortedSheets.map { effectiveDS(it, region) }
                    val cnBaselineDs = matchedCN?.let { cn ->
                        sortedSheets.indices.map { idx -> cn.ds.getOrElse(idx) { 0.0 } }
                    }

                    var resolvedAliases = matchedCN?.alias ?: cnShareableAliasesBySong["$cnLookupTitle|$mappedGenre"]
                    if (zSong.songId == "Link (2)") {
                        val list = resolvedAliases.orEmpty().toMutableList()
                        if (list.none { it.equals("Link(CoF)", ignoreCase = true) }) {
                            list.add(0, "Link(CoF)")
                        }
                        resolvedAliases = list
                    }

                    val basic = SongData.BasicInfo(
                        artist = zSong.artist,
                        bpm = bpmInt,
                        from = fromVersion,
                        genre = mappedGenre,
                        isNew = fromVersion in b15Versions,
                        title = zSong.title,
                        version = fromVersion
                    )

                    output += SongData(
                        basicInfo = basic,
                        charts = charts,
                        ds = dsValues,
                        oldDs = cnBaselineDs,
                        id = resolvedId,
                        level = levels,
                        title = zSong.title,
                        type = mappedType,
                        alias = resolvedAliases,
                        comment = null,
                        remoteCoverName = zSong.imageName,
                        preferRemoteCover = if (matchedCN == null) true else null,
                        sortOrder = sortOrder
                    )
                }
            }
        }

        return ConversionOutput(songs = output, migratedIdPairs = migratedPairs)
    }

    private fun resolveUtageBPM(
        zSong: ZetarakuSong,
        matchedCN: SongData?,
        baseBPMs: Map<String, Int>
    ): Int {
        val raw = zSong.bpm
        if (raw != null && raw > 0.0) return raw.roundToInt()
        val cnBPM = matchedCN?.basicInfo?.bpm
        if (cnBPM != null && cnBPM > 0) return cnBPM
        for (candidate in candidateBaseTitles(zSong.title)) {
            val found = baseBPMs[candidate]
            if (found != null && found > 0) return found
        }
        return 0
    }

    private fun deriveUtageNumericId(
        utageTitle: String,
        baseIds: Map<String, Int>
    ): Int? {
        for (candidate in candidateBaseTitles(utageTitle)) {
            val baseId = baseIds[candidate]
            if (baseId != null && baseId in 1 until 100_000) {
                return 100_000 + baseId
            }
        }
        return null
    }

    private fun candidateBaseTitles(rawTitle: String): List<String> {
        val stripped = stripLeadingUtagePrefix(rawTitle)
        val exact = normalizeTitleForBaseLookup(stripped)
        val candidates = mutableListOf(exact)

        for (delimiter in listOf("(", "（", "～")) {
            val idx = stripped.indexOf(delimiter)
            if (idx > 0) {
                val prefix = normalizeTitleForBaseLookup(stripped.substring(0, idx))
                if (prefix.length >= 2 && prefix !in candidates) {
                    candidates += prefix
                }
            }
        }
        if (stripped.startsWith("犬日和。")) {
            candidates += normalizeTitleForBaseLookup("ネコ日和。")
        }
        return candidates
    }

    private fun stripLeadingUtagePrefix(title: String): String {
        var s = title.trim()
        if (s.startsWith("[")) {
            val close = s.indexOf(']')
            if (close >= 0) {
                s = s.substring(close + 1).trim()
            }
        } else if (s.startsWith("(宴)")) {
            s = s.drop(3).trim()
        }
        return s
    }

    private fun normalizeTitleForBaseLookup(title: String): String =
        title.replace("　", " ").trim().lowercase(Locale.ROOT)

    private fun isSheetAvailable(sheet: ZetarakuSheet, region: MaimaiConstants.ServerRegion): Boolean =
        when (region) {
            MaimaiConstants.ServerRegion.INTL -> sheet.regions.intl == true
            MaimaiConstants.ServerRegion.JP, MaimaiConstants.ServerRegion.CN -> sheet.regions.jp == true
        }

    private fun effectiveVersion(
        sheet: ZetarakuSheet,
        songVersion: String,
        region: MaimaiConstants.ServerRegion
    ): String {
        if (region == MaimaiConstants.ServerRegion.INTL) {
            val intlVer = sheet.regionOverrides?.intl?.version
            if (!intlVer.isNullOrEmpty()) return intlVer
        }
        val sheetVer = sheet.version
        if (!sheetVer.isNullOrEmpty()) return sheetVer
        return songVersion
    }

    private fun effectiveLevel(sheet: ZetarakuSheet, region: MaimaiConstants.ServerRegion): String {
        if (region == MaimaiConstants.ServerRegion.INTL) {
            val intlLv = sheet.regionOverrides?.intl?.level
            if (!intlLv.isNullOrEmpty()) return intlLv
        }
        return sheet.level
    }

    private fun effectiveDS(sheet: ZetarakuSheet, region: MaimaiConstants.ServerRegion): Double {
        if (region == MaimaiConstants.ServerRegion.INTL) {
            val intlOverride = sheet.regionOverrides?.intl
            if (intlOverride != null) {
                intlOverride.internalLevelValue?.let { return it }
                intlOverride.levelValue?.let { return it }
            }
        }
        return sheet.internalLevelValue ?: sheet.levelValue ?: 0.0
    }

    private fun cleanedCharter(raw: String?): String = cleanedOptionalText(raw) ?: "-"

    private fun cleanedOptionalText(raw: String?): String? =
        raw?.trim()?.takeIf { it.isNotEmpty() }
}
