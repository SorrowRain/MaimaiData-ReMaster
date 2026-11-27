import SwiftUI

enum MaimaiConstants {
    static let genreUtage = "宴会場"
    static let chartTypeDX = "DX"
    static let chartTypeSD = "SD"

    static let allGenres: [String] = [
        "流行&动漫",
        "niconico & VOCALOID",
        "东方Project",
        "其他游戏",
        "舞萌",
        "音击&中二节奏",
        "宴会場"
    ]

    static let allLevels: [String] = [
        "ALL", "1", "2", "3", "4", "5", "6", "7", "7+",
        "8", "8+", "9", "9+", "10", "10+", "11", "11+",
        "12", "12+", "13", "13+", "14", "14+", "15"
    ]

    struct CNVersionSpec {
        let name: String
        let asset: String
        let isClassicEra: Bool
    }

    struct JPVersionSpec {
        let name: String
        let asset: String
        let codePrefix: String
        let isClassicEra: Bool
        let isIntlAvailableByDefault: Bool

        init(
            name: String,
            asset: String,
            codePrefix: String,
            isClassicEra: Bool,
            isIntlAvailableByDefault: Bool = true
        ) {
            self.name = name
            self.asset = asset
            self.codePrefix = codePrefix
            self.isClassicEra = isClassicEra
            self.isIntlAvailableByDefault = isIntlAvailableByDefault
        }
    }

    /// Single source of truth for CN versions (ordered oldest -> newest).
    /// When a new CN major version ships, append ONE entry here.
    static let cnVersionRegistry: [CNVersionSpec] = [
        CNVersionSpec(name: "maimai", asset: "maimai", isClassicEra: true),
        CNVersionSpec(name: "maimai PLUS", asset: "maimai_plus", isClassicEra: true),
        CNVersionSpec(name: "maimai GreeN", asset: "maimai_green", isClassicEra: true),
        CNVersionSpec(name: "maimai GreeN PLUS", asset: "maimai_green_plus", isClassicEra: true),
        CNVersionSpec(name: "maimai ORANGE", asset: "maimai_orange", isClassicEra: true),
        CNVersionSpec(name: "maimai ORANGE PLUS", asset: "maimai_orange_plus", isClassicEra: true),
        CNVersionSpec(name: "maimai PiNK", asset: "maimai_pink", isClassicEra: true),
        CNVersionSpec(name: "maimai PiNK PLUS", asset: "maimai_pink_plus", isClassicEra: true),
        CNVersionSpec(name: "maimai MURASAKi", asset: "maimai_murasaki", isClassicEra: true),
        CNVersionSpec(name: "maimai MURASAKi PLUS", asset: "maimai_murasaki_plus", isClassicEra: true),
        CNVersionSpec(name: "maimai MiLK", asset: "maimai_milk", isClassicEra: true),
        CNVersionSpec(name: "maimai MiLK PLUS", asset: "maimai_milk_plus", isClassicEra: true),
        CNVersionSpec(name: "maimai FiNALE", asset: "maimai_finale", isClassicEra: true),
        CNVersionSpec(name: "舞萌DX", asset: "maimaidx_cn", isClassicEra: false),
        CNVersionSpec(name: "舞萌DX 2021", asset: "maimaidx_2021", isClassicEra: false),
        CNVersionSpec(name: "舞萌DX 2022", asset: "maimaidx_2022", isClassicEra: false),
        CNVersionSpec(name: "舞萌DX 2023", asset: "maimaidx_2023", isClassicEra: false),
        CNVersionSpec(name: "舞萌DX 2024", asset: "maimaidx_2024", isClassicEra: false),
        CNVersionSpec(name: "舞萌DX 2025", asset: "maimaidx_2025", isClassicEra: false),
        CNVersionSpec(name: "舞萌DX 2026", asset: "maimaidx_2026", isClassicEra: false)
    ]

