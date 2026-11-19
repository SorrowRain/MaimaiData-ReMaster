import Foundation

struct ZetarakuCatalog: Codable, Sendable {
    let songs: [ZetarakuSong]
    let versions: [ZetarakuVersionEntry]?
    let updateTime: String?

    init(songs: [ZetarakuSong], versions: [ZetarakuVersionEntry]? = nil, updateTime: String? = nil) {
        self.songs = songs
        self.versions = versions
        self.updateTime = updateTime
    }
}

struct ZetarakuVersionEntry: Codable, Sendable, Equatable {
    let version: String
    let abbr: String?
}

struct ZetarakuSong: Codable, Sendable {
    let songId: String
    let category: String
    let title: String
    let artist: String
    let bpm: Double?
    let imageName: String
    let version: String
    let releaseDate: String?
    let comment: String?
    let sheets: [ZetarakuSheet]
}

struct ZetarakuSheet: Codable, Sendable {
    let type: String
    let difficulty: String
    let level: String
    let levelValue: Double?
    let internalLevelValue: Double?
    let noteDesigner: String?
    let noteCounts: ZetarakuNoteCounts?
    let regions: ZetarakuRegions
    let regionOverrides: ZetarakuRegionOverrides?
    let version: String?
}

struct ZetarakuNoteCounts: Codable, Sendable {
    let tap: Int?
    let hold: Int?
    let slide: Int?
    let touch: Int?
    let breakCount: Int?
    let total: Int?

    enum CodingKeys: String, CodingKey {
        case tap, hold, slide, touch
        case breakCount = "break"
        case total
    }
}

struct ZetarakuRegions: Codable, Sendable {
    let jp: Bool?
    let intl: Bool?
    let usa: Bool?
    let cn: Bool?
}

struct ZetarakuRegionOverrides: Codable, Sendable {
    let intl: ZetarakuSheetOverride?
}

struct ZetarakuSheetOverride: Codable, Sendable {
    let level: String?
    let levelValue: Double?
    let internalLevelValue: Double?
    let version: String?
}

/// Compact persisted lookup table distilled from DXRating's live `dxdata` endpoint (`miruku.dxrating.net/api/v1/dxdata`).
struct DXDataIdMap: Codable, Sendable {
    let updatedAt: String?
    let idsByKey: [String: Int]

    static func extract(from raw: DXDataRawCatalog) -> DXDataIdMap {
        var map: [String: Int] = [:]
        map.reserveCapacity(raw.songs.count + 256)

        for song in raw.songs {
            let cnGenre = ZetarakuCatalogConverter.mapCategoryToCNGenre(song.category)
            if cnGenre == MaimaiConstants.genreUtage {
                if let rawId = song.sheets.compactMap(\.internalId).first(where: { $0 > 0 }) {
                    let normalized = rawId < 100_000 ? (100_000 + rawId) : rawId
                    map["utage|\(song.title)"] = normalized
                }
            } else {
                for sheet in song.sheets {
                    guard let rawId = sheet.internalId, rawId > 0 else { continue }
                    if sheet.type == "std" {
                        let key = "\(song.title)|\(cnGenre)|\(MaimaiConstants.chartTypeSD)"
                        if map[key] == nil { map[key] = rawId }
                    } else if sheet.type == "dx" {
                        let normalized = rawId < 10_000 ? (10_000 + rawId) : rawId
                        let key = "\(song.title)|\(cnGenre)|\(MaimaiConstants.chartTypeDX)"
                        if map[key] == nil { map[key] = normalized }
                    }
                }
            }
        }

        return DXDataIdMap(updatedAt: raw.updatedAt, idsByKey: map)
    }
}

struct DXDataRawCatalog: Decodable, Sendable {
    let updatedAt: String?
    let songs: [DXDataRawSong]
}

struct DXDataRawSong: Decodable, Sendable {
    let category: String
    let title: String
    let sheets: [DXDataRawSheet]
}

