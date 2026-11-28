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
    let clearCount: Int
    let totalCount: Int

    init(records: [ProberRecord], totalCount: Int) {
        self.sssCount = records.filter { $0.achievements >= 100 }.count
        self.fcCount = records.filter { !$0.fc.isEmpty }.count
        self.apCount = records.filter { $0.fc == "ap" || $0.fc == "app" }.count
        self.fsdCount = records.filter { $0.fs == "fsd" || $0.fs == "fsdp" }.count
        self.clearCount = records.filter { $0.achievements >= 80 }.count
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

struct AppReleaseInfo: Identifiable, Equatable {
    var id: String { version }
    let version: String
    let notes: String
    let htmlURL: String

    private enum AtomError: Error {
        case invalidXML
        case noStableRelease
    }

    static func fromAtom(_ data: Data) throws -> AppReleaseInfo {
        let otherEncoding = #"\A\uFEFF?<\?xml\s+[^?]*\bencoding\s*=\s*(['"])(?!UTF-8\1)"#
        guard let text = String(data: data, encoding: .utf8),
              !text.utf8.contains(0), !text.contains("<!DOCTYPE"),
              text.range(of: otherEncoding, options: [.regularExpression, .caseInsensitive]) == nil else {
            throw AtomError.invalidXML
        }

        let delegate = ReleaseAtomParser()
        let parser = XMLParser(data: data)
        parser.delegate = delegate
        parser.shouldProcessNamespaces = true
        parser.shouldResolveExternalEntities = false
        parser.externalEntityResolvingPolicy = .never
        guard parser.parse(), parser.parserError == nil else { throw AtomError.invalidXML }
        guard let latest = delegate.latest else { throw AtomError.noStableRelease }
        return latest
    }

    func isNewer(than currentVersion: String) -> Bool {
        let cleanedCurrent = currentVersion
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(of: #"^[vV]+"#, with: "", options: .regularExpression)
        return VersionString.isNewer(version, than: cleanedCurrent)
    }
}

private final class ReleaseAtomParser: NSObject, XMLParserDelegate {
    private static let namespace = "http://www.w3.org/2005/Atom"
    private static let linkPrefix = "https://github.com/SorrowRain/MaimaiData-ReMaster/releases/tag/v"
    private static let htmlTags = try! NSRegularExpression(
        pattern: #"(?is)<!--.*?-->|<(/?)([a-z][a-z0-9:-]*)(?:[^"'<>]|"[^"]*"|'[^']*')*>"#
    )
    private static let htmlEntities = try! NSRegularExpression(
        pattern: #"&(amp|lt|gt|quot|apos|nbsp|#[0-9]+|#[xX][0-9a-fA-F]+);"#
    )

    private var depth = 0
    private var inEntry = false
    private var readingContent = false
    private var link: (version: String, url: String)?
    private var content: (isHTML: Bool, text: String)?
    private(set) var latest: AppReleaseInfo?

    func parser(
        _ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
        qualifiedName: String?, attributes: [String: String]
    ) {
        depth += 1
        if depth == 1, elementName != "feed" || namespaceURI != Self.namespace {
            parser.abortParsing()
            return
        }
        guard namespaceURI == Self.namespace else { return }
        switch (depth, elementName) {
        case (2, "entry"):
            inEntry = true
            link = nil
            content = nil
        case (3, "link") where inEntry && link == nil && attributes["rel"] == "alternate":
            guard let url = attributes["href"], url.hasPrefix(Self.linkPrefix) else { return }
            let version = String(url.dropFirst(Self.linkPrefix.count))
            guard version.range(of: #"\A[0-9]+\.[0-9]+\.[0-9]+\z"#, options: .regularExpression) != nil,
                  version.split(separator: ".").allSatisfy({ Int32($0) != nil }) else { return }
            link = (version, url)
        case (3, "content") where inEntry && content == nil:
            let type = attributes["type"] ?? "text"
            if ["html", "text", "text/plain"].contains(type) {
                content = (type == "html", "")
                readingContent = true
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if depth == 3 && readingContent { content?.text += string }
    }

    func parser(_ parser: XMLParser, foundCDATA data: Data) {
        if let string = String(data: data, encoding: .utf8) {
            self.parser(parser, foundCharacters: string)
        }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName: String?) {
        defer { depth -= 1 }
        if depth == 3 { readingContent = false }
        guard depth == 2, inEntry else { return }
        inEntry = false
        guard let link, latest.map({ VersionString.isNewer(link.version, than: $0.version) }) ?? true else { return }
        let notes = (content.map { $0.isHTML ? Self.plainText($0.text) : $0.text } ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let scalars = notes.unicodeScalars
        let cappedNotes = scalars.count > 420 ? String(scalars.prefix(420)) + "…" : notes
        latest = AppReleaseInfo(version: link.version, notes: cappedNotes, htmlURL: link.url)
    }

    private static func plainText(_ html: String) -> String {
        let tightened = html.replacingOccurrences(
            of: #"(?i)(<li\b[^>]*>)\s*(?:<p\b[^>]*>\s*)?"#,
            with: "$1",
            options: .regularExpression
        )
        let text = replacingMatches(in: tightened, of: htmlTags) { source, match in
            let name = match.range(at: 2)
            guard name.location != NSNotFound else { return "" }
            switch source.substring(with: name).lowercased() {
            case "li": return match.range(at: 1).length == 0 ? "\n- " : "\n"
            case "p", "br", "h1", "h2", "h3", "h4", "h5", "h6", "ul", "ol", "div", "blockquote", "pre", "hr":
                return "\n"
            default: return ""
            }
        }
        let decoded = replacingMatches(in: text, of: htmlEntities) { source, match in
            let entity = source.substring(with: match.range(at: 1))
            switch entity {
            case "amp": return "&"
            case "lt": return "<"
            case "gt": return ">"
            case "quot": return "\""
            case "apos": return "'"
            case "nbsp": return " "
            default:
                let digits = entity.dropFirst()
                let hex = digits.first == "x" || digits.first == "X"
                if let value = UInt32(hex ? digits.dropFirst() : digits, radix: hex ? 16 : 10),
                   let scalar = UnicodeScalar(value) {
                    return String(scalar)
                }
                return source.substring(with: match.range)
            }
        }
        return decoded.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespacesAndNewlines) }
            .filter { !$0.isEmpty }
            .joined(separator: "\n")
    }

    private static func replacingMatches(
        in text: String, of pattern: NSRegularExpression,
        with replacement: (NSString, NSTextCheckingResult) -> String
    ) -> String {
        let source = text as NSString
        let range = NSRange(location: 0, length: source.length)
        var output = ""
        var offset = 0
        for match in pattern.matches(in: text, range: range) {
            output += source.substring(with: NSRange(location: offset, length: match.range.location - offset))
            output += replacement(source, match)
            offset = NSMaxRange(match.range)
        }
        return output + source.substring(from: offset)
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