    /// Single source of truth for SEGA JP/INTL arcade versions matching Zetaraku `data.json`.
    /// When a new arcade version ships, append ONE entry here (and add its drawable asset).
    static let jpVersionRegistry: [JPVersionSpec] = [
        JPVersionSpec(name: "maimai", asset: "maimai", codePrefix: "100", isClassicEra: true),
        JPVersionSpec(name: "maimai PLUS", asset: "maimai_plus", codePrefix: "110", isClassicEra: true),
        JPVersionSpec(name: "GreeN", asset: "maimai_green", codePrefix: "120", isClassicEra: true),
        JPVersionSpec(name: "GreeN PLUS", asset: "maimai_green_plus", codePrefix: "130", isClassicEra: true),
        JPVersionSpec(name: "ORANGE", asset: "maimai_orange", codePrefix: "140", isClassicEra: true),
        JPVersionSpec(name: "ORANGE PLUS", asset: "maimai_orange_plus", codePrefix: "150", isClassicEra: true),
        JPVersionSpec(name: "PiNK", asset: "maimai_pink", codePrefix: "160", isClassicEra: true),
        JPVersionSpec(name: "PiNK PLUS", asset: "maimai_pink_plus", codePrefix: "170", isClassicEra: true),
        JPVersionSpec(name: "MURASAKi", asset: "maimai_murasaki", codePrefix: "180", isClassicEra: true),
        JPVersionSpec(name: "MURASAKi PLUS", asset: "maimai_murasaki_plus", codePrefix: "185", isClassicEra: true),
        JPVersionSpec(name: "MiLK", asset: "maimai_milk", codePrefix: "190", isClassicEra: true),
        JPVersionSpec(name: "MiLK PLUS", asset: "maimai_milk_plus", codePrefix: "195", isClassicEra: true),
        JPVersionSpec(name: "FiNALE", asset: "maimai_finale", codePrefix: "199", isClassicEra: true),
        JPVersionSpec(name: "maimaiでらっくす", asset: "maimaidx", codePrefix: "200", isClassicEra: false),
        JPVersionSpec(name: "maimaiでらっくす PLUS", asset: "maimaidx_plus", codePrefix: "205", isClassicEra: false),
        JPVersionSpec(name: "Splash", asset: "maimaidx_splash", codePrefix: "210", isClassicEra: false),
        JPVersionSpec(name: "Splash PLUS", asset: "maimaidx_splash_plus", codePrefix: "215", isClassicEra: false),
        JPVersionSpec(name: "UNiVERSE", asset: "maimaidx_universe", codePrefix: "220", isClassicEra: false),
        JPVersionSpec(name: "UNiVERSE PLUS", asset: "maimaidx_universe_plus", codePrefix: "225", isClassicEra: false),
        JPVersionSpec(name: "FESTiVAL", asset: "maimaidx_festival", codePrefix: "230", isClassicEra: false),
        JPVersionSpec(name: "FESTiVAL PLUS", asset: "maimaidx_festival_plus", codePrefix: "235", isClassicEra: false),
        JPVersionSpec(name: "BUDDiES", asset: "maimaidx_buddies", codePrefix: "240", isClassicEra: false),
        JPVersionSpec(name: "BUDDiES PLUS", asset: "maimaidx_buddies_plus", codePrefix: "245", isClassicEra: false),
        JPVersionSpec(name: "PRiSM", asset: "maimaidx_prism", codePrefix: "250", isClassicEra: false),
        JPVersionSpec(name: "PRiSM PLUS", asset: "maimaidx_prism_plus", codePrefix: "255", isClassicEra: false),
        JPVersionSpec(name: "CiRCLE", asset: "maimaidx_circle", codePrefix: "260", isClassicEra: false),
        JPVersionSpec(name: "CiRCLE PLUS", asset: "maimaidx_circle_plus", codePrefix: "265", isClassicEra: false),
        JPVersionSpec(name: "MAGiCAL", asset: "maimaidx_magical", codePrefix: "270", isClassicEra: false, isIntlAvailableByDefault: false)
    ]

    static let latestCNVersion: CNVersionSpec = cnVersionRegistry.last!
    static let latestJPVersion: JPVersionSpec = jpVersionRegistry.last!

    static let cnVersions: [(name: String, asset: String)] = cnVersionRegistry.map { ($0.name, $0.asset) }
    static let jpVersions: [(name: String, asset: String)] = jpVersionRegistry.map { ($0.name, $0.asset) }
    static let overseasClassicVersions: Set<String> = Set(jpVersionRegistry.filter(\.isClassicEra).map(\.name))
    static let knownJPVersionNames: Set<String> = Set(jpVersionRegistry.map(\.name))

    private static let cnAssetByName: [String: String] = Dictionary(
        uniqueKeysWithValues: cnVersionRegistry.map { ($0.name, $0.asset) }
    )
    private static let jpAssetByName: [String: String] = Dictionary(
        uniqueKeysWithValues: jpVersionRegistry.map { ($0.name, $0.asset) }
    )

    private static let classicEraChips: [String] = [
        "maimai", "GreeN", "ORANGE", "PiNK", "MURASAKi", "MiLK", "FiNALE"
    ]