struct DXDataRawSheet: Decodable, Sendable {
    let type: String
    let internalId: Int?
}

/// Pure converter transforming Zetaraku `maimai/data.json` + optional live `DXDataIdMap` into `[SongData]`
/// for `.jp` or `.intl`, joining CN IDs, aliases, Utage comments/BPMs, and CN baseline constants (`oldDs`).
enum ZetarakuCatalogConverter {
    struct ConversionOutput: Sendable {
        let songs: [SongData]
        /// Pairs of `(legacySyntheticId, resolvedNumericId)` for migrating user favorites smoothly after online ID updates.
        let migratedIdPairs: [(legacyId: String, resolvedId: String)]
    }

    private struct CNLookupKey: Hashable {
        let title: String
        let genre: String
        let type: String
    }

    private static let difficultyOrder: [String: Int] = [
        "basic": 0,
        "advanced": 1,
        "expert": 2,
        "master": 3,
        "remaster": 4
    ]

    static func mapCategoryToCNGenre(_ category: String) -> String {
        switch category {
        case "POPS＆アニメ":
            return "流行&动漫"
        case "niconico＆ボーカロイド":
            return "niconico & VOCALOID"
        case "東方Project":
            return "东方Project"
        case "ゲーム＆バラエティ":
            return "其他游戏"
        case "maimai":
            return "舞萌"
        case "オンゲキ＆CHUNITHM":
            return "音击&中二节奏"
        case "宴会場":
            return MaimaiConstants.genreUtage
        default:
            return category
        }
    }

    /// Post-`CiRCLE` SEGA rule: `B15` covers the active version plus 1 immediately preceding half-version.
    static func isB15NewVersion(_ version: String, region: MaimaiConstants.ServerRegion) -> Bool {
        switch region {
        case .cn:
            return false
        case .jp:
            return version == "MAGiCAL" || version == "CiRCLE PLUS"
        case .intl:
            return version == "CiRCLE PLUS" || version == "CiRCLE" || version == "MAGiCAL"
        }
    }

    /// Backfills Utage `comment` ("谱面说明") and `kanji` badges onto CN Utage songs using cached Zetaraku metadata.
    static func enrichCNSongsWithUtageMetadata(
        _ cnSongs: [SongData],
        from catalog: ZetarakuCatalog?
    ) -> [SongData] {
        guard let catalog else { return cnSongs }
        var utageMetaByTitle: [String: (comment: String?, kanjis: [String?])] = [:]
        for zSong in catalog.songs where zSong.category == "宴会場" {
            let trimmedComment = cleanedOptionalText(zSong.comment)
            let kanjis = zSong.sheets.filter { $0.type == "utage" }.map { cleanedOptionalText($0.difficulty) }
            utageMetaByTitle[zSong.title] = (trimmedComment, kanjis)
        }
        guard !utageMetaByTitle.isEmpty else { return cnSongs }

        return cnSongs.map { song in
            guard song.isUtage, let meta = utageMetaByTitle[song.title] else { return song }
            let updatedCharts = song.charts.enumerated().map { idx, chart in
                var copy = chart
                if copy.kanji == nil {
                    copy.kanji = meta.kanjis[safe: idx] ?? meta.kanjis.first ?? nil
                }
                return copy
            }
            return SongData(
                basicInfo: song.basicInfo,
                charts: updatedCharts,
                ds: song.ds,
                oldDs: song.oldDs,
                id: song.id,
                level: song.level,
                title: song.title,
                type: song.type,
                alias: song.alias,
                comment: song.comment ?? meta.comment,
                remoteCoverName: song.remoteCoverName,
                preferRemoteCover: song.preferRemoteCover,
                isDisabled: song.isDisabled,
                sortOrder: song.sortOrder
            )
        }
    }

    static func convert(
        _ catalog: ZetarakuCatalog,
        region: MaimaiConstants.ServerRegion,
        cnReference: [SongData],
        dxIdMap: DXDataIdMap? = nil
    ) -> [SongData] {
        convertWithMigrations(
            catalog,
            region: region,
            cnReference: cnReference,
            dxIdMap: dxIdMap
        ).songs
    }

