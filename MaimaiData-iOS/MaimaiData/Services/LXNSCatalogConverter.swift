import Foundation

enum LXNSCatalogConverter {
    private static let genreMap: [String: String] = [
        "POPSアニメ": "流行&动漫",
        "niconicoボーカロイド": "niconico & VOCALOID",
        "東方Project": "东方Project",
        "ゲームバラエティ": "其他游戏",
        "maimai": "舞萌",
        "オンゲキCHUNITHM": "音击&中二节奏",
        "宴会場": MaimaiConstants.genreUtage
    ]

    private static let knownPlateMetadata: [String: (id: String, title: String, asset: String)] = [
        "真": ("真", "真 · maimai ~ PLUS", "maimai"),
        "超": ("超", "超 · GreeN", "maimai_green"),
        "檄": ("檄", "檄 · GreeN PLUS", "maimai_green_plus"),
        "橙": ("橙", "橙 · ORANGE", "maimai_orange"),
        "暁": ("暁", "暁 · ORANGE PLUS", "maimai_orange_plus"),
        "桃": ("桃", "桃 · PiNK", "maimai_pink"),
        "櫻": ("櫻", "櫻 · PiNK PLUS", "maimai_pink_plus"),
        "紫": ("紫", "紫 · MURASAKi", "maimai_murasaki"),
        "菫": ("菫", "菫 · MURASAKi PLUS", "maimai_murasaki_plus"),
        "白": ("白", "白 · MiLK", "maimai_milk"),
        "雪": ("雪", "雪 · MiLK PLUS", "maimai_milk_plus"),
        "輝": ("輝", "輝 · FiNALE", "maimai_finale"),
        "覇者/舞": ("舞", "舞 / 覇者 · 旧框全曲", "maimai_finale"),
        "熊/華": ("熊華", "熊 / 華 · 舞萌DX", "maimaidx_cn"),
        "爽/煌": ("爽煌", "爽 / 煌 · 舞萌DX 2021", "maimaidx_2021"),
        "宙/星": ("宙星", "宙 / 星 · 舞萌DX 2022", "maimaidx_2022"),
        "祭/祝": ("祭祝", "祭 / 祝 · 舞萌DX 2023", "maimaidx_2023"),
        "双/宴": ("双宴", "双 / 宴 · 舞萌DX 2024", "maimaidx_2024"),
        "鏡": ("鏡", "鏡 · 舞萌DX 2025", "maimaidx_2025")
    ]

    // MARK: - 1. CN Song & Alias Catalog Conversion

