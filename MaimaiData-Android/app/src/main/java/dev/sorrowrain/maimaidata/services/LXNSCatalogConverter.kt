package dev.sorrowrain.maimaidata.services

import dev.sorrowrain.maimaidata.models.CNPlateCatalog
import dev.sorrowrain.maimaidata.models.CNPlateRuleGroup
import dev.sorrowrain.maimaidata.models.LXNSAliasListResponse
import dev.sorrowrain.maimaidata.models.LXNSPlateListResponse
import dev.sorrowrain.maimaidata.models.LXNSScore
import dev.sorrowrain.maimaidata.models.LXNSSongListResponse
import dev.sorrowrain.maimaidata.models.LXNSUtageNotes
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.PlateCheckScope
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.models.SongData
import java.time.LocalDate
import java.util.Locale
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * Pure converter for LXNS public song/alias/plate catalogs, player score mapping, and
 * multi-region (CN / INTL / JP) unified plate scopes. Matches iOS `LXNSCatalogConverter.swift` 1:1.
 */
object LXNSCatalogConverter {
    data class CatalogConversionResult(
        val songs: List<SongData>,
        val latestMajorVersion: Int
    )

    private data class PlateMeta(
        val id: String,
        val title: String,
        val asset: String
    )

    private val genreMap: Map<String, String> = mapOf(
        "POPSアニメ" to "流行&动漫",
        "niconicoボーカロイド" to "niconico & VOCALOID",
        "東方Project" to "东方Project",
        "ゲームバラエティ" to "其他游戏",
        "maimai" to "舞萌",
        "オンゲキCHUNITHM" to "音击&中二节奏",
        "宴会場" to MaimaiConstants.GENRE_UTAGE
    )

    private val knownPlateMetadata: Map<String, PlateMeta> = mapOf(
        "真" to PlateMeta("真", "真 · maimai ~ PLUS", "maimai"),
        "超" to PlateMeta("超", "超 · GreeN", "maimai_green"),
        "檄" to PlateMeta("檄", "檄 · GreeN PLUS", "maimai_green_plus"),
        "橙" to PlateMeta("橙", "橙 · ORANGE", "maimai_orange"),
        "暁" to PlateMeta("暁", "暁 · ORANGE PLUS", "maimai_orange_plus"),
        "桃" to PlateMeta("桃", "桃 · PiNK", "maimai_pink"),
        "櫻" to PlateMeta("櫻", "櫻 · PiNK PLUS", "maimai_pink_plus"),
        "紫" to PlateMeta("紫", "紫 · MURASAKi", "maimai_murasaki"),
        "菫" to PlateMeta("菫", "菫 · MURASAKi PLUS", "maimai_murasaki_plus"),
        "白" to PlateMeta("白", "白 · MiLK", "maimai_milk"),
        "雪" to PlateMeta("雪", "雪 · MiLK PLUS", "maimai_milk_plus"),
        "輝" to PlateMeta("輝", "輝 · FiNALE", "maimai_finale"),
        "覇者/舞" to PlateMeta("舞", "舞 · 旧框全曲", "maimai_finale"),
        "舞/覇者" to PlateMeta("舞", "舞 · 旧框全曲", "maimai_finale"),
        "熊/華" to PlateMeta("熊華", "熊 / 華 · 舞萌DX", "maimaidx_cn"),
        "爽/煌" to PlateMeta("爽煌", "爽 / 煌 · 舞萌DX 2021", "maimaidx_2021"),
        "宙/星" to PlateMeta("宙星", "宙 / 星 · 舞萌DX 2022", "maimaidx_2022"),
        "祭/祝" to PlateMeta("祭祝", "祭 / 祝 · 舞萌DX 2023", "maimaidx_2023"),
        "双/宴" to PlateMeta("双宴", "双 / 宴 · 舞萌DX 2024", "maimaidx_2024"),
        "鏡" to PlateMeta("鏡", "鏡 · 舞萌DX 2025", "maimaidx_2025")
    )

    // 1. CN Song & Alias Catalog Conversion

