import Foundation

struct ChartStatsResponse: Codable {
    let charts: [String: [ChartStatEntry]]
}

struct ChartStatEntry: Codable {
    let fitDiff: Double?

    enum CodingKeys: String, CodingKey {
        case fitDiff = "fit_diff"
    }
}

struct ProberRecordsResponse: Codable {
    let username: String?
    let nickname: String?
    let records: [ProberRecord]?
    let status: String?
    let message: String?
}

struct ProberRecord: Codable, Identifiable, Hashable {
    var id: String { "\(songId)_\(levelIndex)_\(type)" }

    let achievements: Double
    let ds: Double
    let fc: String
    let fs: String
    let level: String
    let levelIndex: Int
    let ra: Int
    let rate: String
    let songId: Int
    let title: String
    let type: String
    var apBonusEnabled: Bool = false

    enum CodingKeys: String, CodingKey {
        case achievements, ds
        case fc, fs, level
        case levelIndex = "level_index"
        case ra, rate
        case songId = "song_id"
        case title, type
    }

    var difficulty: DifficultyType {
        DifficultyType(rawValue: levelIndex) ?? .unknown
    }

    var isAllPerfect: Bool {
        fc == "ap" || fc == "app"
    }

    var computedRating: Int {
        RatingCalculator.singleSongRating(
            ds: ds,
            achievement: achievements,
            isAP: isAllPerfect,
            apBonusEnabled: apBonusEnabled
        )
    }

    /// Re-projects a player's CN record onto the active server's chart constants and AP+1 rule.
    func projected(onto song: SongData, region: MaimaiConstants.ServerRegion) -> ProberRecord {
        guard region != .cn else { return self }
        let targetDS = song.ds[safe: levelIndex] ?? ds
        let targetLevel = song.level[safe: levelIndex] ?? level
        return ProberRecord(
            achievements: achievements,
            ds: targetDS,
            fc: fc,
            fs: fs,
            level: targetLevel,
            levelIndex: levelIndex,
            ra: ra,
            rate: rate,
            songId: songId,
            title: title,
            type: type,
            apBonusEnabled: region.usesAPBonus
        )
    }

    func rankAssetName(for region: MaimaiConstants.ServerRegion) -> String {
        MaimaiConstants.rankAssetName(for: rate, region: region)
    }

    var fcAssetName: String? {
        switch fc {
        case "fc", "fcp", "ap", "app":
            return "mmd_player_rtsong_\(fc)"
        default:
            return nil
        }
    }

    var fsAssetName: String? {
        switch fs {
        case "fs", "fsp", "fsd", "fsdp":
            return "mmd_player_rtsong_\(fs)"
        default:
            return nil
        }
    }

    var rankAccessibilityLabel: String {
        let display = rate.uppercased().replacingOccurrences(of: "P", with: "+")
        return "评级 \(display)"
    }

    var fcAccessibilityLabel: String {
        let display: String
        switch fc {
        case "fc": display = "FC"
        case "fcp": display = "FC+"
        case "ap": display = "AP"
        case "app": display = "AP+"
        default: display = fc.uppercased()
        }
        return "连击状态 \(display)"
    }

    var fsAccessibilityLabel: String {
        let display: String
        switch fs {
        case "fs": display = "FS"
        case "fsp": display = "FS+"
        case "fsd": display = "FSD"
        case "fsdp": display = "FSD+"
        default: display = fs.uppercased()
        }
        return "同步状态 \(display)"
    }

    var typeAssetName: String {
        type == MaimaiConstants.chartTypeDX ? "mmd_player_rtsong_icon_dx" : "mmd_player_rtsong_icon_standard"
    }

    var ratingBoardAssetName: String {
        switch levelIndex {
        case 0: return "mmd_rating_board_bsc"
        case 1: return "mmd_rating_board_adv"
        case 2: return "mmd_rating_board_exp"
        case 3: return "mmd_rating_board_mas"
        default: return "mmd_rating_board_rem"
        }
    }

    var ratingDiffAssetName: String {
        switch levelIndex {
        case 0: return "mmd_rating_diff_basic"
        case 1: return "mmd_rating_diff_advanced"
        case 2: return "mmd_rating_diff_expert"
        case 3: return "mmd_rating_diff_master"
        default: return "mmd_rating_diff_remaster"
        }
    }
}

enum ScoreProvider: String, Codable, CaseIterable, Identifiable, Hashable {
    case lxns
    case divingFish

    var id: String { rawValue }

    var displayName: String {
        switch self {
        case .lxns: return "落雪咖啡屋"
        case .divingFish: return "水鱼查分器"
        }
    }

    var shortName: String {
        switch self {
        case .lxns: return "落雪"
        case .divingFish: return "水鱼"
        }
    }
}

enum ScoreAuthMethod: String, Codable, Hashable {
    case lxnsOAuth
    case lxnsPersonalToken
    case divingFishPassword
    case divingFishImportToken

    var provider: ScoreProvider {
        switch self {
        case .lxnsOAuth, .lxnsPersonalToken:
            return .lxns
        case .divingFishPassword, .divingFishImportToken:
            return .divingFish
        }
    }

    var shortTitle: String {
        switch self {
        case .lxnsOAuth: return "OAuth 授权"
        case .lxnsPersonalToken: return "个人密钥"
        case .divingFishPassword: return "账号密码"
        case .divingFishImportToken: return "导入 Token"
        }
    }
}