    /// Filter chips mirror grouped version chips for CN (derived from `cnVersionRegistry`).
    static let searchVersions: [String] = classicEraChips + cnVersionRegistry.filter { !$0.isClassicEra }.map(\.name)

    static func searchVersionChips(for region: ServerRegion) -> [String] {
        switch region {
        case .cn:
            return searchVersions
        case .intl:
            let dxNames = jpVersionRegistry
                .filter { !$0.isClassicEra && $0.isIntlAvailableByDefault }
                .map(\.name)
            return classicEraChips + dxNames
        case .jp:
            let dxNames = jpVersionRegistry
                .filter { !$0.isClassicEra }
                .map(\.name)
            return classicEraChips + dxNames
        }
    }

    /// Parses level string (`"14+"` -> `14.5`, `"13"` -> `13.0`) for consistent sorting.
    static func levelSortOrder(_ level: String) -> Double {
        let cleaned = level.replacingOccurrences(of: "?", with: "").trimmingCharacters(in: .whitespaces)
        if cleaned.hasSuffix("+") {
            return (Double(cleaned.dropLast()) ?? 0) + 0.5
        }
        return Double(cleaned) ?? 0
    }

    static func expandVersionFilter(_ selected: [String], region: ServerRegion = .cn) -> Set<String> {
        var result = Set<String>()
        switch region {
        case .cn:
            for item in selected {
                if item == "maimai" {
                    result.insert("maimai")
                    result.insert("maimai PLUS")
                } else if item == "FiNALE" {
                    result.insert("maimai FiNALE")
                } else if !item.hasPrefix("舞萌") && !item.contains("FiNALE") {
                    result.insert("maimai \(item) PLUS")
                    result.insert("maimai \(item)")
                } else {
                    result.insert(item)
                }
            }
        case .intl, .jp:
            let groupedClassic: Set<String> = ["GreeN", "ORANGE", "PiNK", "MURASAKi", "MiLK"]
            for item in selected {
                if item == "maimai" {
                    result.insert("maimai")
                    result.insert("maimai PLUS")
                } else if groupedClassic.contains(item) {
                    result.insert(item)
                    result.insert("\(item) PLUS")
                } else {
                    result.insert(item)
                }
            }
        }
        return result
    }

    static func genreColors(for genre: String) -> (bg: Color, stroke: Color) {
        switch genre {
        case "流行&动漫":
            return AppTheme.Palette.genrePop
        case "niconico & VOCALOID":
            return AppTheme.Palette.genreNico
        case "东方Project":
            return AppTheme.Palette.genreTouhou
        case "其他游戏":
            return AppTheme.Palette.genreGame
        case "舞萌":
            return AppTheme.Palette.genreMaimai
        case "宴会場":
            return AppTheme.Palette.genreUtage
        default:
            return AppTheme.Palette.genreOther
        }
    }

    /// Fill for the big genre card on the song detail page. Locked genre colors stay
    /// untouched; only 其他游戏 uses a lighter green so white text reads comfortably.
    static func genreCardFill(for genre: String) -> Color {
        genre == "其他游戏" ? AppTheme.Palette.genreGameCardFill : genreColors(for: genre).bg
    }

    static func genreTextColor(for genre: String) -> Color {
        let light: Color
        switch genre {
        case "流行&动漫": light = AppTheme.Palette.genrePopText
        case "niconico & VOCALOID": light = AppTheme.Palette.genreNicoText
        case "东方Project": light = AppTheme.Palette.genreTouhouText
        case "其他游戏": light = AppTheme.Palette.genreGameText
        case "舞萌": light = AppTheme.Palette.genreMaimaiText
        case "宴会場": light = AppTheme.Palette.genreUtageText
        default: light = AppTheme.Palette.genreOtherText
        }
        // Dark mode: same hue as the genre color, brightness >= 0.85.
        return .adaptive(light: light, dark: genreColors(for: genre).bg.brightened(toAtLeast: 1.0, maxSaturation: 0.5))
    }

    static func jpVersionAsset(for versionCode: String) -> String? {
        jpVersionRegistry.first { versionCode.hasPrefix($0.codePrefix) }?.asset
    }