    fun convertCatalog(
        songResponse: LXNSSongListResponse,
        aliasResponse: LXNSAliasListResponse?,
        existingBaseline: List<SongData> = emptyList()
    ): CatalogConversionResult {
        val sortedVersions = songResponse.versions.sortedBy { it.version }
        val latestMajor = sortedVersions.lastOrNull()?.version ?: 25_500

        val aliasByBaseId = HashMap<Int, List<String>>(aliasResponse?.aliases?.size ?: 0)
        aliasResponse?.aliases?.forEach { item ->
            if (item.aliases.isNotEmpty()) {
                aliasByBaseId[item.songId] = item.aliases
            }
        }

        val existingById = HashMap<Int, SongData>(existingBaseline.size)
        for (item in existingBaseline) {
            if (item.numericId > 0) {
                existingById.putIfAbsent(item.numericId, item)
            }
        }
        val existingMaxMajor = existingBaseline
            .mapNotNull { it.basicInfo.version.toIntOrNull() }
            .maxOrNull() ?: latestMajor
        val didAdvanceMajorVersion = existingMaxMajor < latestMajor

        fun resolveFromName(chartVersion: Int): String {
            if (sortedVersions.isEmpty()) return "舞萌DX"
            var matched = sortedVersions[0]
            for (ver in sortedVersions) {
                if (ver.version <= chartVersion) {
                    matched = ver
                } else {
                    break
                }
            }
            if (matched.version in 12_000 until 20_000 && !matched.title.startsWith("maimai")) {
                return "maimai ${matched.title}"
            }
            return matched.title
        }

        fun resolveOldDs(songId: Int, chartCount: Int): List<Double>? {
            val existing = existingById[songId] ?: return null
            val source = if (didAdvanceMajorVersion) existing.ds else existing.oldDs.orEmpty()
            if (source.isEmpty()) return null
            val padded = (0 until chartCount).map { idx -> source.getOrElse(idx) { 0.0 } }
            return padded.takeIf { list -> list.any { it > 0.0 } }
        }

        val result = ArrayList<SongData>(songResponse.songs.size + 120)

        for (song in songResponse.songs) {
            val sid = song.id
            val mappedGenre = genreMap[song.genre] ?: song.genre
            val isDisabledFlag = if (song.disabled == true) true else null
            val resolvedAliases = aliasByBaseId[sid]
                ?: existingById[sid]?.alias
                ?: existingById[sid + 10_000]?.alias

            // 1. Utage songs (id >= 100000)
            if (sid >= 100_000 || !song.difficulties.utage.isNullOrEmpty()) {
                val u = song.difficulties.utage?.firstOrNull() ?: continue
                val cver = u.version ?: song.version
                val fromName = resolveFromName(cver)
                val charter = u.noteDesigner?.takeIf { it.isNotEmpty() } ?: "-"
                val kanji = u.kanji?.takeIf { it.isNotEmpty() }
                val comment = existingById[sid]?.comment?.takeIf { it.isNotEmpty() }
                    ?: u.description?.takeIf { it.isNotEmpty() }

                val inferredDs = inferUtageDs(u.level, u.levelValue)
                val rawCharts: List<SongData.RawChart>
                val dsList: List<Double>
                val levelList: List<String>

                when (val utageNotes = u.notes) {
                    is LXNSUtageNotes.Buddy -> {
                        if (u.isBuddy == true) {
                            rawCharts = listOf(
                                SongData.RawChart(charter = charter, notes = utageNotes.left.dxArray, kanji = kanji),
                                SongData.RawChart(charter = charter, notes = utageNotes.right.dxArray, kanji = kanji)
                            )
                            dsList = listOf(inferredDs, inferredDs)
                            levelList = listOf(u.level, u.level)
                        } else {
                            rawCharts = listOf(
                                SongData.RawChart(charter = charter, notes = utageNotes.left.dxArray, kanji = kanji)
                            )
                            dsList = listOf(inferredDs)
                            levelList = listOf(u.level)
                        }
                    }
                    is LXNSUtageNotes.Single -> {
                        rawCharts = listOf(
                            SongData.RawChart(charter = charter, notes = utageNotes.notes.dxArray, kanji = kanji)
                        )
                        dsList = listOf(inferredDs)
                        levelList = listOf(u.level)
                    }
                    null -> {
                        rawCharts = listOf(
                            SongData.RawChart(charter = charter, notes = listOf(0, 0, 0, 0, 0), kanji = kanji)
                        )
                        dsList = listOf(inferredDs)
                        levelList = listOf(u.level)
                    }
                }

                result += SongData(
                    basicInfo = SongData.BasicInfo(
                        artist = song.artist,
                        bpm = song.bpm,
                        from = fromName,
                        genre = MaimaiConstants.GENRE_UTAGE,
                        isNew = false,
                        title = song.title,
                        version = cver.toString()
                    ),
                    charts = rawCharts,
                    ds = dsList,
                    oldDs = null,
                    id = sid.toString(),
                    level = levelList,
                    title = song.title,
                    type = MaimaiConstants.CHART_TYPE_DX,
                    alias = resolvedAliases,
                    comment = comment,
                    releaseDate = u.releaseDate ?: song.releaseDate,
                    isDisabled = isDisabledFlag
                )
                continue
            }

            // 2. Standard charts
            if (song.difficulties.standard.isNotEmpty()) {
                val stdDiffs = song.difficulties.standard.sortedBy { it.difficulty }
                val cver = stdDiffs.first().version ?: song.version
                val fromName = resolveFromName(cver)
                val isNew = cver >= latestMajor
                val charts = stdDiffs.map { diff ->
                    SongData.RawChart(
                        charter = diff.noteDesigner?.takeIf { it.isNotEmpty() } ?: "-",
                        notes = diff.notes?.sdArray ?: listOf(0, 0, 0, 0)
                    )
                }
                result += SongData(
                    basicInfo = SongData.BasicInfo(
                        artist = song.artist,
                        bpm = song.bpm,
                        from = fromName,
                        genre = mappedGenre,
                        isNew = isNew,
                        title = song.title,
                        version = cver.toString()
                    ),
                    charts = charts,
                    ds = stdDiffs.map { it.levelValue },
                    oldDs = resolveOldDs(songId = sid, chartCount = stdDiffs.size),
                    id = sid.toString(),
                    level = stdDiffs.map { it.level },
                    title = song.title,
                    type = MaimaiConstants.CHART_TYPE_SD,
                    alias = resolvedAliases,
                    releaseDate = stdDiffs.first().releaseDate ?: song.releaseDate,
                    isDisabled = isDisabledFlag
                )
            }

            // 3. DX charts (`id < 10000` normalized to `id + 10000`)
            if (song.difficulties.dx.isNotEmpty()) {
                val dxDiffs = song.difficulties.dx.sortedBy { it.difficulty }
                val dxSongId = if (sid < 10_000) sid + 10_000 else sid
                val cver = dxDiffs.first().version ?: song.version
                val fromName = resolveFromName(cver)
                val isNew = cver >= latestMajor
                val charts = dxDiffs.map { diff ->
                    SongData.RawChart(
                        charter = diff.noteDesigner?.takeIf { it.isNotEmpty() } ?: "-",
                        notes = diff.notes?.dxArray ?: listOf(0, 0, 0, 0, 0)
                    )
                }
                result += SongData(
                    basicInfo = SongData.BasicInfo(
                        artist = song.artist,
                        bpm = song.bpm,
                        from = fromName,
                        genre = mappedGenre,
                        isNew = isNew,
                        title = song.title,
                        version = cver.toString()
                    ),
                    charts = charts,
                    ds = dxDiffs.map { it.levelValue },
                    oldDs = resolveOldDs(songId = dxSongId, chartCount = dxDiffs.size),
                    id = dxSongId.toString(),
                    level = dxDiffs.map { it.level },
                    title = song.title,
                    type = MaimaiConstants.CHART_TYPE_DX,
                    alias = resolvedAliases,
                    releaseDate = dxDiffs.first().releaseDate ?: song.releaseDate,
                    isDisabled = isDisabledFlag
                )
            }
        }

        return CatalogConversionResult(
            songs = result,
            latestMajorVersion = latestMajor
        )
    }

