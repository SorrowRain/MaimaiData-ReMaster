import Foundation

struct SongData: Codable, Identifiable, Hashable {
    let basicInfo: BasicInfo
    let charts: [RawChart]
    let ds: [Double]
    let oldDs: [Double]?
    let id: String
    let level: [String]
    let title: String
    let type: String
    var alias: [String]?
    var comment: String? = nil
    var remoteCoverName: String? = nil
    var preferRemoteCover: Bool? = nil
    var sortOrder: Int? = nil
    var releaseDate: String? = nil
    var isDisabled: Bool? = nil

    enum CodingKeys: String, CodingKey {
        case basicInfo = "basic_info"
        case charts
        case ds
        case oldDs = "old_ds"
        case id
        case level
        case title
        case type
        case alias
        case comment
        case remoteCoverName = "remote_cover"
        case preferRemoteCover = "prefer_remote_cover"
        case sortOrder = "sort_order"
        case releaseDate = "release_date"
        case isDisabled = "is_disabled"
    }

    var numericId: Int { Int(id) ?? 0 }
    var defaultSortKey: Int { sortOrder ?? numericId }
    var releaseDateSortKey: Int { Self.parseReleaseDateKey(releaseDate) }

    static func parseReleaseDateKey(_ raw: String?) -> Int {
        guard let raw, raw.count >= 10 else { return 0 }
        let parts = raw.prefix(10).split(separator: "-")
        guard parts.count == 3,
              let y = Int(parts[0]),
              let m = Int(parts[1]),
              let d = Int(parts[2]),
              y >= 2000, (1...12).contains(m), (1...31).contains(d) else {
            return 0
        }
        return y * 10_000 + m * 100 + d
    }

    static func isOrderedBeforeInDefaultSort(_ a: SongData, _ b: SongData) -> Bool {
        let aDate = a.releaseDateSortKey
        let bDate = b.releaseDateSortKey
        if aDate != bDate { return aDate > bDate }
        if a.defaultSortKey != b.defaultSortKey { return a.defaultSortKey > b.defaultSortKey }
        if a.numericId != b.numericId { return a.numericId > b.numericId }
        return a.id > b.id
    }
    var baseJacketId: Int {
        let rem = numericId % 10000
        return rem > 0 ? rem : numericId
    }
    var isUtage: Bool { basicInfo.genre == MaimaiConstants.genreUtage }
    var isDX: Bool { type == MaimaiConstants.chartTypeDX }

    func chartArrayIndex(forRecordLevelIndex recordLevelIndex: Int) -> Int? {
        let count = max(ds.count, max(level.count, charts.count))
        if isUtage {
            switch recordLevelIndex {
            case 0, 10:
                return count > 0 ? 0 : nil
            case 1, 11:
                return count > 1 ? 1 : (count > 0 ? 0 : nil)
            default:
                return nil
            }
        }
        return (recordLevelIndex >= 0 && recordLevelIndex < count) ? recordLevelIndex : nil
    }

    var resolvedCharts: [ResolvedChart] {
        charts.indices.map { idx in
            let raw = charts[idx]
            let isSD = type == MaimaiConstants.chartTypeSD
            let notes = raw.notes
            let tap = notes.indices.contains(0) ? notes[0] : 0
            let hold = notes.indices.contains(1) ? notes[1] : 0
            let slide = notes.indices.contains(2) ? notes[2] : 0
            let touch = isSD ? 0 : (notes.indices.contains(3) ? notes[3] : 0)
            let breakCount = isSD
                ? (notes.indices.contains(3) ? notes[3] : 0)
                : (notes.indices.contains(4) ? notes[4] : 0)
            let difficulty = DifficultyType.from(genre: basicInfo.genre, index: idx)
            let currentDs = ds.indices.contains(idx) ? ds[idx] : 0
            let rawOldDs = (oldDs?.indices.contains(idx) == true) ? oldDs![idx] : nil
            let meaningfulOldDs: Double? = {
                guard let old = rawOldDs, old > 0, abs(old - currentDs) >= 0.05 else { return nil }
                return old
            }()
            return ResolvedChart(
                index: idx,
                difficulty: difficulty,
                ds: currentDs,
                oldDs: meaningfulOldDs,
                level: level.indices.contains(idx) ? level[idx] : "-",
                charter: raw.charter,
                kanji: raw.kanji,
                tap: tap,
                hold: hold,
                slide: slide,
                touch: touch,
                breakCount: breakCount,
                totalNotes: notes.reduce(0, +)
            )
        }
    }

