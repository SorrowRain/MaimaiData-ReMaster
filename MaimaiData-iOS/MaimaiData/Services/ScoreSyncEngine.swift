import Foundation

/// Pure stateless score merge, third-party writeback payload builder, CN rating snapshot,
/// and v2->v3 profile upgrade engine. Shared 1:1 with Android `ScoreSyncEngine.kt`.
enum ScoreSyncEngine {
    struct CNRatingSnapshot: Equatable {
        let cnRating: Int
        let b35Rating: Int
        let b15Rating: Int
    }

    struct WritebackBuildResult<Upload: Equatable>: Equatable {
        let uploads: [Upload]
        let skippedDeletedCount: Int
        let sampleSkippedSongIds: [Int]
    }

    static func fcRank(_ fc: String) -> Int {
        switch fc.lowercased() {
        case "fc": return 1
        case "fcp": return 2
        case "ap": return 3
        case "app": return 4
        default: return 0
        }
    }

    static func fsRank(_ fs: String) -> Int {
        switch fs.lowercased() {
        case "sync": return 1
        case "fs": return 2
        case "fsp": return 3
        case "fsd": return 4
        case "fsdp": return 5
        default: return 0
        }
    }

    static func maxFC(_ lhs: String, _ rhs: String) -> String {
        let l = lhs.lowercased()
        let r = rhs.lowercased()
        return fcRank(l) >= fcRank(r) ? l : r
    }

    static func maxFS(_ lhs: String, _ rhs: String) -> String {
        let l = lhs.lowercased()
        let r = rhs.lowercased()
        return fsRank(l) >= fsRank(r) ? l : r
    }

    static func normalizeBackendRecords(
        _ dtos: [SyncBackendRecordDTO],
        songsById: [Int: SongData],
        region: MaimaiConstants.ServerRegion
    ) -> [ProberRecord] {
        var bestByKey: [String: ProberRecord] = [:]
        bestByKey.reserveCapacity(dtos.count)

        for dto in dtos {
            let isUtage = dto.songId >= 100_000 || dto.levelIndex >= 10
            let canonicalLevelIndex: Int = {
                guard isUtage else { return dto.levelIndex }
                return (dto.levelIndex == 1 || dto.levelIndex == 11) ? 11 : 10
            }()
            let song = songsById[dto.songId]
            let recordType: String = {
                if isUtage { return "utage" }
                if let song { return song.type }
                return dto.songId >= 10_000 ? MaimaiConstants.chartTypeDX : MaimaiConstants.chartTypeSD
            }()
            let chartIdx = song?.chartArrayIndex(forRecordLevelIndex: canonicalLevelIndex)
            let ds = isUtage ? 0 : (chartIdx.flatMap { song?.ds[safe: $0] } ?? 0)
            let level = chartIdx.flatMap { song?.level[safe: $0] } ?? "-"
            let title = song?.title ?? "Song #\(dto.songId)"
            let normalizedFC = normalizedBadgeWithAchievementFloor(
                fc: (dto.fc ?? "").lowercased(),
                achievements: dto.achievements
            )
            let fs = (dto.fs ?? "").lowercased()
            let rate = LXNSCatalogConverter.fallbackRateString(for: dto.achievements)
            let isAP = (normalizedFC == "ap" || normalizedFC == "app")
            let ra = isUtage ? 0 : RatingCalculator.singleSongRating(
                ds: ds,
                achievement: dto.achievements,
                isAP: isAP,
                apBonusEnabled: region.usesAPBonus
            )

            let record = ProberRecord(
                achievements: dto.achievements,
                ds: ds,
                fc: normalizedFC,
                fs: fs,
                level: level,
                levelIndex: canonicalLevelIndex,
                ra: ra,
                rate: rate,
                songId: dto.songId,
                title: title,
                type: recordType,
                dxScore: dto.dxScore,
                apBonusEnabled: region.usesAPBonus
            )

            let key = "\(record.songId)_\(record.levelIndex)"
            if let existing = bestByKey[key] {
                bestByKey[key] = mergePairBest(existing: existing, incoming: record)
            } else {
                bestByKey[key] = record
            }
        }

        return Array(bestByKey.values)
    }