    static func convertCatalog(
        songResponse: LXNSSongListResponse,
        aliasResponse: LXNSAliasListResponse?,
        existingBaseline: [SongData] = []
    ) -> (songs: [SongData], latestMajorVersion: Int) {
        let sortedVersions = songResponse.versions.sorted { $0.version < $1.version }
        let latestMajor = sortedVersions.last?.version ?? 25500

        var aliasByBaseId: [Int: [String]] = [:]
        if let aliasResponse {
            aliasByBaseId.reserveCapacity(aliasResponse.aliases.count)
            for item in aliasResponse.aliases where !item.aliases.isEmpty {
                aliasByBaseId[item.songId] = item.aliases
            }
        }

        // Detect whether the local baseline comes from an earlier major version (so its `ds` becomes the new `oldDs`),
        // or the same major version (so we preserve its existing `oldDs` and rich Utage comments).
        let existingById: [Int: SongData] = Dictionary(
            existingBaseline.compactMap { $0.numericId > 0 ? ($0.numericId, $0) : nil },
            uniquingKeysWith: { first, _ in first }
        )
        let existingMaxMajor: Int = existingBaseline.compactMap { Int($0.basicInfo.version) }.max() ?? latestMajor
        let didAdvanceMajorVersion = existingMaxMajor < latestMajor

        func resolveFromName(for chartVersion: Int) -> String {
            guard !sortedVersions.isEmpty else { return "舞萌DX" }
            var matched = sortedVersions[0]
            for ver in sortedVersions {
                if ver.version <= chartVersion {
                    matched = ver
                } else {
                    break
                }
            }
            if matched.version >= 12_000 && matched.version < 20_000 && !matched.title.hasPrefix("maimai") {
                return "maimai \(matched.title)"
            }
            return matched.title
        }

        func resolveOldDs(songId: Int, chartCount: Int) -> [Double]? {
            guard let existing = existingById[songId] else { return nil }
            let source = didAdvanceMajorVersion ? existing.ds : (existing.oldDs ?? [])
            guard !source.isEmpty else { return nil }
            let padded = (0..<chartCount).map { idx in source[safe: idx] ?? 0 }
            return padded.contains(where: { $0 > 0 }) ? padded : nil
        }

        var result: [SongData] = []
        result.reserveCapacity(songResponse.songs.count + 120)

        for song in songResponse.songs {
            let sid = song.id
            let mappedGenre = genreMap[song.genre] ?? song.genre
            let isDisabledFlag: Bool? = (song.disabled == true) ? true : nil
            let resolvedAliases = aliasByBaseId[sid] ?? existingById[sid]?.alias ?? existingById[sid + 10_000]?.alias

            // 1. Utage songs (id >= 100000)
            if sid >= 100_000 || !(song.difficulties.utage ?? []).isEmpty {
                guard let u = song.difficulties.utage?.first else { continue }
                let cver = u.version ?? song.version
                let fromName = resolveFromName(for: cver)
                let charter = (u.noteDesigner?.isEmpty == false) ? u.noteDesigner! : "-"
                let kanji = (u.kanji?.isEmpty == false) ? u.kanji : nil
                let existingComment = existingById[sid]?.comment
                let comment: String? = {
                    if let existingComment, !existingComment.isEmpty {
                        return existingComment
                    }
                    if let desc = u.description, !desc.isEmpty {
                        return desc
                    }
                    return nil
                }()

                let inferredDs = inferUtageDs(level: u.level, explicitValue: u.levelValue)
                var rawCharts: [SongData.RawChart] = []
                var dsList: [Double] = []
                var levelList: [String] = []

                switch u.notes {
                case .buddy(let left, let right) where u.isBuddy == true:
                    rawCharts.append(SongData.RawChart(charter: charter, notes: left.dxArray, kanji: kanji))
                    rawCharts.append(SongData.RawChart(charter: charter, notes: right.dxArray, kanji: kanji))
                    dsList = [inferredDs, inferredDs]
                    levelList = [u.level, u.level]
                case .buddy(let left, _):
                    rawCharts.append(SongData.RawChart(charter: charter, notes: left.dxArray, kanji: kanji))
                    dsList = [inferredDs]
                    levelList = [u.level]
                case .single(let notes):
                    rawCharts.append(SongData.RawChart(charter: charter, notes: notes.dxArray, kanji: kanji))
                    dsList = [inferredDs]
                    levelList = [u.level]
                case .none:
                    rawCharts.append(SongData.RawChart(charter: charter, notes: [0, 0, 0, 0, 0], kanji: kanji))
                    dsList = [inferredDs]
                    levelList = [u.level]
                }

                result.append(
                    SongData(
                        basicInfo: SongData.BasicInfo(
                            artist: song.artist,
                            bpm: song.bpm,
                            from: fromName,
                            genre: MaimaiConstants.genreUtage,
                            isNew: false,
                            title: song.title,
                            version: "\(cver)"
                        ),
                        charts: rawCharts,
                        ds: dsList,
                        oldDs: nil,
                        id: "\(sid)",
                        level: levelList,
                        title: song.title,
                        type: MaimaiConstants.chartTypeDX,
                        alias: resolvedAliases,
                        comment: comment,
                        isDisabled: isDisabledFlag
                    )
                )
                continue
            }

            // 2. Standard charts
            if !song.difficulties.standard.isEmpty {
                let stdDiffs = song.difficulties.standard.sorted { $0.difficulty < $1.difficulty }
                let cver = stdDiffs.first?.version ?? song.version
                let fromName = resolveFromName(for: cver)
                let isNew = cver >= latestMajor
                let charts = stdDiffs.map { diff in
                    SongData.RawChart(
                        charter: (diff.noteDesigner?.isEmpty == false) ? diff.noteDesigner! : "-",
                        notes: diff.notes?.sdArray ?? [0, 0, 0, 0]
                    )
                }
                result.append(
                    SongData(
                        basicInfo: SongData.BasicInfo(
                            artist: song.artist,
                            bpm: song.bpm,
                            from: fromName,
                            genre: mappedGenre,
                            isNew: isNew,
                            title: song.title,
                            version: "\(cver)"
                        ),
                        charts: charts,
                        ds: stdDiffs.map(\.levelValue),
                        oldDs: resolveOldDs(songId: sid, chartCount: stdDiffs.count),
                        id: "\(sid)",
                        level: stdDiffs.map(\.level),
                        title: song.title,
                        type: MaimaiConstants.chartTypeSD,
                        alias: resolvedAliases,
                        isDisabled: isDisabledFlag
                    )
                )
            }

            // 3. DX charts (`id < 10000` normalized to `id + 10000`)
            if !song.difficulties.dx.isEmpty {
                let dxDiffs = song.difficulties.dx.sorted { $0.difficulty < $1.difficulty }
                let dxSongId = sid < 10_000 ? sid + 10_000 : sid
                let cver = dxDiffs.first?.version ?? song.version
                let fromName = resolveFromName(for: cver)
                let isNew = cver >= latestMajor
                let charts = dxDiffs.map { diff in
                    SongData.RawChart(
                        charter: (diff.noteDesigner?.isEmpty == false) ? diff.noteDesigner! : "-",
                        notes: diff.notes?.dxArray ?? [0, 0, 0, 0, 0]
                    )
                }
                result.append(
                    SongData(
                        basicInfo: SongData.BasicInfo(
                            artist: song.artist,
                            bpm: song.bpm,
                            from: fromName,
                            genre: mappedGenre,
                            isNew: isNew,
                            title: song.title,
                            version: "\(cver)"
                        ),
                        charts: charts,
                        ds: dxDiffs.map(\.levelValue),
                        oldDs: resolveOldDs(songId: dxSongId, chartCount: dxDiffs.count),
                        id: "\(dxSongId)",
                        level: dxDiffs.map(\.level),
                        title: song.title,
                        type: MaimaiConstants.chartTypeDX,
                        alias: resolvedAliases,
                        isDisabled: isDisabledFlag
                    )
                )
            }
        }

        return (result, latestMajor)
    }

