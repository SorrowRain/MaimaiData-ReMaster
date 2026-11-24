import XCTest
@testable import MaimaiData

final class RatingCalculatorTests: XCTestCase {
    func testSSSPlusCapsAtMaxAchievement() {
        // 14.8 @ 100.5% -> 148 * 1005000 * 22.4 / 1e7 = 333.2 -> 333
        XCTAssertEqual(RatingCalculator.singleSongRating(ds: 14.8, achievement: 100.5), 333)
        // Achievement above 100.5% is capped.
        XCTAssertEqual(RatingCalculator.singleSongRating(ds: 14.8, achievement: 101.0), 333)
    }

    func testTierBoundaries() {
        XCTAssertEqual(RatingCalculator.achievementToRating(levelTenths: 100, achievementTenThousandths: 1_004_999), 223)
        XCTAssertEqual(RatingCalculator.achievementToRating(levelTenths: 100, achievementTenThousandths: 1_000_000), 216)
        XCTAssertEqual(RatingCalculator.achievementToRating(levelTenths: 100, achievementTenThousandths: 970_000), 194)
        XCTAssertEqual(RatingCalculator.achievementToRating(levelTenths: 100, achievementTenThousandths: 969_999), 170)
        XCTAssertEqual(RatingCalculator.achievementToRating(levelTenths: 100, achievementTenThousandths: 50_000), 0)
    }

    func testRatingIsMonotonicInAchievement() {
        var previous = 0
        for achi in stride(from: 0, through: 1_010_000, by: 5_000) {
            let r = RatingCalculator.achievementToRating(levelTenths: 130, achievementTenThousandths: achi)
            XCTAssertGreaterThanOrEqual(r, previous, "rating dropped at \(achi)")
            previous = r
        }
    }

    func testReverseCalculateRejectsNonPositiveTarget() {
        XCTAssertTrue(RatingCalculator.reverseCalculate(targetTotalRating: 0).isEmpty)
        XCTAssertTrue(RatingCalculator.reverseCalculate(targetTotalRating: -5).isEmpty)
    }

    func testReverseCalculateRowsReachTarget() {
        let rows = RatingCalculator.reverseCalculate(targetTotalRating: 15000)
        XCTAssertFalse(rows.isEmpty)
        for row in rows {
            XCTAssertGreaterThanOrEqual(row.singleRating, 15000 / 50)
            XCTAssertEqual(row.totalRating, row.singleRating * 50)
        }
    }

    func testReverseCalculateMaxBoundaryAndUnreachableTarget() {
        // 15.0 @ 100.5% = 337 -> 337 * 50 = 16850 exact boundary
        let maxRows = RatingCalculator.reverseCalculate(targetTotalRating: 16850)
        XCTAssertFalse(maxRows.isEmpty)
        let ssspRow = maxRows.first { $0.achi == 1_005_000 }
        XCTAssertEqual(ssspRow?.minLv, 150)
        XCTAssertEqual(ssspRow?.singleRating, 337)
        XCTAssertEqual(ssspRow?.totalRating, 16850)

        XCTAssertTrue(RatingCalculator.reverseCalculate(targetTotalRating: 17000).isEmpty)
    }

    func testFinaleToDxConversion() throws {
        // Missing break notes must throw
        XCTAssertThrowsError(
            try RatingCalculator.convertFinaleToDx(.init(tapPerfect: 100))
        )
        // All Perfect without breakScore returns 100.5000% ~ 101.0000%
        let rangeText = try RatingCalculator.convertFinaleToDx(
            .init(tapPerfect: 100, breakPerfect: 10)
        )
        XCTAssertEqual(rangeText, "100.5000% ~ 101.0000%")

        // All 2600 Critical Break Perfect returns exact 101.0000%
        let exactMax = try RatingCalculator.convertFinaleToDx(
            .init(tapPerfect: 100, breakPerfect: 10, breakScore: 26_000)
        )
        XCTAssertEqual(exactMax, "101.0000%")

        // Invalid breakScore (< 2500 per break) throws
        XCTAssertThrowsError(
            try RatingCalculator.convertFinaleToDx(
                .init(tapPerfect: 100, breakPerfect: 10, breakScore: 24_900)
            )
        )
    }

    func testVersionStringNumericComparison() {
        XCTAssertTrue(VersionString.isNewer("20261113", than: "2026113"))
        XCTAssertFalse(VersionString.isNewer("2026113", than: "20261113"))
        XCTAssertTrue(VersionString.isNewer("1.10.0", than: "1.9.9"))
        XCTAssertFalse(VersionString.isNewer("1.0.0", than: "1.0.0"))
    }

