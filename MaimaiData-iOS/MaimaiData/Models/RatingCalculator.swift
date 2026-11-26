import Foundation

enum RatingCalculator {
    /// Exact match of ConvertUtils.achievementToRating(levelInt, achiInt)
    /// - Parameters:
    ///   - levelTenths: e.g. 14.8 -> 148
    ///   - achievementTenThousandths: e.g. 100.5000 -> 1005000
    static func achievementToRating(levelTenths: Int, achievementTenThousandths achi: Int) -> Int {
        let multiplier: Double
        switch achi {
        case 1_005_000...: multiplier = 22.4
        case 1_004_999: multiplier = 22.2
        case 1_000_000...1_004_998: multiplier = 21.6
        case 999_999: multiplier = 21.4
        case 995_000...999_998: multiplier = 21.1
        case 990_000...994_999: multiplier = 20.8
        case 989_999: multiplier = 20.6
        case 980_000...989_998: multiplier = 20.3
        case 970_000...979_999: multiplier = 20.0
        case 969_999: multiplier = 17.6
        case 940_000...969_998: multiplier = 16.8
        case 900_000...939_999: multiplier = 15.2
        case 800_000...899_999: multiplier = 13.6
        case 799_999: multiplier = 12.8
        case 750_000...799_998: multiplier = 12.0
        case 700_000...749_999: multiplier = 11.2
        case 600_000...699_999: multiplier = 9.6
        case 500_000...599_999: multiplier = 8.0
        case 400_000...499_999: multiplier = 6.4
        case 300_000...399_999: multiplier = 4.8
        case 200_000...299_999: multiplier = 3.2
        case 100_000...199_999: multiplier = 1.6
        default: multiplier = 0.0
        }
        let capped = min(achi, 1_005_000)
        return Int(Double(capped) * Double(levelTenths) * multiplier / 10_000_000.0)
    }

    static func singleSongRating(
        ds: Double,
        achievement: Double,
        isAP: Bool = false,
        apBonusEnabled: Bool = false
    ) -> Int {
        let base = achievementToRating(
            levelTenths: Int((ds * 10).rounded()),
            achievementTenThousandths: Int((achievement * 10000).rounded())
        )
        let bonus = (apBonusEnabled && isAP && ds > 0) ? 1 : 0
        return base + bonus
    }

    struct TargetRow: Identifiable {
        let id = UUID()
        let level: Double
        let achievementText: String
        let singleRating: Int
        let totalRating: Int
    }

    static func reverseCalculate(targetTotalRating: Int) -> [TargetRow] {
        guard targetTotalRating > 0 else { return [] }
        let perSong = (targetTotalRating + 49) / 50
        var minLv = 0
        for i in 10...150 {
            if perSong <= achievementToRating(levelTenths: i, achievementTenThousandths: 1_005_000) {
                minLv = i
                break
            }
        }
        guard minLv > 0 else { return [] }
        var bestByAchi: [Int: Int] = [:]
        for lv in stride(from: 150, through: minLv, by: -1) {
            let achi = reachableAchievement(levelTenths: lv, targetSingleRating: perSong)
            if [800_000, 900_000, 940_000].contains(achi) || (970_000...1_010_000).contains(achi) {
                bestByAchi[achi] = lv
            }
        }
        return bestByAchi.keys.sorted(by: <).compactMap { achi in
            guard let lv = bestByAchi[achi] else { return nil }
            let r = achievementToRating(levelTenths: lv, achievementTenThousandths: achi)
            return TargetRow(
                level: Double(lv) / 10.0,
                achievementText: String(format: "%.4f%%", Double(achi) / 10000.0),
                singleRating: r,
                totalRating: r * 50
            )
        }
    }

    private static func reachableAchievement(levelTenths: Int, targetSingleRating: Int) -> Int {
        if achievementToRating(levelTenths: levelTenths, achievementTenThousandths: 1_005_000) < targetSingleRating {
            return 1_010_001
        }
        var maxAchi = 1_010_000
        var minAchi = 0
        for _ in 0...20 where maxAchi - minAchi >= 2 {
            let mid = (maxAchi + minAchi) / 2
            if achievementToRating(levelTenths: levelTenths, achievementTenThousandths: mid) < targetSingleRating {
                minAchi = mid
            } else {
                maxAchi = mid
            }
        }
        return maxAchi
    }

    // MARK: - Per-Chart Rating Tier Table (with SSS Intra-Tier +1 Thresholds & B50 Net Gain)