    /// Quick Sync: merges public rival scores with existing local state so FC/FS badges are preserved
    /// while achievement >= 101.0000% promotes FC to AP+.
    static func mergeQuickSync(
        existing localRecords: [ProberRecord],
        incoming remoteRecords: [ProberRecord]
    ) -> [ProberRecord] {
        var mergedByKey: [String: ProberRecord] = [:]
        mergedByKey.reserveCapacity(max(localRecords.count, remoteRecords.count))

        for local in localRecords {
            let key = recordMergeKey(local)
            if let prev = mergedByKey[key] {
                mergedByKey[key] = mergePairBest(existing: prev, incoming: local)
            } else {
                mergedByKey[key] = local
            }
        }

        for remote in remoteRecords {
            let key = recordMergeKey(remote)
            if let local = mergedByKey[key] {
                mergedByKey[key] = mergePairBest(existing: local, incoming: remote)
            } else {
                let upgradedFC = normalizedBadgeWithAchievementFloor(fc: remote.fc, achievements: remote.achievements)
                mergedByKey[key] = ProberRecord(
                    achievements: remote.achievements,
                    ds: remote.ds,
                    fc: upgradedFC,
                    fs: remote.fs.lowercased(),
                    level: remote.level,
                    levelIndex: remote.levelIndex,
                    ra: remote.ra,
                    rate: LXNSCatalogConverter.fallbackRateString(for: remote.achievements),
                    songId: remote.songId,
                    title: remote.title,
                    type: remote.type,
                    dxScore: remote.dxScore,
                    apBonusEnabled: remote.apBonusEnabled
                )
            }
        }

        return sortedDeterministically(Array(mergedByKey.values))
    }

    /// Full Sync: authoritative replacement of local records with server snapshot.
    static func applyFullSync(incoming remoteRecords: [ProberRecord]) -> [ProberRecord] {
        var byKey: [String: ProberRecord] = [:]
        byKey.reserveCapacity(remoteRecords.count)

        for item in remoteRecords {
            let upgradedFC = normalizedBadgeWithAchievementFloor(fc: item.fc, achievements: item.achievements)
            let normalized = ProberRecord(
                achievements: item.achievements,
                ds: item.ds,
                fc: upgradedFC,
                fs: item.fs.lowercased(),
                level: item.level,
                levelIndex: item.levelIndex,
                ra: item.ra,
                rate: item.rate.isEmpty ? LXNSCatalogConverter.fallbackRateString(for: item.achievements) : item.rate,
                songId: item.songId,
                title: item.title,
                type: item.type,
                dxScore: item.dxScore,
                apBonusEnabled: item.apBonusEnabled
            )
            let key = recordMergeKey(normalized)
            if let prev = byKey[key] {
                byKey[key] = mergePairBest(existing: prev, incoming: normalized)
            } else {
                byKey[key] = normalized
            }
        }

        return sortedDeterministically(Array(byKey.values))
    }

    static func isEligibleForCNProberUpload(
        record: ProberRecord,
        cnSongsById: [Int: SongData]
    ) -> Bool {
        guard record.achievements > 0 else { return false }
        guard !cnSongsById.isEmpty else { return false }
        guard let song = cnSongsById[record.songId], song.isDisabled != true else {
            return false
        }
        return record.isUtage ? song.isUtage : (song.chartArrayIndex(forRecordLevelIndex: record.levelIndex) != nil)
    }