    // MARK: - 2. CN Official Plate Rules Extraction

    static func extractPlateCatalog(
        from response: LXNSPlateListResponse,
        updatedAt: String = Self.todayISOString()
    ) -> CNPlateCatalog {
        struct DraftGroup {
            var prefixes: [String]
            var description: String
            var badges: [String]
            var b2mOrdered: [Int]
            var b2mSet: Set<Int>
            var remOrdered: [Int]
            var remSet: Set<Int>
        }

        let validSuffixes = ["極", "将", "神", "舞舞"]
        var drafts: [DraftGroup] = []

        for plate in response.plates {
            guard plate.genre == "実績",
                  let reqs = plate.required,
                  let firstReq = reqs.first else { continue }
            let name = plate.name
            let isVersionPlate = (name == "覇者") || validSuffixes.contains(where: { name.hasSuffix($0) })
            guard isVersionPlate else { continue }

            let b2mIds = (firstReq.songs ?? []).map { normalizeSongId(id: $0.id, type: $0.type) }
            guard b2mIds.count > 1 else { continue }
            let remIds: [Int] = reqs.count > 1
                ? (reqs[1].songs ?? []).map { normalizeSongId(id: $0.id, type: $0.type) }
                : []

            let prefix = (name == "覇者") ? "覇者" : String(name.prefix(1))
            let b2mSet = Set(b2mIds)
            let remSet = Set(remIds)

            if let idx = drafts.firstIndex(where: { $0.b2mSet == b2mSet && $0.remSet == remSet }) {
                if !drafts[idx].prefixes.contains(prefix) {
                    drafts[idx].prefixes.append(prefix)
                }
                if !drafts[idx].badges.contains(name) {
                    drafts[idx].badges.append(name)
                }
            } else {
                drafts.append(
                    DraftGroup(
                        prefixes: [prefix],
                        description: plate.description ?? "",
                        badges: [name],
                        b2mOrdered: b2mIds,
                        b2mSet: b2mSet,
                        remOrdered: remIds,
                        remSet: remSet
                    )
                )
            }
        }

        let groups: [CNPlateRuleGroup] = drafts.map { draft in
            let key = draft.prefixes.joined(separator: "/")
            if let meta = knownPlateMetadata[key] {
                return CNPlateRuleGroup(
                    id: meta.id,
                    title: meta.title,
                    versionAsset: meta.asset,
                    badges: draft.badges,
                    basicToMasterSongIds: draft.b2mOrdered,
                    remasterSongIds: draft.remOrdered
                )
            }
            let gid = draft.prefixes.joined()
            let versionLabel: String = {
                if let range = draft.description.range(of: " 全曲") {
                    return String(draft.description[..<range.lowerBound])
                }
                return draft.description.components(separatedBy: "/").first ?? gid
            }()
            let asset = MaimaiConstants.cnVersionAsset(for: versionLabel) ?? MaimaiConstants.latestCNVersion.asset
            return CNPlateRuleGroup(
                id: gid,
                title: "\(draft.prefixes.joined(separator: " / ")) · \(versionLabel)",
                versionAsset: asset,
                badges: draft.badges,
                basicToMasterSongIds: draft.b2mOrdered,
                remasterSongIds: draft.remOrdered
            )
        }

        return CNPlateCatalog(updatedAt: updatedAt, groups: groups)
    }

