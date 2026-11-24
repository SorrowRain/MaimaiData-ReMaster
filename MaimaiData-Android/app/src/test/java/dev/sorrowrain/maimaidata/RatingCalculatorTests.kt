package dev.sorrowrain.maimaidata

import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.models.ResolvedChart
import dev.sorrowrain.maimaidata.models.VersionString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingCalculatorTests {

    @Test
    fun testSSSPlusCapsAtMaxAchievement() {
        // 14.8 @ 100.5% -> 148 * 1005000 * 22.4 / 1e7 = 333.2 -> 333
        assertEquals(333, RatingCalculator.singleSongRating(ds = 14.8, achievement = 100.5))
        // Achievement above 100.5% is capped.
        assertEquals(333, RatingCalculator.singleSongRating(ds = 14.8, achievement = 101.0))
    }

    @Test
    fun testTierBoundaries() {
        assertEquals(223, RatingCalculator.achievementToRating(levelTenths = 100, achievementTenThousandths = 1_004_999))
        assertEquals(216, RatingCalculator.achievementToRating(levelTenths = 100, achievementTenThousandths = 1_000_000))
        assertEquals(194, RatingCalculator.achievementToRating(levelTenths = 100, achievementTenThousandths = 970_000))
        assertEquals(170, RatingCalculator.achievementToRating(levelTenths = 100, achievementTenThousandths = 969_999))
        assertEquals(0, RatingCalculator.achievementToRating(levelTenths = 100, achievementTenThousandths = 50_000))
    }

    @Test
    fun testRatingIsMonotonicInAchievement() {
        var previous = 0
        for (achi in 0..1_010_000 step 5_000) {
            val r = RatingCalculator.achievementToRating(levelTenths = 130, achievementTenThousandths = achi)
            assertTrue("rating dropped at $achi", r >= previous)
            previous = r
        }
    }

    @Test
    fun testReverseCalculateRejectsNonPositiveTarget() {
        assertTrue(RatingCalculator.reverseCalculate(targetTotalRating = 0).isEmpty())
        assertTrue(RatingCalculator.reverseCalculate(targetTotalRating = -5).isEmpty())
    }

    @Test
    fun testReverseCalculateRowsReachTarget() {
        val rows = RatingCalculator.reverseCalculate(targetTotalRating = 15_000)
        assertFalse(rows.isEmpty())
        for (row in rows) {
            assertTrue(row.singleRating >= 15_000 / 50)
            assertEquals(row.singleRating * 50, row.totalRating)
        }
    }

    @Test
    fun testReverseCalculateMaxBoundaryAndUnreachableTarget() {
        val maxRows = RatingCalculator.reverseCalculate(targetTotalRating = 16_850)
        assertFalse(maxRows.isEmpty())
        val ssspRow = maxRows.firstOrNull { it.achievementText == "100.5000%" }
        assertNotNull(ssspRow)
        assertEquals(15.0, ssspRow!!.level, 1e-6)
        assertEquals(337, ssspRow.singleRating)
        assertEquals(16_850, ssspRow.totalRating)

        assertTrue(RatingCalculator.reverseCalculate(targetTotalRating = 17_000).isEmpty())
    }

    @Test
    fun testFinaleToDxConversion() {
        // Missing break notes must throw
        assertThrows(RatingCalculator.FinaleConversionException.MissingNotesOrBreak::class.java) {
            RatingCalculator.convertFinaleToDx(RatingCalculator.FinaleNoteCounts(tapPerfect = 100))
        }

        // All Perfect without breakScore returns 100.5000% ~ 101.0000%
        val rangeText = RatingCalculator.convertFinaleToDx(
            RatingCalculator.FinaleNoteCounts(tapPerfect = 100, breakPerfect = 10)
        )
        assertEquals("100.5000% ~ 101.0000%", rangeText)

        // All 2600 Critical Break Perfect returns exact 101.0000%
        val exactMax = RatingCalculator.convertFinaleToDx(
            RatingCalculator.FinaleNoteCounts(tapPerfect = 100, breakPerfect = 10, breakScore = 26_000)
        )
        assertEquals("101.0000%", exactMax)

        // Invalid breakScore (< 2500 per break) throws
        assertThrows(RatingCalculator.FinaleConversionException.InvalidBreakScore::class.java) {
            RatingCalculator.convertFinaleToDx(
                RatingCalculator.FinaleNoteCounts(tapPerfect = 100, breakPerfect = 10, breakScore = 24_900)
            )
        }
    }

    @Test
    fun testVersionStringNumericComparison() {
        assertTrue(VersionString.isNewer("20261113", than = "2026113"))
        assertFalse(VersionString.isNewer("2026113", than = "20261113"))
        assertTrue(VersionString.isNewer("1.10.0", than = "1.9.9"))
        assertFalse(VersionString.isNewer("1.0.0", than = "1.0.0"))
    }

    @Test
    fun testBuildTierRowsIncludesSSSIntraTierJumpThresholdsAndB50Gains() {
        // 14.8 has SSS base 319 (100.0000%), +1 jumps at 100.1002% (320) and 100.4130% (321), SSS+ 333 (100.5000%)
        val rows = RatingCalculator.buildTierRows(
            ds = 14.8,
            currentAchievement = 98.3500,
            b50BaselineRating = 300
        )

        val sssRows = rows.filter { it.rate == "sss" }
        assertEquals(listOf(1_004_130, 1_001_002, 1_000_000), sssRows.map { it.achiTenThousandths })
        assertEquals(listOf(321, 320, 319), sssRows.map { it.singleRating })

        // Current score 98.3500% highlights S+ (98.0000%), which has 0 remaining gain
        val current = rows.firstOrNull { it.isCurrentTier }
        assertEquals("sp", current?.rate)
        assertEquals(0, current?.b50Gain)

        // Tiers below S+ (97%, 94%) are marked passed with +0 gain
        val sRow = rows.firstOrNull { it.rate == "s" }
        assertEquals(true, sRow?.isPassed)
        assertEquals(0, sRow?.b50Gain)

        // SS (99.0% -> 304) exceeds baseline 300 by +4; SSS+ (333) exceeds by +33
        assertEquals(4, rows.firstOrNull { it.rate == "ss" }?.b50Gain)
        assertEquals(33, rows.firstOrNull { it.rate == "sssp" }?.b50Gain)

        // Un-gainable song (baseline 340 > 333 max) yields +0 for all unreached tiers
        val zeroGainRows = RatingCalculator.buildTierRows(ds = 14.8, currentAchievement = null, b50BaselineRating = 340)
        assertTrue(zeroGainRows.all { it.b50Gain == 0 })

        // Logged-out state (b50BaselineRating == null) omits b50Gain across all rows
        val loggedOutRows = RatingCalculator.buildTierRows(ds = 14.8, currentAchievement = null, b50BaselineRating = null)
        assertTrue(loggedOutRows.all { it.b50Gain == null })
        assertNull(loggedOutRows.first().b50Gain)
    }

    @Test
    fun testAppReleaseInfoTagParsingAndVersionCheck() {
        val payload = dev.sorrowrain.maimaidata.models.GitHubLatestReleasePayload(
            tagName = "v1.1.0",
            htmlUrl = "https://github.com/SorrowRain/MaimaiData-ReMaster/releases/tag/v1.1.0",
            body = "  新增功能与体验优化  "
        )
        val info = dev.sorrowrain.maimaidata.models.AppReleaseInfo.from(payload)!!
        assertEquals("1.1.0", info.version)
        assertEquals("新增功能与体验优化", info.notes)
        assertTrue(info.isNewerThan("1.0.0"))
        assertFalse(info.isNewerThan("1.1.0"))
        assertFalse(info.isNewerThan("1.2.0"))
    }

    @Test
    fun testRecordAccessibilityLabelsAndChartToleranceMetrics() {
        val apRecord = ProberRecord(
            achievements = 101.0,
            ds = 14.8,
            fc = "ap",
            fs = "fsd",
            level = "14+",
            levelIndex = 3,
            ra = 333,
            rate = "sssp",
            songId = 10001,
            title = "T",
            type = "DX"
        )
        val appRecord = apRecord.copy(fc = "app", fs = "fsdp")
        assertEquals("连击状态 AP", apRecord.fcAccessibilityLabel)
        assertEquals("连击状态 AP+", appRecord.fcAccessibilityLabel)
        assertEquals("同步状态 FSD", apRecord.fsAccessibilityLabel)
        assertEquals("同步状态 FSD+", appRecord.fsAccessibilityLabel)

        val chart = ResolvedChart(
            index = 3,
            difficulty = DifficultyType.MASTER,
            ds = 14.8,
            oldDs = null,
            level = "14+",
            charter = "Tester",
            kanji = null,
            tap = 500,
            hold = 50,
            slide = 50,
            touch = 0,
            breakCount = 50,
            totalNotes = 650
        )
        val metrics = chart.toleranceMetrics
        assertNotNull(metrics)
        assertEquals(0.0002, metrics!!.tapGreat, 1e-9)
        assertEquals(0.00005, metrics.breakPerfect50!!, 1e-9)
        assertEquals(101.0, chart.finaleTheoreticalMax(isDX = false)!!, 1e-6)
        assertNull(chart.finaleTheoreticalMax(isDX = true))
    }
}