    /// Builds Diving-Fish `/player/update_records` payload: excludes Utage, 0% records, and removed/disabled CN songs,
    /// diffs strictly better records against `cloudRecords`, merges best badges so cloud badges are never downgraded, and resolves title.
    static func buildDivingFishWriteback(
        localRecords: [ProberRecord],
        cloudRecords: [ProberRecord],
        songsById: [Int: SongData],
        cnSongsById: [Int: SongData] = [:],
        musicTitlesById: [Int: String] = [:]
    ) -> WritebackBuildResult<DivingFishUploadRecordDTO> {
        var cloudByKey: [String: ProberRecord] = [:]
        for cloud in cloudRecords where !cloud.isUtage && cloud.levelIndex >= 0 && cloud.levelIndex <= 4 {
            let key = recordMergeKey(cloud)
            if let prev = cloudByKey[key] {
                cloudByKey[key] = mergePairBest(existing: prev, incoming: cloud)
            } else {
                cloudByKey[key] = cloud
            }
        }

        var uploads: [DivingFishUploadRecordDTO] = []
        var skippedDeletedCount = 0
        var sampleSkippedIds: [Int] = []

        for local in sortedDeterministically(localRecords) {
            guard !local.isUtage, local.levelIndex >= 0, local.levelIndex <= 4 else { continue }
            guard local.achievements > 0 else { continue }
            guard isEligibleForCNProberUpload(record: local, cnSongsById: cnSongsById) else {
                skippedDeletedCount += 1
                if !sampleSkippedIds.contains(local.songId), sampleSkippedIds.count < 5 {
                    sampleSkippedIds.append(local.songId)
                }
                continue
            }
            let cloud = cloudByKey[recordMergeKey(local)]
            if let cloud, !isStrictlyBetter(local: local, than: cloud) {
                continue
            }

            let title = resolveDivingFishTitle(
                local: local,
                cloud: cloud,
                songsById: songsById,
                musicTitlesById: musicTitlesById
            )
            guard !title.isEmpty else { continue }

            let mergedAchi = max(local.achievements, cloud?.achievements ?? 0)
            let mergedDx = max(local.dxScore, cloud?.dxScore ?? 0)
            let mergedFC = normalizedBadgeWithAchievementFloor(
                fc: maxFC(local.fc, cloud?.fc ?? ""),
                achievements: mergedAchi
            )
            let mergedFS = maxFS(local.fs, cloud?.fs ?? "")
            let chartType = local.type.uppercased() == MaimaiConstants.chartTypeDX
                ? MaimaiConstants.chartTypeDX
                : MaimaiConstants.chartTypeSD

            uploads.append(
                DivingFishUploadRecordDTO(
                    achievements: mergedAchi,
                    title: title,
                    type: chartType,
                    levelIndex: local.levelIndex,
                    fc: mergedFC,
                    fs: mergedFS,
                    dxScore: mergedDx
                )
            )
        }
        return WritebackBuildResult(
            uploads: uploads,
            skippedDeletedCount: skippedDeletedCount,
            sampleSkippedSongIds: sampleSkippedIds
        )
    }

