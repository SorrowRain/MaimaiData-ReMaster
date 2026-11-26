import Foundation

enum SyncChannelKind: String, Codable, CaseIterable, Identifiable, Hashable {
    case selfHosted
    case lxns
    case divingFish

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .selfHosted: return "国服直连"
        case .lxns: return "落雪咖啡屋"
        case .divingFish: return "水鱼查分器"
        }
    }

    var shortName: String {
        switch self {
        case .selfHosted: return "国服"
        case .lxns: return "落雪"
        case .divingFish: return "水鱼"
        }
    }
}

enum ChannelBindingStatus: Equatable {
    case unavailable
    case unbound
    case bound
    case needsReauth
}

enum SyncOperationKind: Equatable {
    case bind
    case quickSync
    case fullSync
    case pullLXNS
    case pullDivingFish
    case pullMerged
    case writeback
}

enum SyncPlan: Equatable {
    case none
    case cnDirectQuick
    case singleProber(SyncChannelKind)
    case mergedProbers
}

struct SyncOutcome: Equatable {
    let warningMessage: String?

    init(warningMessage: String? = nil) {
        self.warningMessage = warningMessage
    }
}

struct ActiveSyncTask: Equatable {
    let identityId: String?
    let operation: SyncOperationKind
}

enum SyncMode: String, Codable, CaseIterable, Hashable {
    case quick
    case full
    case lxns
    case divingFish

    var displayName: String {
        switch self {
        case .quick: return "快速同步"
        case .full: return "完整同步"
        case .lxns, .divingFish: return "同步成绩"
        }
    }

    var isSelfHosted: Bool {
        self == .quick || self == .full
    }

    var isAuthoritativeBadge: Bool {
        self == .full || self == .lxns || self == .divingFish
    }
}

struct SelfHostedBinding: Codable, Equatable, Hashable {
    let externalUserId: Int64
    var displayName: String
    var playerRating: Int?
    var boundAtEpochMs: Int64

    enum CodingKeys: String, CodingKey {
        case externalUserId, displayName, playerRating, boundAtEpochMs
    }

    init(
        externalUserId: Int64,
        displayName: String,
        playerRating: Int?,
        boundAtEpochMs: Int64
    ) {
        self.externalUserId = externalUserId
        self.displayName = displayName
        self.playerRating = playerRating
        self.boundAtEpochMs = boundAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.externalUserId = try c.decode(Int64.self, forKey: .externalUserId)
        self.displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        self.playerRating = try c.decodeIfPresent(Int.self, forKey: .playerRating)
        self.boundAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .boundAtEpochMs) ?? 0
    }
}

struct LXNSBinding: Codable, Equatable, Hashable {
    var authMethod: ScoreAuthMethod
    var accountKey: String
    var displayName: String
    var friendCode: Int64?
    var hasWriteScope: Bool
    var boundAtEpochMs: Int64

    enum CodingKeys: String, CodingKey {
        case authMethod, accountKey, displayName, friendCode, hasWriteScope, boundAtEpochMs
    }

    init(
        authMethod: ScoreAuthMethod,
        accountKey: String,
        displayName: String,
        friendCode: Int64?,
        hasWriteScope: Bool,
        boundAtEpochMs: Int64
    ) {
        self.authMethod = authMethod
        self.accountKey = accountKey
        self.displayName = displayName
        self.friendCode = friendCode
        self.hasWriteScope = hasWriteScope
        self.boundAtEpochMs = boundAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.authMethod = (try? c.decodeIfPresent(ScoreAuthMethod.self, forKey: .authMethod)) ?? .lxnsOAuth
        self.accountKey = try c.decodeIfPresent(String.self, forKey: .accountKey) ?? ""
        self.displayName = try c.decodeIfPresent(String.self, forKey: .displayName) ?? ""
        self.friendCode = try c.decodeIfPresent(Int64.self, forKey: .friendCode)
        self.hasWriteScope = try c.decodeIfPresent(Bool.self, forKey: .hasWriteScope) ?? false
        self.boundAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .boundAtEpochMs) ?? 0
    }
}

struct DivingFishBinding: Codable, Equatable, Hashable {
    var authMethod: ScoreAuthMethod
    var accountKey: String
    var username: String
    var nickname: String
    var boundAtEpochMs: Int64

    enum CodingKeys: String, CodingKey {
        case authMethod, accountKey, username, nickname, boundAtEpochMs
    }