    // MARK: - 3. LXNS Player Score -> ProberRecord Conversion

    static func convertScores(
        _ scores: [LXNSScore],
        songsById: [Int: SongData]
    ) -> [ProberRecord] {
        var bestByKey: [String: ProberRecord] = [:]
        bestByKey.reserveCapacity(scores.count)

        for score in scores {
            let songId = normalizeSongId(id: score.id, type: score.type)
            let lowerType = score.type.lowercased()
            let isUtage = (lowerType == "utage" || songId >= 100_000)
            let canonicalLevelIndex: Int = {
                guard isUtage else { return score.levelIndex }
                return (score.levelIndex == 1 || score.levelIndex == 11) ? 11 : 10
            }()
            let recordType: String = {
                if isUtage { return "utage" }
                return (lowerType == "standard") ? MaimaiConstants.chartTypeSD : MaimaiConstants.chartTypeDX
            }()
            let song = songsById[songId]
            let chartIdx = song?.chartArrayIndex(forRecordLevelIndex: canonicalLevelIndex)
            let ds = isUtage ? 0 : (chartIdx.flatMap { song?.ds[safe: $0] } ?? 0)
            let level = chartIdx.flatMap { song?.level[safe: $0] } ?? score.level ?? "-"
            let title = song?.title ?? score.songName ?? "Song #\(songId)"
            let fc = (score.fc ?? "").lowercased()
            let fs = (score.fs ?? "").lowercased()
            let dxScore = score.dxScore ?? 0
            let rate = (score.rate?.isEmpty == false)
                ? score.rate!.lowercased()
                : fallbackRateString(for: score.achievements)
            let computedRa: Int = {
                if isUtage { return 0 }
                if ds > 0 {
                    return RatingCalculator.singleSongRating(ds: ds, achievement: score.achievements)
                }
                if let dxRating = score.dxRating {
                    return Int(floor(dxRating))
                }
                return 0
            }()

            let record = ProberRecord(
                achievements: score.achievements,
                ds: ds,
                fc: fc,
                fs: fs,
                level: level,
                levelIndex: canonicalLevelIndex,
                ra: computedRa,
                rate: rate,
                songId: songId,
                title: title,
                type: recordType,
                dxScore: dxScore
            )

            let key = "\(songId)_\(canonicalLevelIndex)"
            if let existing = bestByKey[key], !shouldReplaceScore(existing: existing, candidate: record) {
                continue
            }
            bestByKey[key] = record
        }

        return Array(bestByKey.values)
    }

    private static func shouldReplaceScore(existing: ProberRecord, candidate: ProberRecord) -> Bool {
        if candidate.achievements != existing.achievements {
            return candidate.achievements > existing.achievements
        }
        let fcC = badgeRankFC(candidate.fc)
        let fcE = badgeRankFC(existing.fc)
        if fcC != fcE { return fcC > fcE }
        let fsC = badgeRankFS(candidate.fs)
        let fsE = badgeRankFS(existing.fs)
        if fsC != fsE { return fsC > fsE }
        return candidate.dxScore > existing.dxScore
    }