struct ScoreProfile: Codable, Identifiable, Equatable, Hashable {
    let id: UUID
    let provider: ScoreProvider
    var authMethod: ScoreAuthMethod
    /// Stable deduplication identifier within a provider (e.g. Diving-Fish username, LXNS OIDC `sub`, or token hash).
    var accountKey: String
    /// Secondary human-readable handle (e.g. username or friend code).
    var username: String
    /// In-game / prober nickname returned by the provider.
    var nickname: String
    var lastSyncedAt: Date?

    var displayTitle: String {
        let trimmedNick = nickname.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedNick.isEmpty { return trimmedNick }
        let trimmedUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedUser.isEmpty { return trimmedUser }
        return "\(provider.shortName)档案"
    }

    var subtitleText: String {
        var parts: [String] = [provider.shortName, authMethod.shortTitle]
        let trimmedUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        if !trimmedUser.isEmpty && trimmedUser != displayTitle {
            parts.append(trimmedUser)
        }
        return parts.joined(separator: " · ")
    }
}

struct ScoreProfileSecret: Codable, Equatable {
    var password: String = ""
    var cookie: String = ""
    var importToken: String = ""
    var personalToken: String = ""
    var accessToken: String = ""
    var refreshToken: String = ""
    var accessTokenExpiresAt: TimeInterval? = nil
}

/// Legacy single-provider model retained strictly for seamless Keychain migration.
struct SavedAccount: Codable, Identifiable, Equatable {
    var id: String { username }
    let username: String
    let password: String
}

struct ChecklistStats: Equatable {
    let sssCount: Int
    let fcCount: Int
    let apCount: Int
    let fsdCount: Int
    let totalCount: Int

    init(records: [ProberRecord], totalCount: Int) {
        self.sssCount = records.filter { $0.achievements >= 100 }.count
        self.fcCount = records.filter { !$0.fc.isEmpty }.count
        self.apCount = records.filter { $0.fc == "ap" || $0.fc == "app" }.count
        self.fsdCount = records.filter { $0.fs == "fsd" || $0.fs == "fsdp" }.count
        self.totalCount = totalCount
    }
}

enum SongSortOption: String, CaseIterable, Identifiable {
    case defaultDesc = "默认排序"
    case expertAsc = "EXPERT-升序"
    case expertDesc = "EXPERT-降序"
    case masterAsc = "MASTER-升序"
    case masterDesc = "MASTER-降序"
    case remasterAsc = "RE:MASTER-升序"
    case remasterDesc = "RE:MASTER-降序"

    var id: String { rawValue }

    var filterDifficulty: DifficultyType? {
        switch self {
        case .defaultDesc: return nil
        case .expertAsc, .expertDesc: return .expert
        case .masterAsc, .masterDesc: return .master
        case .remasterAsc, .remasterDesc: return .remaster
        }
    }
}

struct SongFilterCriteria: Equatable {
    var keyword: String = ""
    var selectedGenres: Set<String> = []
    var selectedVersions: Set<String> = []
    var selectedLevel: String = "ALL"
    var useExactDS: Bool = false
    var exactDS: Double = 1.0
    var onlyFavorites: Bool = false
    var sortOption: SongSortOption = .defaultDesc

    var isActive: Bool {
        !selectedGenres.isEmpty ||
        !selectedVersions.isEmpty ||
        selectedLevel != "ALL" ||
        useExactDS ||
        onlyFavorites ||
        sortOption != .defaultDesc
    }
}

struct GitHubLatestReleasePayload: Decodable {
    let tagName: String
    let htmlURL: String
    let body: String?

    enum CodingKeys: String, CodingKey {
        case tagName = "tag_name"
        case htmlURL = "html_url"
        case body
    }
}

struct AppReleaseInfo: Identifiable, Equatable {
    var id: String { version }
    let version: String
    let tagName: String
    let notes: String
    let htmlURL: String

    static func from(payload: GitHubLatestReleasePayload) -> AppReleaseInfo? {
        let rawTag = payload.tagName.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleaned = rawTag.replacingOccurrences(
            of: #"^[vV]+"#,
            with: "",
            options: .regularExpression
        )
        guard !cleaned.isEmpty else { return nil }
        let trimmedNotes = (payload.body ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let cappedNotes = trimmedNotes.count > 420
            ? String(trimmedNotes.prefix(420)) + "…"
            : trimmedNotes
        return AppReleaseInfo(
            version: cleaned,
            tagName: rawTag,
            notes: cappedNotes,
            htmlURL: payload.htmlURL
        )
    }

    func isNewer(than currentVersion: String) -> Bool {
        let cleanedCurrent = currentVersion
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"^[vV]+"#, with: "", options: .regularExpression)
        return VersionString.isNewer(version, than: cleanedCurrent)
    }
}

enum IOSInstallChannel {
    case appStore
    case testFlight
    case sideloadOrDev

    static var current: IOSInstallChannel {
        let hasEmbeddedProvision = Bundle.main.path(forResource: "embedded", ofType: "mobileprovision") != nil
        if hasEmbeddedProvision {
            return .sideloadOrDev
        }
        let receiptName = Bundle.main.appStoreReceiptURL?.lastPathComponent ?? ""
        if receiptName == "sandboxReceipt" {
            return .testFlight
        }
        if receiptName == "receipt" {
            return .appStore
        }
        return .sideloadOrDev
    }
}