    init(
        authMethod: ScoreAuthMethod,
        accountKey: String,
        username: String,
        nickname: String,
        boundAtEpochMs: Int64
    ) {
        self.authMethod = authMethod
        self.accountKey = accountKey
        self.username = username
        self.nickname = nickname
        self.boundAtEpochMs = boundAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.authMethod = (try? c.decodeIfPresent(ScoreAuthMethod.self, forKey: .authMethod)) ?? .divingFishPassword
        self.accountKey = try c.decodeIfPresent(String.self, forKey: .accountKey) ?? ""
        self.username = try c.decodeIfPresent(String.self, forKey: .username) ?? ""
        self.nickname = try c.decodeIfPresent(String.self, forKey: .nickname) ?? ""
        self.boundAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .boundAtEpochMs) ?? 0
    }
}

struct PlayerIdentity: Codable, Identifiable, Equatable, Hashable {
    let id: String
    var label: String
    var customNickname: String
    var hasMixedScoreSources: Bool
    var selfHosted: SelfHostedBinding?
    var lxns: LXNSBinding?
    var divingFish: DivingFishBinding?
    var lastSyncMode: SyncMode?
    var lastSyncedAtEpochMs: Int64?
    var lastFullSyncAtEpochMs: Int64?

    enum CodingKeys: String, CodingKey {
        case id, label, customNickname, hasMixedScoreSources
        case selfHosted, lxns, divingFish
        case lastSyncMode, lastSyncedAtEpochMs, lastFullSyncAtEpochMs
    }

    init(
        id: String = UUID().uuidString.lowercased(),
        label: String = "",
        customNickname: String = "",
        hasMixedScoreSources: Bool = false,
        selfHosted: SelfHostedBinding? = nil,
        lxns: LXNSBinding? = nil,
        divingFish: DivingFishBinding? = nil,
        lastSyncMode: SyncMode? = nil,
        lastSyncedAtEpochMs: Int64? = nil,
        lastFullSyncAtEpochMs: Int64? = nil
    ) {
        self.id = id.lowercased()
        self.label = label
        self.customNickname = customNickname
        self.hasMixedScoreSources = hasMixedScoreSources
        self.selfHosted = selfHosted
        self.lxns = lxns
        self.divingFish = divingFish
        self.lastSyncMode = lastSyncMode
        self.lastSyncedAtEpochMs = lastSyncedAtEpochMs
        self.lastFullSyncAtEpochMs = lastFullSyncAtEpochMs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.id = try c.decode(String.self, forKey: .id).lowercased()
        self.label = try c.decodeIfPresent(String.self, forKey: .label) ?? ""
        self.customNickname = try c.decodeIfPresent(String.self, forKey: .customNickname) ?? ""
        self.hasMixedScoreSources = try c.decodeIfPresent(Bool.self, forKey: .hasMixedScoreSources) ?? false
        self.selfHosted = try c.decodeIfPresent(SelfHostedBinding.self, forKey: .selfHosted)
        self.lxns = try c.decodeIfPresent(LXNSBinding.self, forKey: .lxns)
        self.divingFish = try c.decodeIfPresent(DivingFishBinding.self, forKey: .divingFish)
        self.lastSyncMode = (try? c.decodeIfPresent(SyncMode.self, forKey: .lastSyncMode)) ?? nil
        self.lastSyncedAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .lastSyncedAtEpochMs)
        self.lastFullSyncAtEpochMs = try c.decodeIfPresent(Int64.self, forKey: .lastFullSyncAtEpochMs)
    }

    var hasAnyChannel: Bool {
        selfHosted != nil || lxns != nil || divingFish != nil
    }

    var boundChannelCount: Int {
        var count = 0
        if selfHosted != nil { count += 1 }
        if lxns != nil { count += 1 }
        if divingFish != nil { count += 1 }
        return count
    }

    var defaultSyncMode: SyncMode? {
        if selfHosted != nil { return .quick }
        if lxns != nil { return .lxns }
        if divingFish != nil { return .divingFish }
        return nil
    }

    var primaryDisplayName: String {
        let candidates = [
            selfHosted?.displayName ?? "",
            lxns?.displayName ?? "",
            divingFish?.nickname ?? "",
            divingFish?.username ?? "",
            label
        ]
        for item in candidates {
            let trimmed = item.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty { return trimmed }
        }
        return "玩家档案"
    }

    var b50PlayerName: String {
        let trimmed = customNickname.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmed.isEmpty ? primaryDisplayName : trimmed
    }

    var syncPlan: SyncPlan {
        if selfHosted != nil { return .cnDirectQuick }
        if lxns != nil && divingFish != nil { return .mergedProbers }
        if lxns != nil { return .singleProber(.lxns) }
        if divingFish != nil { return .singleProber(.divingFish) }
        return .none
    }

    var canUploadToProbers: Bool {
        !uploadTargets.isEmpty && (selfHosted != nil || (lxns != nil && divingFish != nil))
    }

    var boundChannelsSummary: String {
        var names: [String] = []
        if selfHosted != nil {
            names.append(SyncChannelKind.selfHosted.displayName)
        }
        if lxns != nil {
            names.append(needsLXNSReauthForWrite ? "落雪（需重新授权）" : "落雪")
        }
        if divingFish != nil {
            names.append("水鱼")
        }
        return names.isEmpty ? "未绑定" : names.joined(separator: " · ")
    }

    var needsLXNSReauthForWrite: Bool {
        guard let lxns else { return false }
        return lxns.authMethod == .lxnsOAuth && !lxns.hasWriteScope
    }

    var uploadTargets: [SyncChannelKind] {
        var targets: [SyncChannelKind] = []
        if lxns != nil && !needsLXNSReauthForWrite {
            targets.append(.lxns)
        }
        if divingFish != nil {
            targets.append(.divingFish)
        }
        return targets
    }

    func bindingStatus(for channel: SyncChannelKind, cnDirectAvailable: Bool) -> ChannelBindingStatus {
        switch channel {
        case .selfHosted:
            guard cnDirectAvailable else { return .unavailable }
            return selfHosted != nil ? .bound : .unbound
        case .lxns:
            guard lxns != nil else { return .unbound }
            return needsLXNSReauthForWrite ? .needsReauth : .bound
        case .divingFish:
            return divingFish != nil ? .bound : .unbound
        }
    }

    func lastSyncText(now: Date = Date(), calendar: Calendar = .current) -> String? {
        guard let epochMs = lastSyncedAtEpochMs, epochMs > 0 else { return nil }
        let syncedDate = Date(timeIntervalSince1970: Double(epochMs) / 1000.0)
        let timeComps = calendar.dateComponents([.hour, .minute], from: syncedDate)
        let timeText = String(format: "%02d:%02d", timeComps.hour ?? 0, timeComps.minute ?? 0)
        if calendar.isDate(syncedDate, inSameDayAs: now) {
            return "上次同步 今天 \(timeText)"
        }
        if let yesterday = calendar.date(byAdding: .day, value: -1, to: now),
           calendar.isDate(syncedDate, inSameDayAs: yesterday) {
            return "上次同步 昨天 \(timeText)"
        }
        let dateComps = calendar.dateComponents([.month, .day], from: syncedDate)
        let dateText = String(format: "%02d-%02d", dateComps.month ?? 1, dateComps.day ?? 1)
        return "上次同步 \(dateText) \(timeText)"
    }
}