    func testBuildTierRowsIncludesSSSIntraTierJumpThresholdsAndB50Gains() {
        // 14.8 has SSS base 319 (100.0000%), +1 jumps at 100.1002% (320) and 100.4130% (321), SSS+ 333 (100.5000%)
        let rows = RatingCalculator.buildTierRows(
            ds: 14.8,
            currentAchievement: 98.3500,
            b50BaselineRating: 300
        )

        let sssRows = rows.filter { $0.rate == "sss" }
        XCTAssertEqual(sssRows.map(\.achiTenThousandths), [1_004_130, 1_001_002, 1_000_000])
        XCTAssertEqual(sssRows.map(\.singleRating), [321, 320, 319])

        // Current score 98.3500% highlights S+ (98.0000%), which has 0 remaining gain
        let current = rows.first { $0.isCurrentTier }
        XCTAssertEqual(current?.rate, "sp")
        XCTAssertEqual(current?.b50Gain, 0)

        // Tiers below S+ (97%, 94%) are marked passed with +0 gain
        let sRow = rows.first { $0.rate == "s" }
        XCTAssertEqual(sRow?.isPassed, true)
        XCTAssertEqual(sRow?.b50Gain, 0)

        // SS (99.0% -> 304) exceeds baseline 300 by +4; SSS+ (333) exceeds by +33
        XCTAssertEqual(rows.first { $0.rate == "ss" }?.b50Gain, 4)
        XCTAssertEqual(rows.first { $0.rate == "sssp" }?.b50Gain, 33)

        // Un-gainable song (baseline 340 > 333 max) yields +0 for all unreached tiers
        let zeroGainRows = RatingCalculator.buildTierRows(ds: 14.8, currentAchievement: nil, b50BaselineRating: 340)
        XCTAssertTrue(zeroGainRows.allSatisfy { $0.b50Gain == 0 })

        // Logged-out state (b50BaselineRating == nil) omits b50Gain across all rows
        let loggedOutRows = RatingCalculator.buildTierRows(ds: 14.8, currentAchievement: nil, b50BaselineRating: nil)
        XCTAssertTrue(loggedOutRows.allSatisfy { $0.b50Gain == nil })
    }

    func testAppReleaseInfoTagParsingAndVersionCheck() throws {
        let json = #"{"tag_name":"v1.1.0","html_url":"https://github.com/SorrowRain/MaimaiData-ReMaster/releases/tag/v1.1.0","body":"  新增功能与体验优化  "}"#
        let payload = try JSONDecoder().decode(GitHubLatestReleasePayload.self, from: Data(json.utf8))
        let info = try XCTUnwrap(AppReleaseInfo.from(payload: payload))
        XCTAssertEqual(info.version, "1.1.0")
        XCTAssertEqual(info.notes, "新增功能与体验优化")
        XCTAssertTrue(info.isNewer(than: "1.0.0"))
        XCTAssertFalse(info.isNewer(than: "1.1.0"))
        XCTAssertFalse(info.isNewer(than: "1.2.0"))
    }

    func testRecordAccessibilityLabelsAndChartToleranceMetrics() throws {
        let apJson = """
        {"achievements":101.0,"ds":14.8,"fc":"ap","fs":"fsd","level":"14+","level_index":3,"ra":333,"rate":"sssp","song_id":10001,"title":"T","type":"DX"}
        """.data(using: .utf8)!
        let appJson = """
        {"achievements":101.0,"ds":14.8,"fc":"app","fs":"fsdp","level":"14+","level_index":3,"ra":333,"rate":"sssp","song_id":10001,"title":"T","type":"DX"}
        """.data(using: .utf8)!
        let apRecord = try JSONDecoder().decode(ProberRecord.self, from: apJson)
        let appRecord = try JSONDecoder().decode(ProberRecord.self, from: appJson)
        XCTAssertEqual(apRecord.fcAccessibilityLabel, "连击状态 AP")
        XCTAssertEqual(appRecord.fcAccessibilityLabel, "连击状态 AP+")
        XCTAssertEqual(apRecord.fsAccessibilityLabel, "同步状态 FSD")
        XCTAssertEqual(appRecord.fsAccessibilityLabel, "同步状态 FSD+")

        let chart = ResolvedChart(
            index: 3,
            difficulty: .master,
            ds: 14.8,
            oldDs: nil,
            level: "14+",
            charter: "Tester",
            kanji: nil,
            tap: 500,
            hold: 50,
            slide: 50,
            touch: 0,
            breakCount: 50,
            totalNotes: 650
        )
        let metrics = try XCTUnwrap(chart.toleranceMetrics)
        // totalWeightedScore = 500 + 100 + 150 + 250 = 1000
        XCTAssertEqual(metrics.tapGreat, 0.0002, accuracy: 1e-9)
        XCTAssertEqual(metrics.breakPerfect50 ?? 0, 0.00005, accuracy: 1e-9)
        // FiNALE theoretical max: (250000 + 50000 + 75000 + 130000) / (250000 + 50000 + 75000 + 125000) = 505000 / 500000 = 101.00%
        XCTAssertEqual(chart.finaleTheoreticalMax(isDX: false), 101.0)
        XCTAssertNil(chart.finaleTheoreticalMax(isDX: true))
    }
}