    struct TierRow: Identifiable, Equatable {
        var id: String { "\(rate)_\(achiTenThousandths)" }
        let rate: String
        let achiTenThousandths: Int
        let singleRating: Int
        let isCurrentTier: Bool
        let isPassed: Bool
        /// Net increase to player's total B50 rating (`nil` when already reached or when B50 column is hidden).
        let b50Gain: Int?

        var isAPRow: Bool { rate == "ap" }

        var achievementText: String {
            String(format: "%.4f%%", Double(achiTenThousandths) / 10_000.0)
        }

        func badgeAssetName(for region: MaimaiConstants.ServerRegion) -> String {
            MaimaiConstants.rankAssetName(for: rate, region: region)
        }
    }

    /// Builds the descending achievement tier table (`AP` -> `SSS+` -> intra-`SSS` +1 jump lines -> `SSS` -> ... -> `AAA`).
    static func buildTierRows(
        ds: Double,
        currentAchievement: Double? = nil,
        currentIsAP: Bool = false,
        b50BaselineRating: Int? = nil,
        includeAPBonusRow: Bool = false
    ) -> [TierRow] {
        let lv = Int((ds * 10).rounded())
        guard lv > 0 else { return [] }

        var specs: [(rate: String, achi: Int)] = []
        if includeAPBonusRow {
            specs.append(("ap", 1_005_000))
        }
        specs.append(("sssp", 1_005_000))

        // Compute exact +1 Ra integer jump thresholds strictly inside SSS (100.0001% ... 100.4998%)
        let baseSSSRa = achievementToRating(levelTenths: lv, achievementTenThousandths: 1_000_000)
        let maxIntraSSSRa = achievementToRating(levelTenths: lv, achievementTenThousandths: 1_004_998)
        if maxIntraSSSRa > baseSSSRa {
            for targetRa in stride(from: maxIntraSSSRa, through: baseSSSRa + 1, by: -1) {
                if let threshold = minimumSSSThreshold(levelTenths: lv, targetRating: targetRa) {
                    specs.append(("sss", threshold))
                }
            }
        }

        let tailSpecs: [(rate: String, achi: Int)] = [
            ("sss", 1_000_000),
            ("ssp", 995_000),
            ("ss", 990_000),
            ("sp", 980_000),
            ("s", 970_000),
            ("aaa", 940_000)
        ]
        specs.append(contentsOf: tailSpecs)

        let currentAchiInt = currentAchievement.map { Int(($0 * 10_000).rounded()) }
        let matchedCurrentIndex: Int? = {
            guard let cur = currentAchiInt else { return nil }
            return specs.firstIndex { spec in
                if spec.rate == "ap" {
                    return currentIsAP && cur >= spec.achi
                }
                return cur >= spec.achi
            }
        }()

        return specs.enumerated().map { idx, spec in
            let baseRa = achievementToRating(levelTenths: lv, achievementTenThousandths: spec.achi)
            let ra = spec.rate == "ap" ? (baseRa + 1) : baseRa
            let isCurrent = (matchedCurrentIndex == idx)
            let isPassed: Bool = {
                guard let matchedCurrentIndex else { return false }
                return idx > matchedCurrentIndex
            }()

            let gain: Int? = {
                guard let baseline = b50BaselineRating else { return nil }
                if let cur = currentAchiInt, spec.achi <= cur, (spec.rate != "ap" || currentIsAP) {
                    return 0
                }
                return max(0, ra - baseline)
            }()

            return TierRow(
                rate: spec.rate,
                achiTenThousandths: spec.achi,
                singleRating: ra,
                isCurrentTier: isCurrent,
                isPassed: isPassed,
                b50Gain: gain
            )
        }
    }