    // 2. CN Official Plate Rules Extraction

    fun extractPlateCatalog(
        response: LXNSPlateListResponse,
        updatedAt: String = todayISOString()
    ): CNPlateCatalog {
        class DraftGroup(
            val prefixes: MutableList<String>,
            val description: String,
            val badges: MutableList<String>,
            val b2mOrdered: List<Int>,
            val b2mSet: Set<Int>,
            val remOrdered: List<Int>,
            val remSet: Set<Int>
        )

        val validSuffixes = listOf("極", "将", "神", "舞舞")
        val drafts = mutableListOf<DraftGroup>()

        for (plate in response.plates) {
            if (plate.genre != "実績") continue
            val reqs = plate.required?.takeIf { it.isNotEmpty() } ?: continue
            val name = plate.name
            val isVersionPlate = (name == "覇者") || validSuffixes.any { name.endsWith(it) }
            if (!isVersionPlate) continue

            val b2mIds = mutableListOf<Int>()
            val b2mSet = HashSet<Int>()
            val remIds = mutableListOf<Int>()
            val remSet = HashSet<Int>()
            for (req in reqs) {
                val ids = req.songs.orEmpty().map { normalizeSongId(it.id, it.type) }
                if (ids.isEmpty()) continue
                val diffs = req.difficulties.orEmpty().toSet()
                when {
                    diffs == setOf(4) -> {
                        for (id in ids) if (remSet.add(id)) remIds += id
                    }
                    diffs.isNotEmpty() && diffs.all { it in 0..3 } -> {
                        for (id in ids) if (b2mSet.add(id)) b2mIds += id
                    }
                    4 in diffs -> {
                        for (id in ids) {
                            if (b2mSet.add(id)) b2mIds += id
                            if (remSet.add(id)) remIds += id
                        }
                    }
                    b2mIds.isEmpty() -> {
                        for (id in ids) if (b2mSet.add(id)) b2mIds += id
                    }
                    else -> {
                        for (id in ids) if (remSet.add(id)) remIds += id
                    }
                }
            }
            if (b2mIds.size <= 1) continue

            val prefix = if (name == "覇者") "覇者" else name.take(1)

            val existing = drafts.firstOrNull { it.b2mSet == b2mSet && it.remSet == remSet }
            if (existing != null) {
                if (prefix !in existing.prefixes) existing.prefixes += prefix
                if (name !in existing.badges) existing.badges += name
            } else {
                drafts += DraftGroup(
                    prefixes = mutableListOf(prefix),
                    description = plate.description.orEmpty(),
                    badges = mutableListOf(name),
                    b2mOrdered = b2mIds,
                    b2mSet = b2mSet,
                    remOrdered = remIds,
                    remSet = remSet
                )
            }
        }

        val groups = drafts.map { draft ->
            val key = draft.prefixes.joinToString("/")
            val meta = knownPlateMetadata[key]
            if (meta != null) {
                CNPlateRuleGroup(
                    id = meta.id,
                    title = meta.title,
                    versionAsset = meta.asset,
                    badges = draft.badges,
                    basicToMasterSongIds = draft.b2mOrdered,
                    remasterSongIds = draft.remOrdered
                )
            } else {
                val gid = draft.prefixes.joinToString("")
                val versionLabel = if (" 全曲" in draft.description) {
                    draft.description.substringBefore(" 全曲")
                } else {
                    draft.description.substringBefore("/").ifEmpty { gid }
                }
                val asset = MaimaiConstants.cnVersionAsset(versionLabel) ?: MaimaiConstants.latestCNVersion.asset
                CNPlateRuleGroup(
                    id = gid,
                    title = "${draft.prefixes.joinToString(" / ")} · $versionLabel",
                    versionAsset = asset,
                    badges = draft.badges,
                    basicToMasterSongIds = draft.b2mOrdered,
                    remasterSongIds = draft.remOrdered
                )
            }
        }

        return CNPlateCatalog(updatedAt = updatedAt, groups = groups)
    }