    private static func badgeRankFC(_ value: String) -> Int {
        switch value.lowercased() {
        case "fc": return 1
        case "fcp": return 2
        case "ap": return 3
        case "app": return 4
        default: return 0
        }
    }

    private static func badgeRankFS(_ value: String) -> Int {
        switch value.lowercased() {
        case "sync": return 1
        case "fs": return 2
        case "fsp": return 3
        case "fsd": return 4
        case "fsdp": return 5
        default: return 0
        }
    }

    // MARK: - 4. Multi-Region Unified Plate Scopes (CN / INTL / JP)

    /// SD-era Re:MASTER internal IDs present on JP/INTL in addition to CN's 59 `舞` Re:MASTER IDs
    /// (verified against DXRating `plates.ts` & `dxdata`: 39, ようこそジャパリパークへ, 妄想感傷代償連盟, ヒバナ, キミとボクのミライ, Lost Princess).
    private static let additionalOverseasSdRemasterIds: Set<Int> = [146, 668, 731, 792, 826, 827]
    private static let additionalOverseasSdRemasterTitles: Set<String> = [
        "39", "ようこそジャパリパークへ", "妄想感傷代償連盟", "ヒバナ", "キミとボクのミライ", "Lost Princess"
    ]

    static func buildPlateScopes(
        for region: MaimaiConstants.ServerRegion,
        cnPlateGroups: [CNPlateRuleGroup],
        activeSongs: [SongData],
        zetarakuVersions: [ZetarakuVersionEntry]? = nil
    ) -> [PlateCheckScope] {
        switch region {
        case .cn:
            return cnPlateGroups.map { group in
                PlateCheckScope(
                    id: group.id,
                    title: group.title,
                    versionAsset: group.resolvedVersionAsset,
                    rule: .explicitIds(
                        basicToMaster: Set(group.basicToMasterSongIds),
                        remaster: Set(group.remasterSongIds)
                    )
                )
            }

        case .intl, .jp:
            let cnMaiRemasterIds = Set(cnPlateGroups.first(where: { $0.id == "舞" })?.remasterSongIds ?? [])
            let sdRemasterIds = cnMaiRemasterIds.union(additionalOverseasSdRemasterIds)

            var scopes: [PlateCheckScope] = [
                PlateCheckScope(
                    id: "真",
                    title: "真 · maimai ~ PLUS",
                    versionAsset: "maimai",
                    rule: .versions(
                        includedVersions: ["maimai", "maimai PLUS"],
                        excludedIds: [70],
                        excludedStandardTitles: ["ジングルベル"]
                    )
                ),
                PlateCheckScope(id: "超", title: "超 · GreeN", versionAsset: "maimai_green", rule: .versions(includedVersions: ["GreeN"])),
                PlateCheckScope(id: "檄", title: "檄 · GreeN PLUS", versionAsset: "maimai_green_plus", rule: .versions(includedVersions: ["GreeN PLUS"])),
                PlateCheckScope(id: "橙", title: "橙 · ORANGE", versionAsset: "maimai_orange", rule: .versions(includedVersions: ["ORANGE"])),
                PlateCheckScope(id: "暁", title: "暁 · ORANGE PLUS", versionAsset: "maimai_orange_plus", rule: .versions(includedVersions: ["ORANGE PLUS"])),
                PlateCheckScope(id: "桃", title: "桃 · PiNK", versionAsset: "maimai_pink", rule: .versions(includedVersions: ["PiNK"])),
                PlateCheckScope(id: "櫻", title: "櫻 · PiNK PLUS", versionAsset: "maimai_pink_plus", rule: .versions(includedVersions: ["PiNK PLUS"])),
                PlateCheckScope(id: "紫", title: "紫 · MURASAKi", versionAsset: "maimai_murasaki", rule: .versions(includedVersions: ["MURASAKi"])),
                PlateCheckScope(
                    id: "菫",
                    title: "菫 · MURASAKi PLUS",
                    versionAsset: "maimai_murasaki_plus",
                    rule: .versions(
                        includedVersions: ["MURASAKi PLUS"],
                        excludedIds: [853],
                        excludedStandardTitles: ["前前前世"]
                    )
                ),
                PlateCheckScope(id: "白", title: "白 · MiLK", versionAsset: "maimai_milk", rule: .versions(includedVersions: ["MiLK"])),
                PlateCheckScope(id: "雪", title: "雪 · MiLK PLUS", versionAsset: "maimai_milk_plus", rule: .versions(includedVersions: ["MiLK PLUS"])),
                PlateCheckScope(id: "輝", title: "輝 · FiNALE", versionAsset: "maimai_finale", rule: .versions(includedVersions: ["FiNALE"])),
                PlateCheckScope(
                    id: "舞",
                    title: "舞 / 覇者 · 旧框全曲",
                    versionAsset: "maimai_finale",
                    rule: .versions(
                        includedVersions: MaimaiConstants.overseasClassicVersions,
                        excludedIds: [853],
                        excludedStandardTitles: ["前前前世"],
                        sdRemasterIds: sdRemasterIds,
                        sdRemasterFallbackTitles: additionalOverseasSdRemasterTitles
                    )
                ),
                PlateCheckScope(id: "熊", title: "熊 · でらっくす", versionAsset: "maimaidx", rule: .versions(includedVersions: ["maimaiでらっくす"])),
                PlateCheckScope(id: "華", title: "華 · でらっくす PLUS", versionAsset: "maimaidx_plus", rule: .versions(includedVersions: ["maimaiでらっくす PLUS"])),
                PlateCheckScope(id: "爽", title: "爽 · Splash", versionAsset: "maimaidx_splash", rule: .versions(includedVersions: ["Splash"])),
                PlateCheckScope(id: "煌", title: "煌 · Splash PLUS", versionAsset: "maimaidx_splash_plus", rule: .versions(includedVersions: ["Splash PLUS"])),
                PlateCheckScope(id: "宙", title: "宙 · UNiVERSE", versionAsset: "maimaidx_universe", rule: .versions(includedVersions: ["UNiVERSE"])),
                PlateCheckScope(id: "星", title: "星 · UNiVERSE PLUS", versionAsset: "maimaidx_universe_plus", rule: .versions(includedVersions: ["UNiVERSE PLUS"])),
                PlateCheckScope(id: "祭", title: "祭 · FESTiVAL", versionAsset: "maimaidx_festival", rule: .versions(includedVersions: ["FESTiVAL"])),
                PlateCheckScope(id: "祝", title: "祝 · FESTiVAL PLUS", versionAsset: "maimaidx_festival_plus", rule: .versions(includedVersions: ["FESTiVAL PLUS"])),
                PlateCheckScope(id: "双", title: "双 · BUDDiES", versionAsset: "maimaidx_buddies", rule: .versions(includedVersions: ["BUDDiES"])),
                PlateCheckScope(id: "宴", title: "宴 · BUDDiES PLUS", versionAsset: "maimaidx_buddies_plus", rule: .versions(includedVersions: ["BUDDiES PLUS"])),
                PlateCheckScope(id: "鏡", title: "鏡 · PRiSM", versionAsset: "maimaidx_prism", rule: .versions(includedVersions: ["PRiSM"])),
                PlateCheckScope(id: "彩", title: "彩 · PRiSM PLUS", versionAsset: "maimaidx_prism_plus", rule: .versions(includedVersions: ["PRiSM PLUS"])),
                PlateCheckScope(id: "丸", title: "丸 · CiRCLE", versionAsset: "maimaidx_circle", rule: .versions(includedVersions: ["CiRCLE"]))
            ]

            let presentVersions = Set(activeSongs.filter { !$0.isUtage }.map(\.basicInfo.from))
            let abbrByVer = Dictionary(
                (zetarakuVersions ?? []).map { ($0.version, $0.abbr ?? "") },
                uniquingKeysWith: { first, _ in first }
            )

            if region == .jp {
                scopes.append(
                    PlateCheckScope(
                        id: "廻",
                        title: "廻 · CiRCLE PLUS",
                        versionAsset: "maimaidx_circle_plus",
                        rule: .versions(includedVersions: ["CiRCLE PLUS"])
                    )
                )
                let magicalKanji = extractTrailingKanji(from: abbrByVer["MAGiCAL"])
                scopes.append(
                    PlateCheckScope(
                        id: magicalKanji ?? "MAGiCAL",
                        title: magicalKanji.map { "\($0) · MAGiCAL" } ?? "MAGiCAL",
                        versionAsset: "maimaidx_magical",
                        rule: .versions(includedVersions: ["MAGiCAL"])
                    )
                )
            } else {
                // On INTL, `廻` arrives once INTL advances to MAGiCAL; until then `CiRCLE PLUS` is the active version.
                let intlHasMagical = presentVersions.contains("MAGiCAL")
                scopes.append(
                    PlateCheckScope(
                        id: intlHasMagical ? "廻" : "CiRCLE PLUS",
                        title: intlHasMagical ? "廻 · CiRCLE PLUS" : "CiRCLE PLUS",
                        versionAsset: "maimaidx_circle_plus",
                        rule: .versions(includedVersions: ["CiRCLE PLUS"])
                    )
                )
                if intlHasMagical {
                    let magicalKanji = extractTrailingKanji(from: abbrByVer["MAGiCAL"])
                    scopes.append(
                        PlateCheckScope(
                            id: magicalKanji ?? "MAGiCAL",
                            title: magicalKanji.map { "\($0) · MAGiCAL" } ?? "MAGiCAL",
                            versionAsset: "maimaidx_magical",
                            rule: .versions(includedVersions: ["MAGiCAL"])
                        )
                    )
                }
            }

            // Future-proof: automatically append any newly released versions beyond the static registry from Zetaraku `versions`.
            if let zetarakuVersions {
                for entry in zetarakuVersions where !MaimaiConstants.knownJPVersionNames.contains(entry.version) {
                    guard region == .jp || presentVersions.contains(entry.version) else { continue }
                    let kanji = extractTrailingKanji(from: entry.abbr)
                    let asset = MaimaiConstants.jpVersionAssetByName(entry.version) ?? MaimaiConstants.latestJPVersion.asset
                    scopes.append(
                        PlateCheckScope(
                            id: kanji ?? entry.version,
                            title: kanji.map { "\($0) · \(entry.version)" } ?? entry.version,
                            versionAsset: asset,
                            rule: .versions(includedVersions: [entry.version])
                        )
                    )
                }
            }

            return scopes
        }
    }