    private static func minimumSSSThreshold(levelTenths: Int, targetRating: Int) -> Int? {
        var low = 1_000_001
        var high = 1_004_998
        guard achievementToRating(levelTenths: levelTenths, achievementTenThousandths: high) >= targetRating else {
            return nil
        }
        while low < high {
            let mid = (low + high) / 2
            if achievementToRating(levelTenths: levelTenths, achievementTenThousandths: mid) >= targetRating {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }

    // MARK: - FiNALE (Old Cabinet) -> DX Achievement Conversion

    struct FinaleNoteCounts: Equatable {
        var tapPerfect = 0, tapGreat = 0, tapGood = 0, tapMiss = 0
        var holdPerfect = 0, holdGreat = 0, holdGood = 0, holdMiss = 0
        var slidePerfect = 0, slideGreat = 0, slideGood = 0, slideMiss = 0
        var breakPerfect = 0, breakGreat = 0, breakGood = 0, breakMiss = 0
        var breakScore = 0
    }

    enum FinaleConversionError: LocalizedError, Equatable {
        case missingNotesOrBreak
        case invalidBreakScore

        var errorDescription: String? {
            switch self {
            case .missingNotesOrBreak:
                return "请填写有效物量（至少需要包含 Break 数量）"
            case .invalidBreakScore:
                return "绝赞数据错误，请重新填写"
            }
        }
    }

    static func convertFinaleToDx(_ c: FinaleNoteCounts) throws -> String {
        let tapCount = c.tapPerfect + c.tapGreat + c.tapGood + c.tapMiss
        let holdCount = c.holdPerfect + c.holdGreat + c.holdGood + c.holdMiss
        let slideCount = c.slidePerfect + c.slideGreat + c.slideGood + c.slideMiss
        let breakCount = c.breakPerfect + c.breakGreat + c.breakGood + c.breakMiss

        let dxTotal = tapCount * 10 + holdCount * 20 + slideCount * 30 + breakCount * 50
        guard dxTotal > 0, breakCount > 0 else {
            throw FinaleConversionError.missingNotesOrBreak
        }

        let dxPlayMax = c.tapPerfect * 10 + c.tapGreat * 8 + c.tapGood * 5 +
            c.holdPerfect * 20 + c.holdGreat * 16 + c.holdGood * 10 +
            c.slidePerfect * 30 + c.slideGreat * 24 + c.slideGood * 15 +
            c.breakPerfect * 50 + c.breakGreat * 40 + c.breakGood * 20

        let dxPlayMin = c.tapPerfect * 10 + c.tapGreat * 8 + c.tapGood * 5 +
            c.holdPerfect * 20 + c.holdGreat * 16 + c.holdGood * 10 +
            c.slidePerfect * 30 + c.slideGreat * 24 + c.slideGood * 15 +
            c.breakPerfect * 50 + c.breakGreat * 25 + c.breakGood * 20

        if c.breakScore == 0 {
            let maxScore = Double(dxPlayMax) / Double(dxTotal) * 100.0
                + Double(c.breakPerfect) / Double(breakCount) * 1.0
                + Double(c.breakGreat) / Double(breakCount) * 0.4
                + Double(c.breakGood) / Double(breakCount) * 0.3
            let minScore = Double(dxPlayMin) / Double(dxTotal) * 100.0
                + Double(c.breakPerfect) / Double(breakCount) * 0.5
                + Double(c.breakGreat) / Double(breakCount) * 0.4
                + Double(c.breakGood) / Double(breakCount) * 0.3
            return String(format: "%.4f%% ~ %.4f%%", minScore, maxScore)
        } else {
            if c.breakGreat > 0 || c.breakGood > 0 || c.breakMiss > 0 ||
                c.breakPerfect * 2600 < c.breakScore || c.breakPerfect * 2500 > c.breakScore {
                throw FinaleConversionError.invalidBreakScore
            }
            let dxBreak = 1.0 - 0.25 * Double(breakCount * 2600 - c.breakScore) / 50.0 / Double(breakCount)
            let dxCurrent = Double(dxPlayMax) / Double(dxTotal) * 100.0 + dxBreak
            return String(format: "%.4f%%", dxCurrent)
        }
    }
}

struct MaxNotesStats {
    let total: Int
    let tap: Int
    let hold: Int
    let slide: Int
    let touch: Int
    let breakCount: Int

    static let fallback = MaxNotesStats(total: 1400, tap: 1000, hold: 300, slide: 400, touch: 300, breakCount: 150)

    static func compute(from songs: [SongData]) -> MaxNotesStats {
        var maxTotal = 1, maxTap = 1, maxHold = 1, maxSlide = 1, maxTouch = 1, maxBreak = 1
        for song in songs {
            for chart in song.resolvedCharts {
                maxTotal = max(maxTotal, chart.totalNotes)
                maxTap = max(maxTap, chart.tap)
                maxHold = max(maxHold, chart.hold)
                maxSlide = max(maxSlide, chart.slide)
                maxTouch = max(maxTouch, chart.touch)
                maxBreak = max(maxBreak, chart.breakCount)
            }
        }
        return MaxNotesStats(total: maxTotal, tap: maxTap, hold: maxHold, slide: maxSlide, touch: maxTouch, breakCount: maxBreak)
    }
}