    // 3. LXNS Player Score -> ProberRecord Conversion

    fun convertScores(
        scores: List<LXNSScore>,
        songsById: Map<Int, SongData>
    ): List<ProberRecord> {
        val bestByKey = LinkedHashMap<String, ProberRecord>(scores.size)

        for (score in scores) {
            val songId = normalizeSongId(id = score.id, type = score.type)
            val lowerType = score.type.lowercase(Locale.ROOT)
            val isUtage = lowerType == "utage" || songId >= 100_000
            val canonicalLevelIndex = if (isUtage) {
                if (score.levelIndex == 1 || score.levelIndex == 11) 11 else 10
            } else {
                score.levelIndex
            }
            val recordType = when {
                isUtage -> "utage"
                lowerType == "standard" -> MaimaiConstants.CHART_TYPE_SD
                else -> MaimaiConstants.CHART_TYPE_DX
            }
            val song = songsById[songId]
            val chartIdx = song?.chartArrayIndex(canonicalLevelIndex)
            val ds = if (isUtage) 0.0 else (chartIdx?.let { song.ds.getOrNull(it) } ?: 0.0)
            val level = chartIdx?.let { song.level.getOrNull(it) } ?: score.level ?: "-"
            val title = song?.title ?: score.songName ?: "Song #$songId"
            val fc = score.fc.orEmpty().lowercase(Locale.ROOT)
            val fs = score.fs.orEmpty().lowercase(Locale.ROOT)
            val dxScore = score.dxScore ?: 0
            val rate = score.rate?.takeIf { it.isNotEmpty() }?.lowercase(Locale.ROOT)
                ?: fallbackRateString(score.achievements)
            val computedRa = when {
                isUtage -> 0
                ds > 0.0 -> RatingCalculator.singleSongRating(ds = ds, achievement = score.achievements)
                score.dxRating != null -> floor(score.dxRating).toInt()
                else -> 0
            }

            val record = ProberRecord(
                achievements = score.achievements,
                ds = ds,
                fc = fc,
                fs = fs,
                level = level,
                levelIndex = canonicalLevelIndex,
                ra = computedRa,
                rate = rate,
                songId = songId,
                title = title,
                type = recordType,
                dxScore = dxScore
            )

            val key = "${songId}_$canonicalLevelIndex"
            val existing = bestByKey[key]
            if (existing != null && !shouldReplaceScore(existing = existing, candidate = record)) {
                continue
            }
            bestByKey[key] = record
        }

        return bestByKey.values.toList()
    }

