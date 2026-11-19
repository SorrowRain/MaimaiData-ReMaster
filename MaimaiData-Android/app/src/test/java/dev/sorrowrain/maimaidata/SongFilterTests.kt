package dev.sorrowrain.maimaidata

import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import dev.sorrowrain.maimaidata.models.SongSortOption
import dev.sorrowrain.maimaidata.services.SongFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongFilterTests {

    private fun makeSong(
        id: String,
        title: String,
        genre: String = "舞萌",
        from: String = "舞萌DX",
        type: String = "DX",
        ds: List<Double>,
        level: List<String>,
        alias: List<String>? = null,
        charter: String = "-"
    ): SongData = SongData(
        basicInfo = SongData.BasicInfo(
            artist = "A",
            bpm = 150,
            from = from,
            genre = genre,
            isNew = false,
            title = title,
            version = "1"
        ),
        charts = ds.map {
            SongData.RawChart(
                charter = charter,
                notes = if (type == "DX") listOf(10, 10, 10, 10, 10) else listOf(10, 10, 10, 10)
            )
        },
        ds = ds,
        id = id,
        level = level,
        title = title,
        type = type,
        alias = alias
    )

    private fun fixtures(): List<SongData> = listOf(
        makeSong(id = "1", title = "Alpha", ds = listOf(3.0, 5.0, 8.0, 12.2), level = listOf("3", "5", "8", "12"), alias = listOf("first")),
        makeSong(id = "2", title = "Beta", genre = "其他游戏", ds = listOf(4.0, 6.0, 9.0, 13.8), level = listOf("4", "6", "9", "13+")),
        makeSong(id = "3", title = "Gamma", genre = "宴会場", ds = listOf(1.0, 1.0), level = listOf("1", "1")),
        makeSong(id = "4", title = "Delta", ds = listOf(3.0, 5.0, 8.0, 14.6), level = listOf("3", "5", "8", "14+"), charter = "Zed")
    )

    private fun runFilter(
        criteria: SongFilterCriteria,
        favorites: Set<String> = emptySet(),
        alias: Boolean = true,
        charter: Boolean = false
    ): List<String> = SongFilter.apply(
        songs = fixtures(),
        criteria = criteria,
        region = MaimaiConstants.ServerRegion.CN,
        favoriteIds = favorites,
        enableAliasSearch = alias,
        enableCharterSearch = charter
    ).map { it.id }

    @Test
    fun testDefaultExcludesUtageAndSortsByIdDescending() {
        assertFalse(SongFilterCriteria(keyword = "中文输入").isActive)
        assertEquals(listOf("4", "2", "1"), runFilter(SongFilterCriteria()))
    }

    @Test
    fun testGenreFilterIncludesUtageWhenSelected() {
        val c = SongFilterCriteria(selectedGenres = setOf("宴会場"))
        assertEquals(listOf("3"), runFilter(c))
    }

    @Test
    fun testKeywordMatchesTitleAliasAndId() {
        assertEquals(listOf("1"), runFilter(SongFilterCriteria(keyword = "alpha")))
        assertEquals(listOf("1"), runFilter(SongFilterCriteria(keyword = "ＡＬＰＨＡ")))
        assertEquals(listOf("1"), runFilter(SongFilterCriteria(keyword = "FIRST")))
        assertEquals(emptyList<String>(), runFilter(SongFilterCriteria(keyword = "FIRST"), alias = false))
        assertEquals(listOf("2"), runFilter(SongFilterCriteria(keyword = "2")))
        assertEquals(listOf("2"), runFilter(SongFilterCriteria(keyword = "0002")))
    }

    @Test
    fun testCharterSearchOnlyWhenEnabled() {
        val c = SongFilterCriteria(keyword = "zed")
        assertEquals(emptyList<String>(), runFilter(c, charter = false))
        assertEquals(listOf("4"), runFilter(c, charter = true))
    }

    @Test
    fun testFavoritesOnly() {
        val c = SongFilterCriteria(onlyFavorites = true)
        assertEquals(listOf("2"), runFilter(c, favorites = setOf("2")))
    }

    @Test
    fun testLevelAndExactDSFilters() {
        assertEquals(listOf("2"), runFilter(SongFilterCriteria(selectedLevel = "13+")))
        assertEquals(listOf("1"), runFilter(SongFilterCriteria(useExactDS = true, exactDS = 12.2)))
    }

    @Test
    fun testMasterSorting() {
        assertEquals(listOf("4", "2", "1"), runFilter(SongFilterCriteria(sortOption = SongSortOption.MASTER_DESC)))
        assertEquals(listOf("1", "2", "4"), runFilter(SongFilterCriteria(sortOption = SongSortOption.MASTER_ASC)))
    }

    @Test
    fun testVersionFilterExpansion() {
        assertEquals(setOf("maimai", "maimai PLUS"), MaimaiConstants.expandVersionFilter(listOf("maimai")))
        assertEquals(setOf("maimai GreeN", "maimai GreeN PLUS"), MaimaiConstants.expandVersionFilter(listOf("GreeN")))
        assertEquals(setOf("舞萌DX 2021"), MaimaiConstants.expandVersionFilter(listOf("舞萌DX 2021")))

        val criteria = SongFilterCriteria(selectedVersions = setOf("maimai", "舞萌DX 2025", "PRiSM"))
        val cnPruned = dev.sorrowrain.maimaidata.models.SongFilter.prunedCriteria(criteria, MaimaiConstants.ServerRegion.CN)
        assertEquals(setOf("maimai", "舞萌DX 2025"), cnPruned.selectedVersions)
        val jpPruned = dev.sorrowrain.maimaidata.models.SongFilter.prunedCriteria(criteria, MaimaiConstants.ServerRegion.JP)
        assertEquals(setOf("maimai", "PRiSM"), jpPruned.selectedVersions)
    }

    @Test
    fun testCounterpartAliasSyncSharesSongNicknamesWhileKeepingTypeSpecificOnes() {
        val sd = makeSong(
            id = "835",
            title = "Believe the Rainbow",
            from = "maimai FiNALE",
            type = "SD",
            ds = listOf(3.0, 13.0),
            level = listOf("3", "13"),
            alias = listOf("believe the rainbow", "相信彩虹", "btr", "标彩虹")
        )
        val dx = makeSong(
            id = "10835",
            title = "Believe the Rainbow",
            from = "舞萌DX 2025",
            type = "DX",
            ds = listOf(4.0, 13.5),
            level = listOf("4", "13+"),
            alias = listOf("believe the rainbow", "dx彩虹")
        )

        val merged = SongData.mergingCounterpartAliases(listOf(sd, dx))
        val mergedSD = merged.firstOrNull { it.id == "835" }
        val mergedDX = merged.firstOrNull { it.id == "10835" }
        assertNotNull(mergedSD)
        assertNotNull(mergedDX)

        assertTrue(mergedDX!!.alias?.contains("相信彩虹") == true)
        assertTrue(mergedDX.alias?.contains("btr") == true)
        assertFalse(mergedDX.alias?.contains("标彩虹") == true)
        assertFalse(mergedSD!!.alias?.contains("dx彩虹") == true)

        val matchedIds = SongFilter.apply(
            songs = merged,
            criteria = SongFilterCriteria(keyword = "相信彩虹"),
            favoriteIds = emptySet(),
            enableAliasSearch = true,
            enableCharterSearch = false
        ).map { it.id }
        assertEquals(listOf("10835", "835"), matchedIds)
    }
}