    static func needsDivingFishTitleMap(
        localRecords: [ProberRecord],
        cloudRecords: [ProberRecord],
        cnSongsById: [Int: SongData] = [:]
    ) -> Bool {
        var cloudByKey: [String: ProberRecord] = [:]
        for cloud in cloudRecords where !cloud.isUtage && cloud.levelIndex >= 0 && cloud.levelIndex <= 4 {
            let key = recordMergeKey(cloud)
            if let prev = cloudByKey[key] {
                cloudByKey[key] = mergePairBest(existing: prev, incoming: cloud)
            } else {
                cloudByKey[key] = cloud
            }
        }
        for local in localRecords {
            guard !local.isUtage, local.levelIndex >= 0, local.levelIndex <= 4 else { continue }
            guard isEligibleForCNProberUpload(record: local, cnSongsById: cnSongsById) else { continue }
            guard let cloud = cloudByKey[recordMergeKey(local)] else {
                return true
            }
            if isStrictlyBetter(local: local, than: cloud),
               cloud.title.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return true
            }
        }
        return false
    }

    /// Builds LXNS `POST /user/maimai/player/scores` payload: excludes 0% records and removed/disabled CN songs,
    /// preserves Utage `id >= 100000` with `type="utage"` and `level_index=0` (deduplicating Buddy 1P/2P under one key),
    /// converts DX `songId >= 10000` back to base ID + `type="dx"`, diffs strictly better records against cloud, and merges best.
    static func buildLXNSWriteback(
        localRecords: [ProberRecord],
        cloudRecords: [ProberRecord],
        cnSongsById: [Int: SongData] = [:]
    ) -> WritebackBuildResult<LXNSUploadScoreDTO> {
        var cloudByKey: [String: ProberRecord] = [:]
        for cloud in cloudRecords {
            guard let target = lxnsWritebackTarget(for: cloud) else { continue }
            if let prev = cloudByKey[target.key] {
                cloudByKey[target.key] = mergePairBest(existing: prev, incoming: cloud)
            } else {
                cloudByKey[target.key] = cloud
            }
        }

        var localByKey: [String: (record: ProberRecord, target: LXNSWritebackTarget)] = [:]
        for local in sortedDeterministically(localRecords) {
            guard let target = lxnsWritebackTarget(for: local) else { continue }
            if let prev = localByKey[target.key] {
                localByKey[target.key] = (mergePairBest(existing: prev.record, incoming: local), target)
            } else {
                localByKey[target.key] = (local, target)
            }
        }

        let orderedLocals = localByKey.values.sorted {
            let lhs = $0.record
            let rhs = $1.record
            if lhs.songId != rhs.songId { return lhs.songId < rhs.songId }
            if lhs.levelIndex != rhs.levelIndex { return lhs.levelIndex < rhs.levelIndex }
            return lhs.type < rhs.type
        }

        var uploads: [LXNSUploadScoreDTO] = []
        var skippedDeletedCount = 0
        var sampleSkippedIds: [Int] = []

        for entry in orderedLocals {
            let local = entry.record
            let target = entry.target
            guard local.achievements > 0 else { continue }
            guard isEligibleForCNProberUpload(record: local, cnSongsById: cnSongsById) else {
                skippedDeletedCount += 1
                if !sampleSkippedIds.contains(local.songId), sampleSkippedIds.count < 5 {
                    sampleSkippedIds.append(local.songId)
                }
                continue
            }

            let cloud = cloudByKey[target.key]
            if let cloud, !isStrictlyBetter(local: local, than: cloud) {
                continue
            }

            let mergedAchi = max(local.achievements, cloud?.achievements ?? 0)
            let mergedDx = max(local.dxScore, cloud?.dxScore ?? 0)
            let mergedFC = normalizedBadgeWithAchievementFloor(
                fc: maxFC(local.fc, cloud?.fc ?? ""),
                achievements: mergedAchi
            )
            let mergedFS = maxFS(local.fs, cloud?.fs ?? "")

            uploads.append(
                LXNSUploadScoreDTO(
                    id: target.id,
                    levelIndex: target.levelIndex,
                    achievements: mergedAchi,
                    fc: mergedFC.isEmpty ? nil : mergedFC,
                    fs: mergedFS.isEmpty ? nil : mergedFS,
                    dxScore: mergedDx > 0 ? mergedDx : nil,
                    type: target.type
                )
            )
        }
        return WritebackBuildResult(
            uploads: uploads,
            skippedDeletedCount: skippedDeletedCount,
            sampleSkippedSongIds: sampleSkippedIds
        )
    }

    static func computeCNRatingSnapshot(
        records: [ProberRecord],
        songsById: [Int: SongData]
    ) -> CNRatingSnapshot {
        var oldList: [ProberRecord] = []
        var newList: [ProberRecord] = []

        for rec in records {
            guard !rec.isUtage,
                  let song = songsById[rec.songId],
                  !song.isUtage,
                  song.isDisabled != true,
                  song.chartArrayIndex(forRecordLevelIndex: rec.levelIndex) != nil else {
                continue
            }
            let projected = rec.projected(onto: song, region: .cn)
            if song.basicInfo.isNew {
                newList.append(projected)
            } else {
                oldList.append(projected)
            }
        }

        let b50Order: (ProberRecord, ProberRecord) -> Bool = { lhs, rhs in
            if lhs.computedRating != rhs.computedRating { return lhs.computedRating > rhs.computedRating }
            if lhs.achievements != rhs.achievements { return lhs.achievements > rhs.achievements }
            if lhs.songId != rhs.songId { return lhs.songId < rhs.songId }
            return lhs.levelIndex < rhs.levelIndex
        }
        oldList.sort(by: b50Order)
        newList.sort(by: b50Order)

        let b35 = oldList.prefix(35).reduce(0) { $0 + $1.computedRating }
        let b15 = newList.prefix(15).reduce(0) { $0 + $1.computedRating }
        return CNRatingSnapshot(
            cnRating: b35 + b15,
            b35Rating: b35,
            b15Rating: b15
        )
    }

    /// Mechanical 1:1 upgrade of v2 `ScoreProfile` entries to v3 `PlayerIdentity` without any merge heuristics.
    static func upgradeLegacyProfiles(
        _ legacyProfiles: [ScoreProfile],
        secretsById: [UUID: ScoreProfileSecret],
        nowEpochMs: Int64
    ) -> [(identity: PlayerIdentity, lxns: LXNSSecret?, df: DivingFishSecret?)] {
        legacyProfiles.map { profile in
            let lowerId = profile.id.uuidString.lowercased()
            let sec = secretsById[profile.id] ?? ScoreProfileSecret()
            let syncedMs = profile.lastSyncedAt.map { Int64(($0.timeIntervalSince1970 * 1000.0).rounded()) }

            switch profile.provider {
            case .lxns:
                let hasWrite = (profile.authMethod == .lxnsPersonalToken)
                let parsedFriendCode: Int64? = profile.accountKey.hasPrefix("fc_")
                    ? Int64(profile.accountKey.dropFirst(3))
                    : nil
                let binding = LXNSBinding(
                    authMethod: profile.authMethod,
                    accountKey: profile.accountKey,
                    displayName: profile.displayTitle,
                    friendCode: parsedFriendCode,
                    hasWriteScope: hasWrite,
                    boundAtEpochMs: syncedMs ?? nowEpochMs
                )
                let identity = PlayerIdentity(
                    id: lowerId,
                    label: profile.displayTitle,
                    customNickname: "",
                    selfHosted: nil,
                    lxns: binding,
                    divingFish: nil,
                    lastSyncMode: .lxns,
                    lastSyncedAtEpochMs: syncedMs,
                    lastFullSyncAtEpochMs: syncedMs
                )
                let lxnsSecret = LXNSSecret(
                    personalToken: sec.personalToken,
                    accessToken: sec.accessToken,
                    refreshToken: sec.refreshToken,
                    accessTokenExpiresAtEpochSec: sec.accessTokenExpiresAt.map { Int64($0) },
                    grantedScopes: hasWrite ? LXNSConfiguration.scopes : nil
                )
                return (identity, lxnsSecret, nil)

            case .divingFish:
                let binding = DivingFishBinding(
                    authMethod: profile.authMethod,
                    accountKey: profile.accountKey,
                    username: profile.username,
                    nickname: profile.nickname,
                    boundAtEpochMs: syncedMs ?? nowEpochMs
                )
                let identity = PlayerIdentity(
                    id: lowerId,
                    label: profile.displayTitle,
                    customNickname: "",
                    selfHosted: nil,
                    lxns: nil,
                    divingFish: binding,
                    lastSyncMode: .divingFish,
                    lastSyncedAtEpochMs: syncedMs,
                    lastFullSyncAtEpochMs: syncedMs
                )
                let dfSecret = DivingFishSecret(
                    username: profile.username,
                    password: sec.password,
                    cookie: sec.cookie,
                    importToken: sec.importToken
                )
                return (identity, nil, dfSecret)
            }
        }
    }

    static func migrateLegacyB50Nickname(
        identities: [PlayerIdentity],
        legacyUseProberNickname: Bool,
        legacyCustomNickname: String
    ) -> (identities: [PlayerIdentity], didChange: Bool) {
        let trimmed = legacyCustomNickname.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !legacyUseProberNickname, !trimmed.isEmpty, !identities.isEmpty else {
            return (identities, false)
        }
        var didChange = false
        let updated = identities.map { identity -> PlayerIdentity in
            guard identity.customNickname.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
                return identity
            }
            var copy = identity
            copy.customNickname = trimmed
            didChange = true
            return copy
        }
        return (updated, didChange)
    }

    // MARK: - Private Helpers

    private struct LXNSWritebackTarget {
        let key: String
        let id: Int
        let type: String
        let levelIndex: Int
    }

    private static func lxnsWritebackTarget(for record: ProberRecord) -> LXNSWritebackTarget? {
        guard record.songId > 0 else { return nil }
        guard record.isUtage || (record.levelIndex >= 0 && record.levelIndex <= 4) else { return nil }
        if record.isUtage {
            return LXNSWritebackTarget(
                key: "\(record.songId)_utage_0",
                id: record.songId,
                type: "utage",
                levelIndex: 0
            )
        }
        if record.type.uppercased() == MaimaiConstants.chartTypeDX {
            let lxnsId = record.songId >= 10_000 ? record.songId - 10_000 : record.songId
            return LXNSWritebackTarget(
                key: "\(lxnsId)_dx_\(record.levelIndex)",
                id: lxnsId,
                type: "dx",
                levelIndex: record.levelIndex
            )
        }
        return LXNSWritebackTarget(
            key: "\(record.songId)_standard_\(record.levelIndex)",
            id: record.songId,
            type: "standard",
            levelIndex: record.levelIndex
        )
    }

    static func recordMergeKey(_ rec: ProberRecord) -> String {
        "\(rec.songId)_\(rec.levelIndex)"
    }

    static func isStrictlyBetter(local: ProberRecord, than cloud: ProberRecord) -> Bool {
        if local.achievements > cloud.achievements + 0.00001 {
            return true
        }
        if local.dxScore > cloud.dxScore {
            return true
        }
        if fcRank(local.fc) > fcRank(cloud.fc) {
            return true
        }
        if fsRank(local.fs) > fsRank(cloud.fs) {
            return true
        }
        return false
    }

    private static func normalizedBadgeWithAchievementFloor(fc: String, achievements: Double) -> String {
        let lower = fc.lowercased()
        if achievements >= 101.0 {
            return maxFC(lower, "app")
        }
        return lower
    }

    private static func mergePairBest(existing: ProberRecord, incoming: ProberRecord) -> ProberRecord {
        let bestAchievements = max(existing.achievements, incoming.achievements)
        let bestDxScore = max(existing.dxScore, incoming.dxScore)
        let bestFC = normalizedBadgeWithAchievementFloor(
            fc: maxFC(existing.fc, incoming.fc),
            achievements: bestAchievements
        )
        let bestFS = maxFS(existing.fs, incoming.fs)

        let preferredDS = incoming.ds > 0 ? incoming.ds : existing.ds
        let preferredLevel = (incoming.level != "-" && !incoming.level.isEmpty) ? incoming.level : existing.level
        let preferredTitle = (!incoming.title.hasPrefix("Song #") && !incoming.title.isEmpty) ? incoming.title : existing.title
        let apBonus = existing.apBonusEnabled || incoming.apBonusEnabled
        let rate = LXNSCatalogConverter.fallbackRateString(for: bestAchievements)
        let isUtage = existing.isUtage || incoming.isUtage
        let ra = isUtage ? 0 : RatingCalculator.singleSongRating(
            ds: preferredDS,
            achievement: bestAchievements,
            isAP: bestFC == "ap" || bestFC == "app",
            apBonusEnabled: apBonus
        )

        return ProberRecord(
            achievements: bestAchievements,
            ds: preferredDS,
            fc: bestFC,
            fs: bestFS,
            level: preferredLevel,
            levelIndex: incoming.levelIndex,
            ra: ra,
            rate: rate,
            songId: incoming.songId,
            title: preferredTitle,
            type: incoming.type,
            dxScore: bestDxScore,
            apBonusEnabled: apBonus
        )
    }

    private static func resolveDivingFishTitle(
        local: ProberRecord,
        cloud: ProberRecord?,
        songsById: [Int: SongData],
        musicTitlesById: [Int: String]
    ) -> String {
        if let cloudTitle = cloud?.title.trimmingCharacters(in: .whitespacesAndNewlines),
           !cloudTitle.isEmpty {
            return cloudTitle
        }
        if let dfTitle = musicTitlesById[local.songId]?.trimmingCharacters(in: .whitespacesAndNewlines),
           !dfTitle.isEmpty {
            return dfTitle
        }
        if let catalogTitle = songsById[local.songId]?.title.trimmingCharacters(in: .whitespacesAndNewlines),
           !catalogTitle.isEmpty {
            return catalogTitle
        }
        let localTitle = local.title.trimmingCharacters(in: .whitespacesAndNewlines)
        if !localTitle.isEmpty && !localTitle.hasPrefix("Song #") {
            return localTitle
        }
        return ""
    }

    private static func sortedDeterministically(_ records: [ProberRecord]) -> [ProberRecord] {
        records.sorted {
            if $0.songId != $1.songId { return $0.songId < $1.songId }
            if $0.levelIndex != $1.levelIndex { return $0.levelIndex < $1.levelIndex }
            return $0.type < $1.type
        }
    }

    static func normalizeCachedRecords(_ records: [ProberRecord]) -> [ProberRecord] {
        guard !records.isEmpty else { return [] }
        var bestByKey: [String: ProberRecord] = [:]
        bestByKey.reserveCapacity(records.count)

        for rec in records {
            let isUtage = rec.songId >= 100_000 || rec.levelIndex >= 10 || rec.type.lowercased() == "utage"
            let normalized: ProberRecord
            if isUtage {
                let canonicalIdx = (rec.levelIndex == 1 || rec.levelIndex == 11) ? 11 : 10
                let fc = normalizedBadgeWithAchievementFloor(fc: rec.fc.lowercased(), achievements: rec.achievements)
                normalized = ProberRecord(
                    achievements: rec.achievements,
                    ds: 0,
                    fc: fc,
                    fs: rec.fs.lowercased(),
                    level: rec.level,
                    levelIndex: canonicalIdx,
                    ra: 0,
                    rate: LXNSCatalogConverter.fallbackRateString(for: rec.achievements),
                    songId: rec.songId,
                    title: rec.title,
                    type: "utage",
                    dxScore: rec.dxScore,
                    apBonusEnabled: rec.apBonusEnabled
                )
            } else {
                normalized = rec
            }
            let key = recordMergeKey(normalized)
            if let existing = bestByKey[key] {
                bestByKey[key] = mergePairBest(existing: existing, incoming: normalized)
            } else {
                bestByKey[key] = normalized
            }
        }
        return sortedDeterministically(Array(bestByKey.values))
    }
}