    static func jpVersionFallbackTitle(for versionCode: String) -> String {
        let trimmed = versionCode.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "暂无数据" }
        if let matched = jpVersionRegistry.first(where: { trimmed.hasPrefix($0.codePrefix) }) {
            return matched.isClassicEra ? matched.name : "maimai でらっくす \(matched.name)"
        }
        return "Ver. \(trimmed)"
    }

    static func cnVersionAsset(for fromName: String) -> String? {
        cnAssetByName[fromName]
    }

    static func jpVersionAssetByName(_ fromName: String) -> String? {
        jpAssetByName[fromName]
    }

    enum ServerRegion: String, Codable, CaseIterable, Identifiable {
        case cn
        case intl
        case jp

        var id: String { rawValue }

        var displayName: String {
            switch self {
            case .cn: return "国服（舞萌DX）"
            case .intl: return "国际服（International）"
            case .jp: return "日服（日本版）"
            }
        }

        var shortName: String {
            switch self {
            case .cn: return "国服"
            case .intl: return "国际服"
            case .jp: return "日服"
            }
        }

        /// Starting from `CiRCLE` in JP / International, `AP` and `AP+` grant `+1` single-song Rating.
        var usesAPBonus: Bool {
            switch self {
            case .cn: return false
            case .intl, .jp: return true
            }
        }
    }

    static func shortGenreDisplayName(for genre: String) -> String {
        genre == "niconico & VOCALOID" ? "VOCALOID" : genre
    }

    enum RankIconTheme: String {
        case prism
        case circle
    }

    static let validRankKeys: Set<String> = [
        "d", "c", "b", "bb", "bbb", "a", "aa", "aaa", "s", "sp", "ss", "ssp", "sss", "sssp"
    ]

    static func rankIconTheme(for region: ServerRegion) -> RankIconTheme {
        switch region {
        case .cn:
            return .prism
        case .intl, .jp:
            return .circle
        }
    }

    static func rankAssetName(for rate: String, region: ServerRegion) -> String {
        let lower = rate.lowercased()
        if lower == "ap" || lower == "app" {
            return "mmd_player_rtsong_\(lower)"
        }
        let safeRate = validRankKeys.contains(lower) ? lower : "d"
        return "mmd_rank_\(rankIconTheme(for: region).rawValue)_\(safeRate)"
    }

    /// Single source of truth for chart constant (`ds`) range & Level-15 decimal locking across filter/calculator UIs.
    static let minConstantDS: Double = 1.0
    static let maxConstantDS: Double = 15.0
    static let minLevelInteger: Int = 1
    static let maxLevelInteger: Int = 15

    static func clampExactDS(_ ds: Double) -> Double {
        min(max(minConstantDS, ds), maxConstantDS)
    }

    static func splitExactDS(_ ds: Double) -> (intPart: Int, decPart: Int) {
        let tenths = Int((clampExactDS(ds) * 10.0).rounded())
        let intPart = min(max(minLevelInteger, tenths / 10), maxLevelInteger)
        let decPart = intPart >= maxLevelInteger ? 0 : min(max(0, tenths % 10), 9)
        return (intPart, decPart)
    }

    static func effectiveDecimalDigit(forInt intPart: Int, preferred: Int) -> Int {
        intPart >= maxLevelInteger ? 0 : min(max(0, preferred), 9)
    }

    static func effectiveFilterDecimalIndex(levelIndex: Int, baseIntLevel: Int, preferredDecIdx: Int) -> Int {
        if levelIndex == 0 { return 0 }
        if baseIntLevel >= maxLevelInteger { return preferredDecIdx > 0 ? 1 : 0 }
        return min(max(0, preferredDecIdx), 10)
    }

    static func composeExactDS(intPart: Int, decPart: Int) -> Double {
        let clampedInt = min(max(minLevelInteger, intPart), maxLevelInteger)
        let effectiveDec = effectiveDecimalDigit(forInt: clampedInt, preferred: decPart)
        return clampExactDS(Double(clampedInt) + Double(effectiveDec) / 10.0)
    }

    static func baseIntegerLevel(forLevelIndex index: Int) -> Int {
        guard index > 0, allLevels.indices.contains(index) else { return minLevelInteger }
        let raw = allLevels[index].replacingOccurrences(of: "+", with: "")
        return Int(raw) ?? minLevelInteger
    }

    /// Resolves the version logo asset and fallback label for the active game server.
    static func activeServerVersionDisplay(
        for info: SongData.BasicInfo,
        region: ServerRegion = .cn
    ) -> (asset: String?, fallback: String) {
        switch region {
        case .cn:
            let asset = cnVersionAsset(for: info.from)
            let fallback = info.from.isEmpty ? "暂无数据" : info.from
            return (asset, fallback)
        case .jp, .intl:
            let asset = jpVersionAssetByName(info.from) ?? jpVersionAsset(for: info.version)
            let fallback = !info.from.isEmpty ? info.from : jpVersionFallbackTitle(for: info.version)
            return (asset, fallback)
        }
    }
}
