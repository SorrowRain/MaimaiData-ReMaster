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

struct ProberRecord: Identifiable, Hashable {
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
    let dxScore: Int
    var apBonusEnabled: Bool

    init(
        achievements: Double,
        ds: Double,
        fc: String = "",
        fs: String = "",
        level: String = "-",
        levelIndex: Int,
        ra: Int = 0,
        rate: String = "d",
        songId: Int,
        title: String,
        type: String,
        dxScore: Int = 0,
        apBonusEnabled: Bool = false
    ) {
        self.achievements = achievements
        self.ds = ds
        self.fc = fc
        self.fs = fs
        self.level = level
        self.levelIndex = levelIndex
        self.ra = ra
        self.rate = rate
        self.songId = songId
        self.title = title
        self.type = type
        self.dxScore = dxScore
        self.apBonusEnabled = apBonusEnabled
    }

    var difficulty: DifficultyType {
        DifficultyType(rawValue: levelIndex) ?? .unknown
    }

    var isUtage: Bool {
        songId >= 100_000 || levelIndex >= 10 || type.lowercased() == "utage"
    }

    var isAllPerfect: Bool {
        fc == "ap" || fc == "app"
    }

    var computedRating: Int {
        guard !isUtage else { return 0 }
        return RatingCalculator.singleSongRating(
            ds: ds,
            achievement: achievements,
            isAP: isAllPerfect,
            apBonusEnabled: apBonusEnabled
        )
    }

    /// Re-projects a player's record onto the active server's chart constants and AP+1 rule.
    func projected(onto song: SongData, region: MaimaiConstants.ServerRegion) -> ProberRecord {
        let chartIdx = song.chartArrayIndex(forRecordLevelIndex: levelIndex)
        let targetDS: Double
        let targetLevel: String
        let targetTitle: String

        if region == .cn {
            targetDS = isUtage ? 0 : (ds > 0 ? ds : (chartIdx.flatMap { song.ds[safe: $0] } ?? 0))
            targetLevel = (level != "-" && !level.isEmpty) ? level : (chartIdx.flatMap { song.level[safe: $0] } ?? level)
            targetTitle = (!title.isEmpty && !title.hasPrefix("Song #")) ? title : (song.title.isEmpty ? title : song.title)
        } else {
            targetDS = isUtage ? 0 : (chartIdx.flatMap { song.ds[safe: $0] } ?? ds)
            targetLevel = chartIdx.flatMap { song.level[safe: $0] } ?? level
            targetTitle = song.title.isEmpty ? title : song.title
        }

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
            title: targetTitle,
            type: type,
            dxScore: dxScore,
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

extension ProberRecord: Codable {
    enum CodingKeys: String, CodingKey {
        case achievements, ds
        case fc, fs, level
        case levelIndex = "level_index"
        case ra, rate
        case songId = "song_id"
        case title, type
        case dxScore
        case dxScoreSnake = "dx_score"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        let achievements = try c.decode(Double.self, forKey: .achievements)
        let ds = try c.decodeIfPresent(Double.self, forKey: .ds) ?? 0
        let fc = try c.decodeIfPresent(String.self, forKey: .fc) ?? ""
        let fs = try c.decodeIfPresent(String.self, forKey: .fs) ?? ""
        let level = try c.decodeIfPresent(String.self, forKey: .level) ?? "-"
        let levelIndex = try c.decode(Int.self, forKey: .levelIndex)
        let ra = try c.decodeIfPresent(Int.self, forKey: .ra) ?? 0
        let rate = try c.decodeIfPresent(String.self, forKey: .rate) ?? "d"
        let songId = try c.decode(Int.self, forKey: .songId)
        let title = try c.decode(String.self, forKey: .title)
        let type = try c.decode(String.self, forKey: .type)
        let dx = try c.decodeIfPresent(Int.self, forKey: .dxScore)
            ?? c.decodeIfPresent(Int.self, forKey: .dxScoreSnake)
            ?? 0
        self.init(
            achievements: achievements,
            ds: ds,
            fc: fc,
            fs: fs,
            level: level,
            levelIndex: levelIndex,
            ra: ra,
            rate: rate,
            songId: songId,
            title: title,
            type: type,
            dxScore: dx,
            apBonusEnabled: false
        )
    }

    func encode(to encoder: Encoder) throws {
        var c = encoder.container(keyedBy: CodingKeys.self)
        try c.encode(achievements, forKey: .achievements)
        try c.encode(ds, forKey: .ds)
        try c.encode(fc, forKey: .fc)
        try c.encode(fs, forKey: .fs)
        try c.encode(level, forKey: .level)
        try c.encode(levelIndex, forKey: .levelIndex)
        try c.encode(ra, forKey: .ra)
        try c.encode(rate, forKey: .rate)
        try c.encode(songId, forKey: .songId)
        try c.encode(title, forKey: .title)
        try c.encode(type, forKey: .type)
        try c.encode(dxScore, forKey: .dxScore)
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
