package dev.sorrowrain.maimaidata.models

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object RatingCalculator {
    fun achievementToRating(levelTenths: Int, achievementTenThousandths: Int): Int {
        val multiplier = when {
            achievementTenThousandths >= 1_005_000 -> 22.4
            achievementTenThousandths == 1_004_999 -> 22.2
            achievementTenThousandths in 1_000_000..1_004_998 -> 21.6
            achievementTenThousandths == 999_999 -> 21.4
            achievementTenThousandths in 995_000..999_998 -> 21.1
            achievementTenThousandths in 990_000..994_999 -> 20.8
            achievementTenThousandths == 989_999 -> 20.6
            achievementTenThousandths in 980_000..989_998 -> 20.3
            achievementTenThousandths in 970_000..979_999 -> 20.0
            achievementTenThousandths == 969_999 -> 17.6
            achievementTenThousandths in 940_000..969_998 -> 16.8
            achievementTenThousandths in 900_000..939_999 -> 15.2
            achievementTenThousandths in 800_000..899_999 -> 13.6
            achievementTenThousandths == 799_999 -> 12.8
            achievementTenThousandths in 750_000..799_998 -> 12.0
            achievementTenThousandths in 700_000..749_999 -> 11.2
            achievementTenThousandths in 600_000..699_999 -> 9.6
            achievementTenThousandths in 500_000..599_999 -> 8.0
            achievementTenThousandths in 400_000..499_999 -> 6.4
            achievementTenThousandths in 300_000..399_999 -> 4.8
            achievementTenThousandths in 200_000..299_999 -> 3.2
            achievementTenThousandths in 100_000..199_999 -> 1.6
            else -> 0.0
        }
        val capped = min(achievementTenThousandths, 1_005_000)
        return (capped.toDouble() * levelTenths.toDouble() * multiplier / 10_000_000.0).toInt()
    }

    fun singleSongRating(
        ds: Double,
        achievement: Double,
        isAP: Boolean = false,
        apBonusEnabled: Boolean = false
    ): Int {
        val base = achievementToRating(
            levelTenths = (ds * 10.0).roundToInt(),
            achievementTenThousandths = (achievement * 10_000.0).roundToInt()
        )
        val bonus = if (apBonusEnabled && isAP && ds > 0.0) 1 else 0
        return base + bonus
    }

    data class TargetRow(
        val level: Double,
        val achievementText: String,
        val singleRating: Int,
        val totalRating: Int
    )

    fun reverseCalculate(targetTotalRating: Int): List<TargetRow> {
        if (targetTotalRating <= 0) return emptyList()
        val perSong = (targetTotalRating + 49) / 50
        var minLv = 0
        for (i in 10..150) {
            if (perSong <= achievementToRating(levelTenths = i, achievementTenThousandths = 1_005_000)) {
                minLv = i
                break
            }
        }
        if (minLv == 0) return emptyList()

        val bestByAchi = mutableMapOf<Int, Int>()
        for (lv in 150 downTo minLv) {
            val achi = reachableAchievement(levelTenths = lv, targetSingleRating = perSong)
            if (achi == 800_000 || achi == 900_000 || achi == 940_000 || achi in 970_000..1_010_000) {
                bestByAchi[achi] = lv
            }
        }
        return bestByAchi.keys.sorted().mapNotNull { achi ->
            val lv = bestByAchi[achi] ?: return@mapNotNull null
            val r = achievementToRating(levelTenths = lv, achievementTenThousandths = achi)
            TargetRow(
                level = lv / 10.0,
                achievementText = String.format(Locale.US, "%.4f%%", achi / 10_000.0),
                singleRating = r,
                totalRating = r * 50
            )
        }
    }

    private fun reachableAchievement(levelTenths: Int, targetSingleRating: Int): Int {
        if (achievementToRating(levelTenths, 1_005_000) < targetSingleRating) {
            return 1_010_001
        }
        var maxAchi = 1_010_000
        var minAchi = 0
        repeat(21) {
            if (maxAchi - minAchi >= 2) {
                val mid = (maxAchi + minAchi) / 2
                if (achievementToRating(levelTenths, mid) < targetSingleRating) {
                    minAchi = mid
                } else {
                    maxAchi = mid
                }
            }
        }
        return maxAchi
    }

    data class TierRow(
        val rate: String,
        val achiTenThousandths: Int,
        val singleRating: Int,
        val isCurrentTier: Boolean,
        val isPassed: Boolean,
        val b50Gain: Int?
    ) {
        val id: String get() = "${rate}_$achiTenThousandths"
        val isAPRow: Boolean get() = rate == "ap"
        val achievementText: String
            get() = String.format(Locale.US, "%.4f%%", achiTenThousandths / 10_000.0)

        fun badgeAssetName(region: MaimaiConstants.ServerRegion): String =
            MaimaiConstants.rankAssetName(rate, region)
    }

    fun buildTierRows(
        ds: Double,
        currentAchievement: Double? = null,
        currentIsAP: Boolean = false,
        b50BaselineRating: Int? = null,
        includeAPBonusRow: Boolean = false
    ): List<TierRow> {
        val lv = (ds * 10.0).roundToInt()
        if (lv <= 0) return emptyList()

        val specs = mutableListOf<Pair<String, Int>>()
        if (includeAPBonusRow) {
            specs += "ap" to 1_005_000
        }
        specs += "sssp" to 1_005_000

        val baseSSSRa = achievementToRating(lv, 1_000_000)
        val maxIntraSSSRa = achievementToRating(lv, 1_004_998)
        if (maxIntraSSSRa > baseSSSRa) {
            for (targetRa in maxIntraSSSRa downTo (baseSSSRa + 1)) {
                minimumSSSThreshold(lv, targetRa)?.let { threshold ->
                    specs += "sss" to threshold
                }
            }
        }

        specs += listOf(
            "sss" to 1_000_000,
            "ssp" to 995_000,
            "ss" to 990_000,
            "sp" to 980_000,
            "s" to 970_000,
            "aaa" to 940_000
        )

        val currentAchiInt = currentAchievement?.let { (it * 10_000.0).roundToInt() }
        val matchedCurrentIndex: Int? = currentAchiInt?.let { cur ->
            val found = specs.indexOfFirst { (rate, achi) ->
                if (rate == "ap") currentIsAP && cur >= achi else cur >= achi
            }
            found.takeIf { it >= 0 }
        }

        return specs.mapIndexed { idx, (rate, achi) ->
            val baseRa = achievementToRating(lv, achi)
            val ra = if (rate == "ap") baseRa + 1 else baseRa
            val isCurrent = (matchedCurrentIndex == idx)
            val isPassed = matchedCurrentIndex != null && idx > matchedCurrentIndex
            val gain: Int? = b50BaselineRating?.let { baseline ->
                if (currentAchiInt != null && achi <= currentAchiInt && (rate != "ap" || currentIsAP)) {
                    0
                } else {
                    max(0, ra - baseline)
                }
            }
            TierRow(
                rate = rate,
                achiTenThousandths = achi,
                singleRating = ra,
                isCurrentTier = isCurrent,
                isPassed = isPassed,
                b50Gain = gain
            )
        }
    }

    private fun minimumSSSThreshold(levelTenths: Int, targetRating: Int): Int? {
        var low = 1_000_001
        var high = 1_004_998
        if (achievementToRating(levelTenths, high) < targetRating) return null
        while (low < high) {
            val mid = (low + high) / 2
            if (achievementToRating(levelTenths, mid) >= targetRating) {
                high = mid
            } else {
                low = mid + 1
            }
        }
        return low
    }

    data class FinaleNoteCounts(
        var tapPerfect: Int = 0, var tapGreat: Int = 0, var tapGood: Int = 0, var tapMiss: Int = 0,
        var holdPerfect: Int = 0, var holdGreat: Int = 0, var holdGood: Int = 0, var holdMiss: Int = 0,
        var slidePerfect: Int = 0, var slideGreat: Int = 0, var slideGood: Int = 0, var slideMiss: Int = 0,
        var breakPerfect: Int = 0, var breakGreat: Int = 0, var breakGood: Int = 0, var breakMiss: Int = 0,
        var breakScore: Int = 0
    )

    sealed class FinaleConversionException(override val message: String) : Exception(message) {
        data object MissingNotesOrBreak : FinaleConversionException("请填写有效物量（至少需要包含 Break 数量）")
        data object InvalidBreakScore : FinaleConversionException("绝赞数据错误，请重新填写")
    }

    fun convertFinaleToDx(c: FinaleNoteCounts): String {
        val tapCount = c.tapPerfect + c.tapGreat + c.tapGood + c.tapMiss
        val holdCount = c.holdPerfect + c.holdGreat + c.holdGood + c.holdMiss
        val slideCount = c.slidePerfect + c.slideGreat + c.slideGood + c.slideMiss
        val breakCount = c.breakPerfect + c.breakGreat + c.breakGood + c.breakMiss

        val dxTotal = tapCount * 10 + holdCount * 20 + slideCount * 30 + breakCount * 50
        if (dxTotal <= 0 || breakCount <= 0) {
            throw FinaleConversionException.MissingNotesOrBreak
        }

        val dxPlayMax = c.tapPerfect * 10 + c.tapGreat * 8 + c.tapGood * 5 +
            c.holdPerfect * 20 + c.holdGreat * 16 + c.holdGood * 10 +
            c.slidePerfect * 30 + c.slideGreat * 24 + c.slideGood * 15 +
            c.breakPerfect * 50 + c.breakGreat * 40 + c.breakGood * 20

        val dxPlayMin = c.tapPerfect * 10 + c.tapGreat * 8 + c.tapGood * 5 +
            c.holdPerfect * 20 + c.holdGreat * 16 + c.holdGood * 10 +
            c.slidePerfect * 30 + c.slideGreat * 24 + c.slideGood * 15 +
            c.breakPerfect * 50 + c.breakGreat * 25 + c.breakGood * 20

        return if (c.breakScore == 0) {
            val maxScore = dxPlayMax.toDouble() / dxTotal.toDouble() * 100.0 +
                c.breakPerfect.toDouble() / breakCount.toDouble() * 1.0 +
                c.breakGreat.toDouble() / breakCount.toDouble() * 0.4 +
                c.breakGood.toDouble() / breakCount.toDouble() * 0.3
            val minScore = dxPlayMin.toDouble() / dxTotal.toDouble() * 100.0 +
                c.breakPerfect.toDouble() / breakCount.toDouble() * 0.5 +
                c.breakGreat.toDouble() / breakCount.toDouble() * 0.4 +
                c.breakGood.toDouble() / breakCount.toDouble() * 0.3
            String.format(Locale.US, "%.4f%% ~ %.4f%%", minScore, maxScore)
        } else {
            if (c.breakGreat > 0 || c.breakGood > 0 || c.breakMiss > 0 ||
                c.breakPerfect * 2600 < c.breakScore || c.breakPerfect * 2500 > c.breakScore
            ) {
                throw FinaleConversionException.InvalidBreakScore
            }
            val dxBreak = 1.0 - 0.25 * (breakCount * 2600 - c.breakScore).toDouble() / 50.0 / breakCount.toDouble()
            val dxCurrent = dxPlayMax.toDouble() / dxTotal.toDouble() * 100.0 + dxBreak
            String.format(Locale.US, "%.4f%%", dxCurrent)
        }
    }
}

data class MaxNotesStats(
    val total: Int,
    val tap: Int,
    val hold: Int,
    val slide: Int,
    val touch: Int,
    val breakCount: Int
) {
    companion object {
        val fallback = MaxNotesStats(total = 1400, tap = 1000, hold = 300, slide = 400, touch = 300, breakCount = 150)

        fun compute(songs: List<SongData>): MaxNotesStats {
            var maxTotal = 1
            var maxTap = 1
            var maxHold = 1
            var maxSlide = 1
            var maxTouch = 1
            var maxBreak = 1
            for (song in songs) {
                for (chart in song.resolvedCharts) {
                    maxTotal = max(maxTotal, chart.totalNotes)
                    maxTap = max(maxTap, chart.tap)
                    maxHold = max(maxHold, chart.hold)
                    maxSlide = max(maxSlide, chart.slide)
                    maxTouch = max(maxTouch, chart.touch)
                    maxBreak = max(maxBreak, chart.breakCount)
                }
            }
            return MaxNotesStats(maxTotal, maxTap, maxHold, maxSlide, maxTouch, maxBreak)
        }
    }
}