    static func convertWithMigrations(
        _ catalog: ZetarakuCatalog,
        region: MaimaiConstants.ServerRegion,
        cnReference: [SongData],
        dxIdMap: DXDataIdMap? = nil
    ) -> ConversionOutput {
        let enrichedCN = SongData.mergingCounterpartAliases(cnReference)

        var cnByExactKey: [CNLookupKey: SongData] = [:]
        var cnShareableAliasesBySong: [String: [String]] = [:]
        var cnUtageByTitle: [String: SongData] = [:]
        var baseBPMByNormalizedTitle: [String: Int] = [:]
        var baseNumericIdByNormalizedTitle: [String: Int] = [:]
        cnByExactKey.reserveCapacity(enrichedCN.count)

        for cnSong in enrichedCN {
            if cnSong.isUtage {
                cnUtageByTitle[cnSong.title] = cnSong
            } else {
                let key = CNLookupKey(
                    title: cnSong.title,
                    genre: cnSong.basicInfo.genre,
                    type: cnSong.type
                )
                cnByExactKey[key] = cnSong
                let normTitle = normalizeTitleForBaseLookup(cnSong.title)
                if cnSong.basicInfo.bpm > 0 {
                    baseBPMByNormalizedTitle[normTitle] = cnSong.basicInfo.bpm
                }
                if cnSong.numericId > 0, cnSong.numericId < 100_000 {
                    // Prefer SD (<10000) when both SD and DX exist, matching SEGA's 100000 + baseId convention.
                    if let existing = baseNumericIdByNormalizedTitle[normTitle] {
                        if cnSong.numericId < existing {
                            baseNumericIdByNormalizedTitle[normTitle] = cnSong.numericId
                        }
                    } else {
                        baseNumericIdByNormalizedTitle[normTitle] = cnSong.numericId
                    }
                }
                if let aliases = cnSong.alias, !aliases.isEmpty {
                    let songGroupKey = "\(cnSong.title)|\(cnSong.basicInfo.genre)"
                    let filtered = aliases.filter { raw in
                        let lower = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                        return !lower.hasPrefix("标") && !lower.hasPrefix("sd") && !lower.hasPrefix("dx")
                    }
                    if !filtered.isEmpty, cnShareableAliasesBySong[songGroupKey] == nil {
                        cnShareableAliasesBySong[songGroupKey] = filtered
                    }
                }
            }
        }

        // Index BPMs and dxdata IDs from all non-Utage Zetaraku songs so JP-only Utages also recover BPM & 100000+baseId
        for zSong in catalog.songs where zSong.category != "宴会場" {
            let normTitle = normalizeTitleForBaseLookup(zSong.title)
            if let bpm = zSong.bpm, bpm > 0, baseBPMByNormalizedTitle[normTitle] == nil {
                baseBPMByNormalizedTitle[normTitle] = Int(bpm.rounded())
            }
            if baseNumericIdByNormalizedTitle[normTitle] == nil, let dxIdMap {
                let mappedGenre = mapCategoryToCNGenre(zSong.category)
                let sdKey = "\(zSong.title)|\(mappedGenre)|\(MaimaiConstants.chartTypeSD)"
                let dxKey = "\(zSong.title)|\(mappedGenre)|\(MaimaiConstants.chartTypeDX)"
                if let sdId = dxIdMap.idsByKey[sdKey] {
                    baseNumericIdByNormalizedTitle[normTitle] = sdId
                } else if let dxId = dxIdMap.idsByKey[dxKey] {
                    baseNumericIdByNormalizedTitle[normTitle] = dxId
                }
            }
        }

        var versionRank: [String: Int] = [:]
        for (idx, item) in MaimaiConstants.jpVersions.enumerated() {
            versionRank[item.name] = idx
        }

        var output: [SongData] = []
        var migratedPairs: [(legacyId: String, resolvedId: String)] = []
        output.reserveCapacity(catalog.songs.count + 256)

        for (catalogIdx, zSong) in catalog.songs.enumerated() {
            let activeSheets = zSong.sheets.filter { isSheetAvailable($0, in: region) }
            guard !activeSheets.isEmpty else { continue }

            let mappedGenre = mapCategoryToCNGenre(zSong.category)

            for sheetType in ["std", "dx", "utage"] {
                let rawGroup = activeSheets.filter { $0.type == sheetType }
                guard !rawGroup.isEmpty else { continue }

                if sheetType == "utage" {
                    let matchedCN = cnUtageByTitle[zSong.title]
                    let fromVersion = effectiveVersion(of: rawGroup[0], songVersion: zSong.version, region: region)
                    let vRank = versionRank[fromVersion] ?? 0
                    let sortOrder = vRank * 100_000 + catalogIdx * 2
                    let legacyId = "zeta_utage_\(zSong.songId)"

                    let resolvedId: String = {
                        if let cnId = matchedCN?.id { return cnId }
                        if let dxId = dxIdMap?.idsByKey["utage|\(zSong.title)"] { return String(dxId) }
                        if let derived = deriveUtageNumericId(
                            for: zSong.title,
                            baseIds: baseNumericIdByNormalizedTitle
                        ) {
                            return String(derived)
                        }
                        return legacyId
                    }()

                    if matchedCN == nil, resolvedId != legacyId {
                        migratedPairs.append((legacyId: legacyId, resolvedId: resolvedId))
                    }

                    let resolvedBPM = resolveUtageBPM(
                        zSong: zSong,
                        matchedCN: matchedCN,
                        baseBPMs: baseBPMByNormalizedTitle
                    )

                    let charts: [SongData.RawChart] = rawGroup.enumerated().map { idx, sh in
                        let charter = cleanedCharter(sh.noteDesigner)
                        let kanji = cleanedOptionalText(sh.difficulty)
                        if let nc = sh.noteCounts, nc.total != nil {
                            return SongData.RawChart(
                                charter: charter,
                                notes: [nc.tap ?? 0, nc.hold ?? 0, nc.slide ?? 0, nc.touch ?? 0, nc.breakCount ?? 0],
                                kanji: kanji
                            )
                        } else if let cnChart = matchedCN?.charts[safe: idx] {
                            return SongData.RawChart(
                                charter: cnChart.charter,
                                notes: cnChart.notes,
                                kanji: kanji ?? cnChart.kanji
                            )
                        } else {
                            return SongData.RawChart(charter: charter, notes: [0, 0, 0, 0, 0], kanji: kanji)
                        }
                    }

                    let levels = rawGroup.map { effectiveLevel(of: $0, region: region) }
                    let dsValues = rawGroup.map { effectiveDS(of: $0, region: region) }

                    let basic = SongData.BasicInfo(
                        artist: zSong.artist,
                        bpm: resolvedBPM,
                        from: fromVersion,
                        genre: MaimaiConstants.genreUtage,
                        isNew: false,
                        title: zSong.title,
                        version: fromVersion
                    )

                    output.append(
                        SongData(
                            basicInfo: basic,
                            charts: charts,
                            ds: dsValues,
                            oldDs: nil,
                            id: resolvedId,
                            level: levels,
                            title: zSong.title,
                            type: MaimaiConstants.chartTypeDX,
                            alias: matchedCN?.alias,
                            comment: cleanedOptionalText(zSong.comment),
                            remoteCoverName: zSong.imageName,
                            preferRemoteCover: matchedCN == nil ? true : nil,
                            sortOrder: sortOrder
                        )
                    )
                } else {
                    let sortedSheets = rawGroup.sorted {
                        (difficultyOrder[$0.difficulty] ?? 99) < (difficultyOrder[$1.difficulty] ?? 99)
                    }
                    let isDX = (sheetType == "dx")
                    let mappedType = isDX ? MaimaiConstants.chartTypeDX : MaimaiConstants.chartTypeSD
                    let cnLookupTitle = (zSong.songId == "Link (2)") ? "Link(CoF)" : zSong.title
                    let matchedCN = cnByExactKey[
                        CNLookupKey(title: cnLookupTitle, genre: mappedGenre, type: mappedType)
                    ]

                    let legacyId = "zeta_\(sheetType)_\(zSong.songId)"
                    let dxMapKey = "\(zSong.title)|\(mappedGenre)|\(mappedType)"
                    let resolvedId: String = {
                        if let cnId = matchedCN?.id { return cnId }
                        if let dxId = dxIdMap?.idsByKey[dxMapKey] { return String(dxId) }
                        return legacyId
                    }()

                    if matchedCN == nil, resolvedId != legacyId {
                        migratedPairs.append((legacyId: legacyId, resolvedId: resolvedId))
                    }

                    let fromVersion = effectiveVersion(of: sortedSheets[0], songVersion: zSong.version, region: region)
                    let vRank = versionRank[fromVersion] ?? 0
                    let sortOrder = vRank * 100_000 + catalogIdx * 2 + (isDX ? 1 : 0)
                    let bpmInt = Int((zSong.bpm ?? 0).rounded())

                    let charts: [SongData.RawChart] = sortedSheets.map { sh in
                        let nc = sh.noteCounts
                        let tap = nc?.tap ?? 0
                        let hold = nc?.hold ?? 0
                        let slide = nc?.slide ?? 0
                        let touch = nc?.touch ?? 0
                        let brk = nc?.breakCount ?? 0
                        let notes = isDX ? [tap, hold, slide, touch, brk] : [tap, hold, slide, brk]
                        return SongData.RawChart(
                            charter: cleanedCharter(sh.noteDesigner),
                            notes: notes
                        )
                    }

                    let levels = sortedSheets.map { effectiveLevel(of: $0, region: region) }
                    let dsValues = sortedSheets.map { effectiveDS(of: $0, region: region) }
                    let cnBaselineDs: [Double]? = matchedCN.map { cn in
                        sortedSheets.indices.map { idx in cn.ds[safe: idx] ?? 0 }
                    }

                    var resolvedAliases = matchedCN?.alias ?? cnShareableAliasesBySong["\(cnLookupTitle)|\(mappedGenre)"]
                    if zSong.songId == "Link (2)" {
                        var list = resolvedAliases ?? []
                        if !list.contains(where: { $0.caseInsensitiveCompare("Link(CoF)") == .orderedSame }) {
                            list.insert("Link(CoF)", at: 0)
                        }
                        resolvedAliases = list
                    }

                    let basic = SongData.BasicInfo(
                        artist: zSong.artist,
                        bpm: bpmInt,
                        from: fromVersion,
                        genre: mappedGenre,
                        isNew: isB15NewVersion(fromVersion, region: region),
                        title: zSong.title,
                        version: fromVersion
                    )

                    output.append(
                        SongData(
                            basicInfo: basic,
                            charts: charts,
                            ds: dsValues,
                            oldDs: cnBaselineDs,
                            id: resolvedId,
                            level: levels,
                            title: zSong.title,
                            type: mappedType,
                            alias: resolvedAliases,
                            comment: nil,
                            remoteCoverName: zSong.imageName,
                            preferRemoteCover: matchedCN == nil ? true : nil,
                            sortOrder: sortOrder
                        )
                    )
                }
            }
        }

        return ConversionOutput(songs: output, migratedIdPairs: migratedPairs)
    }