    /// Ordered CDN cover URLs: domestic LXNS / Diving-Fish for CN-indexed songs,
    /// with Zetaraku CloudFront cover CDN directly for JP/INTL-exclusive songs (and as fallback).
    var coverCandidateURLs: [URL] {
        guard numericId > 0 else {
            return [Self.zetarakuCoverURL(for: remoteCoverName)].compactMap { $0 }
        }
        var urls: [URL] = []
        if preferRemoteCover == true, let remoteURL = Self.zetarakuCoverURL(for: remoteCoverName) {
            urls.append(remoteURL)
        }
        if let lxns = URL(string: "https://assets2.lxns.net/maimai/jacket/\(baseJacketId).png") {
            urls.append(lxns)
        }
        let paddedFull = String(format: "%05d", numericId)
        if let dfFull = URL(string: "https://www.diving-fish.com/covers/\(paddedFull).png") {
            urls.append(dfFull)
        }
        if baseJacketId != numericId {
            let paddedBase = String(format: "%05d", baseJacketId)
            if let dfBase = URL(string: "https://www.diving-fish.com/covers/\(paddedBase).png") {
                urls.append(dfBase)
            }
        }
        if preferRemoteCover != true, let remoteURL = Self.zetarakuCoverURL(for: remoteCoverName) {
            urls.append(remoteURL)
        }
        return urls
    }

    private static func zetarakuCoverURL(for fileName: String?) -> URL? {
        guard let fileName, !fileName.isEmpty,
              let encoded = fileName.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed) else {
            return nil
        }
        return URL(string: "https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/\(encoded)")
    }

    struct BasicInfo: Codable, Hashable {
        let artist: String
        let bpm: Int
        let from: String
        let genre: String
        let isNew: Bool
        let title: String
        let version: String

        enum CodingKeys: String, CodingKey {
            case artist, bpm, from, genre
            case isNew = "is_new"
            case title
            case version
        }
    }

    struct RawChart: Codable, Hashable {
        let charter: String
        let notes: [Int]
        var kanji: String? = nil
    }

    /// Synchronizes song-level community aliases across SD <-> DX counterpart pairs (`id ± 10000`),
    /// while keeping explicit chart-type prefixes (`标...` vs `dx...`) on their respective side.
    static func mergingCounterpartAliases(_ songs: [SongData]) -> [SongData] {
        var indexByNumericId: [Int: Int] = [:]
        indexByNumericId.reserveCapacity(songs.count)
        for (idx, song) in songs.enumerated() where !song.isUtage && song.numericId > 0 {
            indexByNumericId[song.numericId] = idx
        }

        var result = songs
        for idx in result.indices {
            let song = result[idx]
            guard !song.isUtage, song.numericId > 0, song.numericId < 10_000 else { continue }
            guard let dxIdx = indexByNumericId[song.numericId + 10_000] else { continue }
            let dxSong = result[dxIdx]
            guard !dxSong.isUtage, dxSong.title == song.title, dxSong.isDX != song.isDX else { continue }

            let sdOwn = song.alias ?? []
            let dxOwn = dxSong.alias ?? []

            let sdShareable = sdOwn.filter { !Self.isStandardOnlyAlias($0) }
            let dxShareable = dxOwn.filter { !Self.isDXOnlyAlias($0) }

            let mergedSD = Self.mergeUniqueAliases(primary: sdOwn, secondary: dxShareable)
            let mergedDX = Self.mergeUniqueAliases(primary: dxOwn, secondary: sdShareable)

            if !mergedSD.isEmpty { result[idx].alias = mergedSD }
            if !mergedDX.isEmpty { result[dxIdx].alias = mergedDX }
        }
        return result
    }

    static func isChartTypeScopedAlias(_ raw: String) -> Bool {
        isStandardOnlyAlias(raw) || isDXOnlyAlias(raw)
    }

    private static func isStandardOnlyAlias(_ raw: String) -> Bool {
        let lower = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return lower.hasPrefix("标") || lower.hasPrefix("sd")
    }

    private static func isDXOnlyAlias(_ raw: String) -> Bool {
        let lower = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return lower.hasPrefix("dx")
    }

    private static func mergeUniqueAliases(primary: [String], secondary: [String]) -> [String] {
        var seen = Set<String>()
        var merged: [String] = []
        for item in primary + secondary {
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let key = trimmed.lowercased()
            if seen.insert(key).inserted {
                merged.append(trimmed)
            }
        }
        return merged
    }
}