    private fun shouldReplaceScore(existing: ProberRecord, candidate: ProberRecord): Boolean {
        if (candidate.achievements != existing.achievements) {
            return candidate.achievements > existing.achievements
        }
        val fcC = badgeRankFC(candidate.fc)
        val fcE = badgeRankFC(existing.fc)
        if (fcC != fcE) return fcC > fcE
        val fsC = badgeRankFS(candidate.fs)
        val fsE = badgeRankFS(existing.fs)
        if (fsC != fsE) return fsC > fsE
        return candidate.dxScore > existing.dxScore
    }

    private fun badgeRankFC(value: String): Int = when (value.lowercase(Locale.ROOT)) {
        "fc" -> 1
        "fcp" -> 2
        "ap" -> 3
        "app" -> 4
        else -> 0
    }

    private fun badgeRankFS(value: String): Int = when (value.lowercase(Locale.ROOT)) {
        "sync" -> 1
        "fs" -> 2
        "fsp" -> 3
        "fsd" -> 4
        "fsdp" -> 5
        else -> 0
    }

    // 4. Multi-Region Unified Plate Scopes (CN / INTL / JP)

    private val additionalOverseasSdRemasterIds: Set<Int> = setOf(146, 668, 731, 792, 826, 827)
    private val additionalOverseasSdRemasterTitles: Set<String> = setOf(
        "39", "ようこそジャパリパークへ", "妄想感傷代償連盟", "ヒバナ", "キミとボクのミライ", "Lost Princess"
    )

