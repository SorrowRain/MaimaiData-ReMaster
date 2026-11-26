import Foundation

enum LXNSConfiguration {
    static let clientId = "9e67172c-d7b9-4bcf-b8a9-185b8afe1b63"
    static let redirectURI = "maimaidata://oauth/lxns"
    static let callbackScheme = "maimaidata"
    static let scopes = "read_player write_player openid profile"
    static let authorizeURLString = "https://maimai.lxns.net/oauth/authorize"
    static let apiBase = "https://maimai.lxns.net/api/v0"
}

// MARK: - Authenticated & OAuth Payloads

struct LXNSAPIEnvelope<T: Decodable>: Decodable {
    let success: Bool
    let code: Int
    let message: String?
    let data: T?
}

struct LXNSOAuthTokenResponse: Equatable {
    let accessToken: String
    let tokenType: String
    let expiresIn: Int
    let refreshToken: String?
    let scope: String?
    let idToken: String?

    init(
        accessToken: String,
        tokenType: String = "Bearer",
        expiresIn: Int = 3600,
        refreshToken: String? = nil,
        scope: String? = nil,
        idToken: String? = nil
    ) {
        self.accessToken = accessToken
        self.tokenType = tokenType
        self.expiresIn = expiresIn
        self.refreshToken = refreshToken
        self.scope = scope
        self.idToken = idToken
    }
}

extension LXNSOAuthTokenResponse: Codable {
    enum CodingKeys: String, CodingKey {
        case accessToken = "access_token"
        case tokenType = "token_type"
        case expiresIn = "expires_in"
        case refreshToken = "refresh_token"
        case scope
        case idToken = "id_token"
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        self.init(
            accessToken: try c.decode(String.self, forKey: .accessToken),
            tokenType: try c.decodeIfPresent(String.self, forKey: .tokenType) ?? "Bearer",
            expiresIn: try c.decodeIfPresent(Int.self, forKey: .expiresIn) ?? 3600,
            refreshToken: try c.decodeIfPresent(String.self, forKey: .refreshToken),
            scope: try c.decodeIfPresent(String.self, forKey: .scope),
            idToken: try c.decodeIfPresent(String.self, forKey: .idToken)
        )
    }
}

struct LXNSOAuthErrorResponse: Decodable {
    let error: String
    let errorDescription: String?

    enum CodingKeys: String, CodingKey {
        case error
        case errorDescription = "error_description"
    }
}

struct LXNSUserInfo: Codable, Equatable {
    let sub: String
    let name: String?
    let preferredUsername: String?

    enum CodingKeys: String, CodingKey {
        case sub
        case name
        case preferredUsername = "preferred_username"
    }

    var displayUsername: String {
        if let preferredUsername, !preferredUsername.isEmpty { return preferredUsername }
        if let name, !name.isEmpty { return name }
        return ""
    }
}

struct LXNSPlayer: Codable, Equatable {
    let name: String
    let rating: Int?
    let friendCode: Int64?
    let courseRank: Int?
    let classRank: Int?
    let uploadTime: String?

    enum CodingKeys: String, CodingKey {
        case name
        case rating
        case friendCode = "friend_code"
        case courseRank = "course_rank"
        case classRank = "class_rank"
        case uploadTime = "upload_time"
    }
}

struct LXNSScore: Codable, Equatable {
    let id: Int
    let songName: String?
    let level: String?
    let levelIndex: Int
    let achievements: Double
    let fc: String?
    let fs: String?
    let dxScore: Int?
    let dxRating: Double?
    let rate: String?
    let type: String

    enum CodingKeys: String, CodingKey {
        case id
        case songName = "song_name"
        case level
        case levelIndex = "level_index"
        case achievements
        case fc, fs
        case dxScore = "dx_score"
        case dxRating = "dx_rating"
        case rate, type
    }
}

// MARK: - Public Catalog, Alias & Plate Payloads

struct LXNSSongListResponse: Decodable {
    let songs: [LXNSSong]
    let versions: [LXNSVersion]
}

struct LXNSVersion: Decodable, Equatable {
    let id: Int
    let title: String
    let version: Int
}

struct LXNSSong: Decodable {
    let id: Int
    let title: String
    let artist: String
    let genre: String
    let bpm: Int
    let version: Int
    let releaseDate: String?
    let locked: Bool?
    let disabled: Bool?
    let difficulties: LXNSSongDifficulties

    enum CodingKeys: String, CodingKey {
        case id, title, artist, genre, bpm, version
        case releaseDate = "release_date"
        case locked, disabled, difficulties
    }
}

struct LXNSSongDifficulties: Decodable {
    let standard: [LXNSSongDifficulty]
    let dx: [LXNSSongDifficulty]
    let utage: [LXNSSongDifficultyUtage]?
}

struct LXNSSongDifficulty: Decodable {
    let type: String
    let difficulty: Int
    let level: String
    let levelValue: Double
    let noteDesigner: String?
    let version: Int?
    let notes: LXNSNotes?

    enum CodingKeys: String, CodingKey {
        case type, difficulty, level
        case levelValue = "level_value"
        case noteDesigner = "note_designer"
        case version, notes
    }
}

struct LXNSNotes: Decodable, Equatable {
    let total: Int?
    let tap: Int?
    let hold: Int?
    let slide: Int?
    let touch: Int?
    let `break`: Int?

    var sdArray: [Int] {
        [tap ?? 0, hold ?? 0, slide ?? 0, `break` ?? 0]
    }