struct ResolvedChart: Identifiable, Hashable {
    var id: Int { index }
    let index: Int
    let difficulty: DifficultyType
    let ds: Double
    let oldDs: Double?
    let level: String
    let charter: String
    let kanji: String?
    let tap: Int
    let hold: Int
    let slide: Int
    let touch: Int
    let breakCount: Int
    let totalNotes: Int

    /// Canonical level index used to match `ProberRecord.levelIndex` (Utage is 10/11 while array `index` is 0/1).
    var recordLevelIndex: Int {
        switch difficulty {
        case .utage: return 10
        case .utagePlayer2: return 11
        default: return index
        }
    }

    var totalWeightedScore: Int {
        (tap + touch) + hold * 2 + slide * 3 + breakCount * 5
    }

    struct ToleranceMetrics {
        let tapGreat: Double
        let tapGood: Double
        let tapMiss: Double
        let holdGreat: Double
        let holdGood: Double
        let holdMiss: Double
        let slideGreat: Double
        let slideGood: Double
        let slideMiss: Double
        let breakPerfect50: Double?
        let breakPerfect100: Double?
        let breakGreat4x: Double?
        let breakGreat3x: Double?
        let breakGreat25x: Double?
        let breakGood: Double?
        let breakMiss: Double?
    }

    var toleranceMetrics: ToleranceMetrics? {
        guard totalWeightedScore > 0 else { return nil }
        let total = Double(totalWeightedScore)
        let brk = Double(breakCount)
        let hasBreak = breakCount > 0
        return ToleranceMetrics(
            tapGreat: 1.0 / total * 0.2,
            tapGood: 1.0 / total * 0.5,
            tapMiss: 1.0 / total,
            holdGreat: 2.0 / total * 0.2,
            holdGood: 2.0 / total * 0.5,
            holdMiss: 2.0 / total,
            slideGreat: 3.0 / total * 0.2,
            slideGood: 3.0 / total * 0.5,
            slideMiss: 3.0 / total,
            breakPerfect50: hasBreak ? (0.01 / brk * 0.25) : nil,
            breakPerfect100: hasBreak ? (0.01 / brk * 0.5) : nil,
            breakGreat4x: hasBreak ? (5.0 / total * 0.2 + (0.01 / brk) * 0.6) : nil,
            breakGreat3x: hasBreak ? (5.0 / total * 0.4 + (0.01 / brk) * 0.6) : nil,
            breakGreat25x: hasBreak ? (5.0 / total * 0.5 + (0.01 / brk) * 0.6) : nil,
            breakGood: hasBreak ? (5.0 / total * 0.6 + (0.01 / brk) * 0.7) : nil,
            breakMiss: hasBreak ? (5.0 / total + 0.01 / brk) : nil
        )
    }

    /// FiNALE old-cabinet theoretical max (all 2600 Break), floored to 2 decimal places (`nil` for DX/Utage).
    func finaleTheoreticalMax(isDX: Bool) -> Double? {
        guard !isDX, difficulty != .utage, difficulty != .utagePlayer2 else { return nil }
        let num = Double(tap * 500 + hold * 1000 + slide * 1500 + breakCount * 2600)
        let den = Double(tap * 500 + hold * 1000 + slide * 1500 + breakCount * 2500)
        guard den > 0 else { return nil }
        return floor(num / den * 10000) / 100
    }
}

/// Navigation route carrying an optional target difficulty when jumping from B50 or checklists.
struct SongDetailRoute: Hashable {
    let song: SongData
    var initialDifficulty: DifficultyType? = nil
}