    fun buildPlateScopes(
        region: MaimaiConstants.ServerRegion,
        cnPlateGroups: List<CNPlateRuleGroup>,
        activeSongs: List<SongData>,
        zetarakuVersions: List<ZetarakuVersionEntry>? = null
    ): List<PlateCheckScope> {
        if (region == MaimaiConstants.ServerRegion.CN) {
            var seenHasya = false
            return cnPlateGroups.flatMap { group ->
                val rule = PlateCheckScope.Rule.ExplicitIds(
                    basicToMaster = group.basicToMasterSongIds.toSet(),
                    remaster = group.remasterSongIds.toSet()
                )
                if (group.id == "覇者") {
                    if (seenHasya) return@flatMap emptyList()
                    seenHasya = true
                    return@flatMap listOf(
                        PlateCheckScope(
                            id = "覇者",
                            title = "覇者 · 旧框全曲",
                            versionAsset = "maimai_finale",
                            rule = rule,
                            goal = PlateCheckScope.Goal.CLEAR,
                            hasJiangBadge = false
                        )
                    )
                }
                val normalizedTitle = if (group.id == "舞" && "覇者" in group.title) {
                    "舞 · 旧框全曲"
                } else {
                    group.title
                }
                val hasJiang = group.id != "真" && (group.badges.isEmpty() || group.badges.any { it.endsWith("将") })
                buildList {
                    add(
                        PlateCheckScope(
                            id = group.id,
                            title = normalizedTitle,
                            versionAsset = group.resolvedVersionAsset,
                            rule = rule,
                            goal = PlateCheckScope.Goal.STANDARD,
                            hasJiangBadge = hasJiang
                        )
                    )
                    if (!seenHasya && (group.id == "舞" || "覇者" in group.badges)) {
                        seenHasya = true
                        add(
                            PlateCheckScope(
                                id = "覇者",
                                title = "覇者 · 旧框全曲",
                                versionAsset = "maimai_finale",
                                rule = rule,
                                goal = PlateCheckScope.Goal.CLEAR,
                                hasJiangBadge = false
                            )
                        )
                    }
                }
            }
        }

        val cnMaiRemasterIds = cnPlateGroups.firstOrNull { it.id == "舞" }?.remasterSongIds?.toSet().orEmpty()
        val sdRemasterIds = cnMaiRemasterIds union additionalOverseasSdRemasterIds
        val maiClassicRule = PlateCheckScope.Rule.Versions(
            includedVersions = MaimaiConstants.overseasClassicVersions,
            excludedIds = setOf(853),
            excludedStandardTitles = setOf("前前前世"),
            sdRemasterIds = sdRemasterIds,
            sdRemasterFallbackTitles = additionalOverseasSdRemasterTitles
        )

        val scopes = mutableListOf(
            PlateCheckScope(
                id = "真",
                title = "真 · maimai ~ PLUS",
                versionAsset = "maimai",
                rule = PlateCheckScope.Rule.Versions(
                    includedVersions = setOf("maimai", "maimai PLUS"),
                    excludedIds = setOf(70),
                    excludedStandardTitles = setOf("ジングルベル")
                ),
                hasJiangBadge = false
            ),
            PlateCheckScope("超", "超 · GreeN", "maimai_green", PlateCheckScope.Rule.Versions(setOf("GreeN"))),
            PlateCheckScope("檄", "檄 · GreeN PLUS", "maimai_green_plus", PlateCheckScope.Rule.Versions(setOf("GreeN PLUS"))),
            PlateCheckScope("橙", "橙 · ORANGE", "maimai_orange", PlateCheckScope.Rule.Versions(setOf("ORANGE"))),
            PlateCheckScope("暁", "暁 · ORANGE PLUS", "maimai_orange_plus", PlateCheckScope.Rule.Versions(setOf("ORANGE PLUS"))),
            PlateCheckScope("桃", "桃 · PiNK", "maimai_pink", PlateCheckScope.Rule.Versions(setOf("PiNK"))),
            PlateCheckScope("櫻", "櫻 · PiNK PLUS", "maimai_pink_plus", PlateCheckScope.Rule.Versions(setOf("PiNK PLUS"))),
            PlateCheckScope("紫", "紫 · MURASAKi", "maimai_murasaki", PlateCheckScope.Rule.Versions(setOf("MURASAKi"))),
            PlateCheckScope(
                id = "菫",
                title = "菫 · MURASAKi PLUS",
                versionAsset = "maimai_murasaki_plus",
                rule = PlateCheckScope.Rule.Versions(
                    includedVersions = setOf("MURASAKi PLUS"),
                    excludedIds = setOf(853),
                    excludedStandardTitles = setOf("前前前世")
                )
            ),
            PlateCheckScope("白", "白 · MiLK", "maimai_milk", PlateCheckScope.Rule.Versions(setOf("MiLK"))),
            PlateCheckScope("雪", "雪 · MiLK PLUS", "maimai_milk_plus", PlateCheckScope.Rule.Versions(setOf("MiLK PLUS"))),
            PlateCheckScope("輝", "輝 · FiNALE", "maimai_finale", PlateCheckScope.Rule.Versions(setOf("FiNALE"))),
            PlateCheckScope(
                id = "舞",
                title = "舞 · 旧框全曲",
                versionAsset = "maimai_finale",
                rule = maiClassicRule,
                goal = PlateCheckScope.Goal.STANDARD,
                hasJiangBadge = true
            ),
            PlateCheckScope(
                id = "覇者",
                title = "覇者 · 旧框全曲",
                versionAsset = "maimai_finale",
                rule = maiClassicRule,
                goal = PlateCheckScope.Goal.CLEAR,
                hasJiangBadge = false
            ),
            PlateCheckScope("熊", "熊 · でらっくす", "maimaidx", PlateCheckScope.Rule.Versions(setOf("maimaiでらっくす"))),
            PlateCheckScope("華", "華 · でらっくす PLUS", "maimaidx_plus", PlateCheckScope.Rule.Versions(setOf("maimaiでらっくす PLUS"))),
            PlateCheckScope("爽", "爽 · Splash", "maimaidx_splash", PlateCheckScope.Rule.Versions(setOf("Splash"))),
            PlateCheckScope("煌", "煌 · Splash PLUS", "maimaidx_splash_plus", PlateCheckScope.Rule.Versions(setOf("Splash PLUS"))),
            PlateCheckScope("宙", "宙 · UNiVERSE", "maimaidx_universe", PlateCheckScope.Rule.Versions(setOf("UNiVERSE"))),
            PlateCheckScope("星", "星 · UNiVERSE PLUS", "maimaidx_universe_plus", PlateCheckScope.Rule.Versions(setOf("UNiVERSE PLUS"))),
            PlateCheckScope("祭", "祭 · FESTiVAL", "maimaidx_festival", PlateCheckScope.Rule.Versions(setOf("FESTiVAL"))),
            PlateCheckScope("祝", "祝 · FESTiVAL PLUS", "maimaidx_festival_plus", PlateCheckScope.Rule.Versions(setOf("FESTiVAL PLUS"))),
            PlateCheckScope("双", "双 · BUDDiES", "maimaidx_buddies", PlateCheckScope.Rule.Versions(setOf("BUDDiES"))),
            PlateCheckScope("宴", "宴 · BUDDiES PLUS", "maimaidx_buddies_plus", PlateCheckScope.Rule.Versions(setOf("BUDDiES PLUS"))),
            PlateCheckScope("鏡", "鏡 · PRiSM", "maimaidx_prism", PlateCheckScope.Rule.Versions(setOf("PRiSM"))),
            PlateCheckScope("彩", "彩 · PRiSM PLUS", "maimaidx_prism_plus", PlateCheckScope.Rule.Versions(setOf("PRiSM PLUS"))),
            PlateCheckScope("丸", "丸 · CiRCLE", "maimaidx_circle", PlateCheckScope.Rule.Versions(setOf("CiRCLE")))
        )

        val presentVersions = activeSongs.asSequence()
            .filterNot { it.isUtage }
            .map { it.basicInfo.from }
            .toSet()

        val abbrByVer = LinkedHashMap<String, String>()
        zetarakuVersions?.forEach { entry ->
            abbrByVer.putIfAbsent(entry.version, entry.abbr.orEmpty())
        }

        if (region == MaimaiConstants.ServerRegion.JP) {
            scopes += PlateCheckScope(
                id = "廻",
                title = "廻 · CiRCLE PLUS",
                versionAsset = "maimaidx_circle_plus",
                rule = PlateCheckScope.Rule.Versions(setOf("CiRCLE PLUS"))
            )
            val magicalKanji = extractTrailingKanji(abbrByVer["MAGiCAL"])
            scopes += PlateCheckScope(
                id = magicalKanji ?: "MAGiCAL",
                title = magicalKanji?.let { "$it · MAGiCAL" } ?: "MAGiCAL",
                versionAsset = "maimaidx_magical",
                rule = PlateCheckScope.Rule.Versions(setOf("MAGiCAL"))
            )
        } else {
            val intlHasMagical = "MAGiCAL" in presentVersions
            scopes += PlateCheckScope(
                id = if (intlHasMagical) "廻" else "CiRCLE PLUS",
                title = if (intlHasMagical) "廻 · CiRCLE PLUS" else "CiRCLE PLUS",
                versionAsset = "maimaidx_circle_plus",
                rule = PlateCheckScope.Rule.Versions(setOf("CiRCLE PLUS"))
            )
            if (intlHasMagical) {
                val magicalKanji = extractTrailingKanji(abbrByVer["MAGiCAL"])
                scopes += PlateCheckScope(
                    id = magicalKanji ?: "MAGiCAL",
                    title = magicalKanji?.let { "$it · MAGiCAL" } ?: "MAGiCAL",
                    versionAsset = "maimaidx_magical",
                    rule = PlateCheckScope.Rule.Versions(setOf("MAGiCAL"))
                )
            }
        }

        zetarakuVersions?.forEach { entry ->
            if (entry.version in MaimaiConstants.knownJPVersionNames) return@forEach
            if (region != MaimaiConstants.ServerRegion.JP && entry.version !in presentVersions) return@forEach
            val kanji = extractTrailingKanji(entry.abbr)
            val asset = MaimaiConstants.jpVersionAssetByName(entry.version) ?: MaimaiConstants.latestJPVersion.asset
            scopes += PlateCheckScope(
                id = kanji ?: entry.version,
                title = kanji?.let { "$it · ${entry.version}" } ?: entry.version,
                versionAsset = asset,
                rule = PlateCheckScope.Rule.Versions(setOf(entry.version))
            )
        }

        return scopes
    }