    /// Parses `"MAGiCAL (魔)"` -> `"魔"` when Zetaraku assigns a plate character in `versions[].abbr`.
    private static func extractTrailingKanji(from abbr: String?) -> String? {
        guard let abbr,
              let open = abbr.lastIndex(of: "("),
              let close = abbr.lastIndex(of: ")"),
              open < close else {
            return nil
        }
        let inner = abbr[abbr.index(after: open)..<close].trimmingCharacters(in: .whitespaces)
        return inner.isEmpty ? nil : inner
    }

    // MARK: - Helpers

    static func normalizeSongId(id: Int, type: String) -> Int {
        let lower = type.lowercased()
        if lower == "utage" || id >= 100_000 {
            return id
        }
        if lower == "dx" && id < 10_000 {
            return id + 10_000
        }
        return id
    }

    private static func inferUtageDs(level: String, explicitValue: Double?) -> Double {
        if let explicitValue, explicitValue > 0 {
            return explicitValue
        }
        let cleaned = level.replacingOccurrences(of: "?", with: "").trimmingCharacters(in: .whitespaces)
        if cleaned.hasSuffix("+"), let base = Double(cleaned.dropLast()) {
            return base + 0.6
        }
        return Double(cleaned) ?? 0
    }

    static func fallbackRateString(for achievements: Double) -> String {
        let achi = Int((achievements * 10_000).rounded())
        switch achi {
        case 1_005_000...: return "sssp"
        case 1_000_000..<1_005_000: return "sss"
        case 995_000..<1_000_000: return "ssp"
        case 990_000..<995_000: return "ss"
        case 980_000..<990_000: return "sp"
        case 970_000..<980_000: return "s"
        case 940_000..<970_000: return "aaa"
        case 900_000..<940_000: return "aa"
        case 800_000..<900_000: return "a"
        case 750_000..<800_000: return "bbb"
        case 700_000..<750_000: return "bb"
        case 600_000..<700_000: return "b"
        case 500_000..<600_000: return "c"
        default: return "d"
        }
    }

    private static func todayISOString() -> String {
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter.string(from: Date())
    }
}