// MARK: - Split Keychain / Keystore Channel Secrets

struct SelfHostedEndpointConfig: Codable, Equatable {
    var serverBaseURL: String = ""
    var accessKey: String = ""

    var isConfigured: Bool {
        !serverBaseURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty &&
        !accessKey.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

struct SelfHostedSecret: Codable, Equatable {
    var syncToken: String = ""
}

struct LXNSSecret: Codable, Equatable {
    var personalToken: String = ""
    var accessToken: String = ""
    var refreshToken: String = ""
    var accessTokenExpiresAtEpochSec: Int64?
    var grantedScopes: String?
}

struct DivingFishSecret: Codable, Equatable {
    var username: String = ""
    var password: String = ""
    var cookie: String = ""
    var importToken: String = ""
}

// MARK: - Self-Hosted Proxy DTOs

struct SyncBackendBindRequest: Encodable {
    let qrContent: String
    let fullSync: Bool
}

struct SyncBackendFullRequest: Encodable {
    let qrContent: String
}

struct SyncBackendProfileDTO: Codable, Equatable {
    let userId: Int64
    let userName: String?
    let playerRating: Int?
    let iconId: Int?
    let nameplateId: Int?
    let trophyId: Int?
    let lastDataVersion: String?
    let lastPlayDate: String?
}

struct SyncBackendRecordDTO: Codable, Equatable {
    let songId: Int
    let levelIndex: Int
    let achievements: Double
    let dxScore: Int
    let fc: String?
    let fs: String?

    enum CodingKeys: String, CodingKey {
        case songId, levelIndex, achievements, dxScore, fc, fs
    }

    init(
        songId: Int,
        levelIndex: Int,
        achievements: Double,
        dxScore: Int = 0,
        fc: String? = nil,
        fs: String? = nil
    ) {
        self.songId = songId
        self.levelIndex = levelIndex
        self.achievements = achievements
        self.dxScore = dxScore
        self.fc = fc
        self.fs = fs
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.songId = try c.decode(Int.self, forKey: .songId)
        self.levelIndex = try c.decode(Int.self, forKey: .levelIndex)
        self.achievements = try c.decode(Double.self, forKey: .achievements)
        self.dxScore = try c.decodeIfPresent(Int.self, forKey: .dxScore) ?? 0
        self.fc = try c.decodeIfPresent(String.self, forKey: .fc)
        self.fs = try c.decodeIfPresent(String.self, forKey: .fs)
    }
}

struct SyncBackendResponse: Codable, Equatable {
    let syncToken: String?
    let mode: String
    let profile: SyncBackendProfileDTO
    let records: [SyncBackendRecordDTO]
    let recordCount: Int
    let syncedAtUnixMs: Int64
    let cached: Bool?

    enum CodingKeys: String, CodingKey {
        case syncToken, mode, profile, records, recordCount, syncedAtUnixMs, cached
    }

    init(
        syncToken: String? = nil,
        mode: String,
        profile: SyncBackendProfileDTO,
        records: [SyncBackendRecordDTO] = [],
        recordCount: Int = 0,
        syncedAtUnixMs: Int64 = 0,
        cached: Bool? = nil
    ) {
        self.syncToken = syncToken
        self.mode = mode
        self.profile = profile
        self.records = records
        self.recordCount = recordCount
        self.syncedAtUnixMs = syncedAtUnixMs
        self.cached = cached
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.syncToken = try c.decodeIfPresent(String.self, forKey: .syncToken)
        self.mode = try c.decode(String.self, forKey: .mode)
        self.profile = try c.decode(SyncBackendProfileDTO.self, forKey: .profile)
        let decodedRecords = try c.decodeIfPresent([SyncBackendRecordDTO].self, forKey: .records) ?? []
        self.records = decodedRecords
        self.recordCount = try c.decodeIfPresent(Int.self, forKey: .recordCount) ?? decodedRecords.count
        self.syncedAtUnixMs = try c.decodeIfPresent(Int64.self, forKey: .syncedAtUnixMs) ?? 0
        self.cached = try c.decodeIfPresent(Bool.self, forKey: .cached)
    }
}

struct SyncBackendErrorEnvelope: Decodable {
    struct ErrorDetail: Decodable {
        let code: String
        let message: String
        let returnCode: Int?
        let retryAfterSec: Int?

        enum CodingKeys: String, CodingKey {
            case code, message, returnCode, retryAfterSec
        }

        init(from decoder: Decoder) throws {
            let c = try decoder.container(keyedBy: CodingKeys.self)
            self.code = try c.decodeIfPresent(String.self, forKey: .code) ?? ""
            self.message = try c.decodeIfPresent(String.self, forKey: .message) ?? ""
            self.returnCode = try c.decodeIfPresent(Int.self, forKey: .returnCode)
            self.retryAfterSec = try c.decodeIfPresent(Int.self, forKey: .retryAfterSec)
        }
    }
    let error: ErrorDetail
}

// MARK: - Third-Party Writeback Payloads

struct DivingFishUploadRecordDTO: Codable, Equatable {
    let achievements: Double
    let title: String
    let type: String
    let levelIndex: Int
    let fc: String
    let fs: String
    let dxScore: Int

    enum CodingKeys: String, CodingKey {
        case achievements, title, type
        case levelIndex = "level_index"
        case fc, fs, dxScore
    }
}

struct LXNSUploadScoreDTO: Codable, Equatable {
    let id: Int
    let levelIndex: Int
    let achievements: Double
    let fc: String?
    let fs: String?
    let dxScore: Int?
    let type: String

    enum CodingKeys: String, CodingKey {
        case id
        case levelIndex = "level_index"
        case achievements, fc, fs
        case dxScore = "dx_score"
        case type
    }
}

struct LXNSUploadScoresRequest: Codable, Equatable {
    let scores: [LXNSUploadScoreDTO]
}

// MARK: - Sync Result, Writeback Report & Local History Snapshot

struct WritebackChannelStatus: Equatable {
    let channel: SyncChannelKind
    let uploadedCount: Int
    let skippedCount: Int
    let errorMessage: String?

    var succeeded: Bool { errorMessage == nil }
}

struct WritebackSummary: Equatable {
    let results: [WritebackChannelStatus]

    var isAllSucceeded: Bool {
        results.allSatisfy(\.succeeded)
    }

    var hasAnySucceeded: Bool {
        results.contains(where: \.succeeded)
    }

    var summaryText: String {
        guard !results.isEmpty else { return "" }
        if isAllSucceeded {
            return "上传成功"
        }
        let succeededNames = results
            .filter(\.succeeded)
            .map(\.channel.shortName)
            .joined(separator: "、")
        let failedText = results
            .filter { !$0.succeeded }
            .map { "\($0.channel.shortName)上传失败：\($0.errorMessage ?? "请稍后重试")" }
            .joined(separator: "；")
        return succeededNames.isEmpty ? failedText : "\(succeededNames)上传成功，\(failedText)"
    }
}

struct SyncHistoryEntry: Codable, Equatable {
    let identityId: String
    let timestampEpochMs: Int64
    let syncMode: SyncMode
    let cnRating: Int
    let b35Rating: Int
    let b15Rating: Int
    let recordCount: Int
    let playedSongsCount: Int
    let isBadgeBaseline: Bool
}