    /** Parses `"MAGiCAL (魔)"` -> `"魔"` when Zetaraku assigns a plate character in `versions[].abbr`. */
    private fun extractTrailingKanji(abbr: String?): String? {
        if (abbr == null) return null
        val open = abbr.lastIndexOf('(')
        val close = abbr.lastIndexOf(')')
        if (open < 0 || close <= open) return null
        return abbr.substring(open + 1, close).trim().takeIf { it.isNotEmpty() }
    }

    fun normalizeSongId(id: Int, type: String): Int {
        val lower = type.lowercase(Locale.ROOT)
        if (lower == "utage" || id >= 100_000) return id
        if (lower == "dx" && id < 10_000) return id + 10_000
        return id
    }

    private fun inferUtageDs(level: String, explicitValue: Double?): Double {
        if (explicitValue != null && explicitValue > 0.0) return explicitValue
        val cleaned = level.replace("?", "").trim()
        if (cleaned.endsWith("+")) {
            val base = cleaned.dropLast(1).toDoubleOrNull()
            if (base != null) return base + 0.6
        }
        return cleaned.toDoubleOrNull() ?: 0.0
    }

    fun fallbackRateString(achievements: Double): String {
        val achi = (achievements * 10_000.0).roundToInt()
        return when {
            achi >= 1_005_000 -> "sssp"
            achi >= 1_000_000 -> "sss"
            achi >= 995_000 -> "ssp"
            achi >= 990_000 -> "ss"
            achi >= 980_000 -> "sp"
            achi >= 970_000 -> "s"
            achi >= 940_000 -> "aaa"
            achi >= 900_000 -> "aa"
            achi >= 800_000 -> "a"
            achi >= 750_000 -> "bbb"
            achi >= 700_000 -> "bb"
            achi >= 600_000 -> "b"
            achi >= 500_000 -> "c"
            else -> "d"
        }
    }

    private fun todayISOString(): String = LocalDate.now().toString()
}