    // MARK: - Utage Base Song Lookup Helpers

    private static func resolveUtageBPM(
        zSong: ZetarakuSong,
        matchedCN: SongData?,
        baseBPMs: [String: Int]
    ) -> Int {
        if let raw = zSong.bpm, raw > 0 {
            return Int(raw.rounded())
        }
        if let cnBPM = matchedCN?.basicInfo.bpm, cnBPM > 0 {
            return cnBPM
        }
        for candidate in candidateBaseTitles(forUtageTitle: zSong.title) {
            if let found = baseBPMs[candidate], found > 0 {
                return found
            }
        }
        return 0
    }

    private static func deriveUtageNumericId(
        for utageTitle: String,
        baseIds: [String: Int]
    ) -> Int? {
        for candidate in candidateBaseTitles(forUtageTitle: utageTitle) {
            if let baseId = baseIds[candidate], baseId > 0, baseId < 100_000 {
                return 100_000 + baseId
            }
        }
        return nil
    }

    private static func candidateBaseTitles(forUtageTitle rawTitle: String) -> [String] {
        let stripped = stripLeadingUtagePrefix(rawTitle)
        let exact = normalizeTitleForBaseLookup(stripped)
        var candidates: [String] = [exact]

        // Fallback for remix/subtitled Utages (e.g. "Oshama Scramble! (Cranky Remix)" or "怒槌～光吉猛修一部謎～")
        for delimiter in ["(", "（", "～"] {
            if let range = stripped.range(of: delimiter) {
                let prefix = normalizeTitleForBaseLookup(String(stripped[..<range.lowerBound]))
                if prefix.count >= 2, !candidates.contains(prefix) {
                    candidates.append(prefix)
                }
            }
        }
        if stripped.hasPrefix("犬日和。") {
            candidates.append(normalizeTitleForBaseLookup("ネコ日和。"))
        }
        return candidates
    }