    var dxArray: [Int] {
        [tap ?? 0, hold ?? 0, slide ?? 0, touch ?? 0, `break` ?? 0]
    }
}

enum LXNSUtageNotes: Decodable, Equatable {
    case single(LXNSNotes)
    case buddy(left: LXNSNotes, right: LXNSNotes)

    private enum BuddyCodingKeys: String, CodingKey {
        case left, right
    }

    init(from decoder: Decoder) throws {
        if let buddyContainer = try? decoder.container(keyedBy: BuddyCodingKeys.self),
           buddyContainer.contains(.left),
           let left = try? buddyContainer.decode(LXNSNotes.self, forKey: .left),
           let right = try? buddyContainer.decode(LXNSNotes.self, forKey: .right) {
            self = .buddy(left: left, right: right)
            return
        }
        let singleNotes = try LXNSNotes(from: decoder)
        self = .single(singleNotes)
    }
}

struct LXNSSongDifficultyUtage: Decodable {
    let type: String
    let difficulty: Int
    let level: String
    let levelValue: Double?
    let noteDesigner: String?
    let version: Int?
    let kanji: String?
    let description: String?
    let isBuddy: Bool?
    let notes: LXNSUtageNotes?

    enum CodingKeys: String, CodingKey {
        case type, difficulty, level
        case levelValue = "level_value"
        case noteDesigner = "note_designer"
        case version, kanji, description
        case isBuddy = "is_buddy"
        case notes
    }
}

struct LXNSAliasListResponse: Decodable {
    let aliases: [LXNSAliasEntry]
}

struct LXNSAliasEntry: Decodable {
    let songId: Int
    let aliases: [String]

    enum CodingKeys: String, CodingKey {
        case songId = "song_id"
        case aliases
    }
}

struct LXNSPlateListResponse: Decodable {
    let plates: [LXNSPlate]
}

struct LXNSPlate: Decodable {
    let id: Int
    let name: String
    let description: String?
    let genre: String?
    let required: [LXNSPlateRequirement]?
}

struct LXNSPlateRequirement: Decodable {
    let difficulties: [Int]?
    let rate: String?
    let fc: String?
    let fs: String?
    let songs: [LXNSPlateRequiredSong]?
}

struct LXNSPlateRequiredSong: Decodable {
    let id: Int
    let title: String?
    let type: String
}

// MARK: - Local Cached CN Plate Rule Catalog

struct CNPlateCatalog: Codable, Equatable {
    let updatedAt: String
    let groups: [CNPlateRuleGroup]

    enum CodingKeys: String, CodingKey {
        case updatedAt = "updated_at"
        case groups
    }
}

struct CNPlateRuleGroup: Codable, Identifiable, Equatable, Hashable {
    let id: String
    let title: String
    let versionAsset: String
    let badges: [String]
    let basicToMasterSongIds: [Int]
    let remasterSongIds: [Int]

    enum CodingKeys: String, CodingKey {
        case id, title
        case versionAsset = "version_asset"
        case badges
        case basicToMasterSongIds = "basic_to_master_song_ids"
        case remasterSongIds = "remaster_song_ids"
    }

    var resolvedVersionAsset: String {
        id == "真" ? "maimai" : versionAsset
    }
}

/// Region-agnostic plate / version progress scope shared across CN, INTL, and JP in `VersionCheckView`.
struct PlateCheckScope: Identifiable, Equatable, Hashable {
    enum Rule: Equatable, Hashable {
        case explicitIds(basicToMaster: Set<Int>, remaster: Set<Int>)
        case versions(
            includedVersions: Set<String>,
            excludedIds: Set<Int> = [],
            excludedStandardTitles: Set<String> = [],
            sdRemasterIds: Set<Int>? = nil,
            sdRemasterFallbackTitles: Set<String> = []
        )
    }

    let id: String
    let title: String
    let versionAsset: String
    let rule: Rule

    static let fallback = PlateCheckScope(
        id: "default",
        title: "真 · maimai ~ PLUS",
        versionAsset: "maimai",
        rule: .explicitIds(basicToMaster: [], remaster: [])
    )

    var supportsRemaster: Bool {
        switch rule {
        case .explicitIds(_, let rem):
            return !rem.isEmpty
        case .versions(_, _, _, let sdRem, _):
            return sdRem != nil
        }
    }

    func contains(_ song: SongData, difficulty: DifficultyType) -> Bool {
        guard difficulty.rawValue >= 0,
              difficulty.rawValue <= 4,
              !song.isUtage,
              song.isDisabled != true,
              song.charts.count > difficulty.rawValue else {
            return false
        }
        switch rule {
        case .explicitIds(let b2m, let rem):
            let target = (difficulty == .remaster) ? rem : b2m
            return song.numericId > 0 && target.contains(song.numericId)
        case .versions(let versions, let excludedIds, let excludedStdTitles, let sdRemasterIds, let sdRemasterFallbackTitles):
            if song.numericId > 0 && excludedIds.contains(song.numericId) {
                return false
            }
            if !song.isDX && excludedStdTitles.contains(song.title) {
                return false
            }
            guard versions.contains(song.basicInfo.from) else {
                return false
            }
            if difficulty == .remaster {
                guard let sdRemasterIds else { return false }
                if song.numericId > 0 {
                    return sdRemasterIds.contains(song.numericId)
                }
                return !song.isDX && sdRemasterFallbackTitles.contains(song.title)
            }
            return true
        }
    }
}