    private static func stripLeadingUtagePrefix(_ title: String) -> String {
        var s = title.trimmingCharacters(in: .whitespacesAndNewlines)
        if s.hasPrefix("["), let close = s.firstIndex(of: "]") {
            s = String(s[s.index(after: close)...]).trimmingCharacters(in: .whitespacesAndNewlines)
        } else if s.hasPrefix("(宴)") {
            s = String(s.dropFirst(3)).trimmingCharacters(in: .whitespacesAndNewlines)
        }
        return s
    }

    private static func normalizeTitleForBaseLookup(_ title: String) -> String {
        title
            .replacingOccurrences(of: "　", with: " ")
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
    }

    // MARK: - Field Helpers

    private static func isSheetAvailable(_ sheet: ZetarakuSheet, in region: MaimaiConstants.ServerRegion) -> Bool {
        switch region {
        case .intl:
            return sheet.regions.intl == true
        case .jp, .cn:
            return sheet.regions.jp == true
        }
    }

    private static func effectiveVersion(
        of sheet: ZetarakuSheet,
        songVersion: String,
        region: MaimaiConstants.ServerRegion
    ) -> String {
        if region == .intl,
           let intlVer = sheet.regionOverrides?.intl?.version,
           !intlVer.isEmpty {
            return intlVer
        }
        if let sheetVer = sheet.version, !sheetVer.isEmpty {
            return sheetVer
        }
        return songVersion
    }

    private static func effectiveLevel(of sheet: ZetarakuSheet, region: MaimaiConstants.ServerRegion) -> String {
        if region == .intl,
           let intlLv = sheet.regionOverrides?.intl?.level,
           !intlLv.isEmpty {
            return intlLv
        }
        return sheet.level
    }

    private static func effectiveDS(of sheet: ZetarakuSheet, region: MaimaiConstants.ServerRegion) -> Double {
        if region == .intl, let intlOverride = sheet.regionOverrides?.intl {
            if let internalVal = intlOverride.internalLevelValue { return internalVal }
            if let lvVal = intlOverride.levelValue { return lvVal }
        }
        return sheet.internalLevelValue ?? sheet.levelValue ?? 0
    }

    private static func cleanedCharter(_ raw: String?) -> String {
        cleanedOptionalText(raw) ?? "-"
    }

    private static func cleanedOptionalText(_ raw: String?) -> String? {
        guard let trimmed = raw?.trimmingCharacters(in: .whitespacesAndNewlines),
              !trimmed.isEmpty else {
            return nil
        }
        return trimmed
    }
}
