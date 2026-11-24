package dev.sorrowrain.maimaidata

import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.Best50Renderer
import dev.sorrowrain.maimaidata.services.DXDataIdMap
import dev.sorrowrain.maimaidata.services.DXDataRawCatalog
import dev.sorrowrain.maimaidata.services.MaimaiAPI
import dev.sorrowrain.maimaidata.services.ZetarakuCatalog
import dev.sorrowrain.maimaidata.services.ZetarakuCatalogConverter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiRegionTests {

    @Test
    fun testAPPlusOneSingleSongRatingTierRowAndRecordProjection() {
        // Base 14.8 @ 100.5000% = 333
        assertEquals(
            333,
            RatingCalculator.singleSongRating(ds = 14.8, achievement = 100.5, isAP = true, apBonusEnabled = false)
        )
        assertEquals(
            334,
            RatingCalculator.singleSongRating(ds = 14.8, achievement = 100.5, isAP = true, apBonusEnabled = true)
        )
        assertEquals(
            333,
            RatingCalculator.singleSongRating(ds = 14.8, achievement = 100.5, isAP = false, apBonusEnabled = true)
        )

        // Top AP row in JP/INTL mode sits above SSS+ with +1 Ra and distinct id
        val nonAPRows = RatingCalculator.buildTierRows(
            ds = 14.8,
            currentAchievement = 100.6,
            currentIsAP = false,
            includeAPBonusRow = true
        )
        assertEquals("ap", nonAPRows[0].rate)
        assertEquals(334, nonAPRows[0].singleRating)
        assertEquals(false, nonAPRows[0].isCurrentTier)
        assertEquals("sssp", nonAPRows[1].rate)
        assertEquals(333, nonAPRows[1].singleRating)
        assertEquals(true, nonAPRows[1].isCurrentTier)
        assertNotEquals(nonAPRows[0].id, nonAPRows[1].id)

        val apRows = RatingCalculator.buildTierRows(
            ds = 14.8,
            currentAchievement = 100.6,
            currentIsAP = true,
            includeAPBonusRow = true
        )
        assertEquals(true, apRows[0].isCurrentTier)
        assertEquals(true, apRows[1].isPassed)

        val recordJson = """
            {
                "achievements": 100.5000,
                "ds": 14.7,
                "fc": "ap",
                "fs": "fsp",
                "level": "14+",
                "level_index": 3,
                "ra": 330,
                "rate": "sssp",
                "song_id": 11663,
                "title": "Test Song",
                "type": "DX"
            }
        """.trimIndent()
        val cnRecord = MaimaiAPI.json.decodeFromString<ProberRecord>(recordJson)
        assertEquals(330, cnRecord.computedRating)

        val jpSong = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "Artist",
                bpm = 180,
                from = "MAGiCAL",
                genre = "舞萌",
                isNew = true,
                title = "Test Song",
                version = "MAGiCAL"
            ),
            charts = (0 until 4).map { SongData.RawChart(charter = "-", notes = listOf(100, 10, 10, 10, 10)) },
            ds = listOf(4.0, 8.0, 11.5, 14.8),
            oldDs = listOf(4.0, 8.0, 11.5, 14.7),
            id = "11663",
            level = listOf("4", "8", "11+", "14+"),
            title = "Test Song",
            type = "DX",
            alias = null
        )

        val cnProjected = cnRecord.projected(onto = jpSong, region = MaimaiConstants.ServerRegion.CN)
        assertEquals(14.7, cnProjected.ds, 0.0001)
        assertEquals(330, cnProjected.computedRating)

        val jpProjected = cnRecord.projected(onto = jpSong, region = MaimaiConstants.ServerRegion.JP)
        assertEquals(14.8, jpProjected.ds, 0.0001)
        assertEquals(334, jpProjected.computedRating)
    }

    @Test
    fun testZetarakuConverterDXDataIdJoinUtageBPMCommentAndMigration() {
        val cnLink = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "Clean Tears",
                bpm = 132,
                from = "maimai ORANGE",
                genre = "niconico & VOCALOID",
                isNew = false,
                title = "Link(CoF)",
                version = "14000"
            ),
            charts = (0 until 4).map { SongData.RawChart(charter = "-", notes = listOf(100, 20, 20, 10)) },
            ds = listOf(3.0, 6.0, 9.0, 12.4),
            oldDs = null,
            id = "383",
            level = listOf("3", "6", "9", "12"),
            title = "Link(CoF)",
            type = "SD",
            alias = listOf("cof", "link cof")
        )

        val rawDXDataJson = """
            {
              "updatedAt": "2026-11-17T16:00:00Z",
              "songs": [
                {
                  "category": "maimai",
                  "title": "魔法仕掛けのファンタジア",
                  "sheets": [
                    { "type": "dx", "internalId": 2025 }
                  ]
                }
              ]
            }
        """.trimIndent()
        val dxMap = DXDataIdMap.extract(MaimaiAPI.json.decodeFromString<DXDataRawCatalog>(rawDXDataJson))
        assertEquals(12025, dxMap.idsByKey["魔法仕掛けのファンタジア|舞萌|DX"])

        val rawCatalogJson = """
            {
              "updateTime": "2026-10-29",
              "songs": [
                {
                  "songId": "Link (2)",
                  "category": "niconico＆ボーカロイド",
                  "title": "Link",
                  "artist": "Clean Tears",
                  "bpm": 132.0,
                  "imageName": "link_cof.png",
                  "version": "ORANGE",
                  "releaseDate": "2014-09-18",
                  "sheets": [
                    { "type": "std", "difficulty": "basic", "level": "3", "internalLevelValue": 3.0, "noteDesigner": null, "noteCounts": { "tap": 100, "hold": 20, "slide": 20, "touch": null, "break": 10, "total": 150 }, "regions": { "jp": true, "intl": true }, "version": "ORANGE" },
                    { "type": "std", "difficulty": "advanced", "level": "6", "internalLevelValue": 6.0, "noteDesigner": null, "noteCounts": { "tap": 100, "hold": 20, "slide": 20, "touch": null, "break": 10, "total": 150 }, "regions": { "jp": true, "intl": true }, "version": "ORANGE" },
                    { "type": "std", "difficulty": "expert", "level": "9", "internalLevelValue": 9.0, "noteDesigner": "-", "noteCounts": { "tap": 100, "hold": 20, "slide": 20, "touch": null, "break": 10, "total": 150 }, "regions": { "jp": true, "intl": true }, "version": "ORANGE" },
                    {
                      "type": "std",
                      "difficulty": "master",
                      "level": "12+",
                      "internalLevelValue": 12.6,
                      "noteDesigner": "Re-rated Charter",
                      "noteCounts": { "tap": 300, "hold": 40, "slide": 50, "touch": null, "break": 20, "total": 410 },
                      "regions": { "jp": true, "intl": true },
                      "regionOverrides": {
                        "intl": { "level": "12", "internalLevelValue": 12.5, "version": "ORANGE" }
                      },
                      "version": "ORANGE"
                    }
                  ]
                },
                {
                  "songId": "魔法仕掛けのファンタジア",
                  "category": "maimai",
                  "title": "魔法仕掛けのファンタジア",
                  "artist": "igel",
                  "bpm": 280.0,
                  "imageName": "fantasia.png",
                  "version": "MAGiCAL",
                  "releaseDate": "2026-10-29",
                  "sheets": [
                    { "type": "dx", "difficulty": "basic", "level": "5", "internalLevelValue": 5.0, "noteDesigner": "-", "noteCounts": { "tap": 80, "hold": 10, "slide": 10, "touch": 5, "break": 5, "total": 110 }, "regions": { "jp": true, "intl": false }, "version": "MAGiCAL" },
                    { "type": "dx", "difficulty": "advanced", "level": "8", "internalLevelValue": 8.2, "noteDesigner": "-", "noteCounts": { "tap": 120, "hold": 15, "slide": 15, "touch": 10, "break": 8, "total": 168 }, "regions": { "jp": true, "intl": false }, "version": "MAGiCAL" },
                    { "type": "dx", "difficulty": "expert", "level": "12", "internalLevelValue": 12.2, "noteDesigner": "Ex", "noteCounts": { "tap": 250, "hold": 30, "slide": 30, "touch": 20, "break": 15, "total": 345 }, "regions": { "jp": true, "intl": false }, "version": "MAGiCAL" },
                    { "type": "dx", "difficulty": "master", "level": "14+", "internalLevelValue": 14.8, "noteDesigner": "Mas", "noteCounts": { "tap": 450, "hold": 60, "slide": 70, "touch": 40, "break": 30, "total": 650 }, "regions": { "jp": true, "intl": false }, "version": "MAGiCAL" }
                  ]
                },
                {
                  "songId": "[魔]魔法仕掛けのファンタジア",
                  "category": "宴会場",
                  "title": "[魔]魔法仕掛けのファンタジア",
                  "artist": "igel",
                  "bpm": null,
                  "imageName": "utage_fantasia.png",
                  "version": "MAGiCAL",
                  "releaseDate": "2026-10-29",
                  "comment": "魔法の力でノンストップ！",
                  "sheets": [
                    { "type": "utage", "difficulty": "【魔】", "level": "14+?", "internalLevelValue": 14.8, "noteDesigner": null, "noteCounts": { "tap": null, "hold": null, "slide": null, "touch": null, "break": null, "total": null }, "regions": { "jp": true, "intl": false }, "version": null }
                  ]
                }
              ]
            }
        """.trimIndent()

        val catalog = MaimaiAPI.json.decodeFromString<ZetarakuCatalog>(rawCatalogJson)
        val conv = ZetarakuCatalogConverter.convertWithMigrations(
            catalog = catalog,
            region = MaimaiConstants.ServerRegion.JP,
            cnReference = listOf(cnLink),
            dxIdMap = dxMap
        )

        val jpFantasia = conv.songs.firstOrNull { it.title == "魔法仕掛けのファンタジア" }
        assertNotNull(jpFantasia)
        assertEquals("12025", jpFantasia!!.id)
        assertEquals(12025, jpFantasia.numericId)
        assertEquals(
            "https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/fantasia.png",
            jpFantasia.coverCandidateUrls.firstOrNull()
        )
        assertTrue(
            conv.migratedIdPairs.any {
                it.legacyId == "zeta_dx_魔法仕掛けのファンタジア" && it.resolvedId == "12025"
            }
        )

        val jpUtage = conv.songs.firstOrNull { it.isUtage }
        assertNotNull(jpUtage)
        assertEquals("112025", jpUtage!!.id)
        assertEquals(280, jpUtage.basicInfo.bpm)
        assertEquals("魔法の力でノンストップ！", jpUtage.comment)
        assertEquals("【魔】", jpUtage.resolvedCharts[0].kanji)

        val rawCNUtage = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "igel",
                bpm = 280,
                from = "舞萌DX 2026",
                genre = "宴会場",
                isNew = false,
                title = "[魔]魔法仕掛けのファンタジア",
                version = "26000"
            ),
            charts = listOf(SongData.RawChart(charter = "-", notes = listOf(100, 10, 10, 10, 10))),
            ds = listOf(14.8),
            oldDs = null,
            id = "112025",
            level = listOf("14+?"),
            title = "[魔]魔法仕掛けのファンタジア",
            type = "DX",
            alias = null
        )
        val enrichedCN = ZetarakuCatalogConverter.enrichCNSongsWithUtageMetadata(listOf(rawCNUtage), catalog)
        assertEquals("魔法の力でノンストップ！", enrichedCN[0].comment)
        assertEquals("【魔】", enrichedCN[0].resolvedCharts[0].kanji)
    }

    @Test
    fun testDynamicB15VersionWindowForINTLAndJP() {
        val json = """
            {
              "versions": [
                { "version": "CiRCLE", "abbr": "CiRCLE (丸)" },
                { "version": "CiRCLE PLUS", "abbr": "CiRCLE+ (廻)" },
                { "version": "MAGiCAL", "abbr": "MAGiCAL (魔)" },
                { "version": "MAGiCAL PLUS", "abbr": "MAGiCAL+" }
              ],
              "songs": [
                {
                  "songId": "s_circle",
                  "category": "maimai",
                  "title": "Circle Song",
                  "artist": "A",
                  "bpm": 150.0,
                  "imageName": "c.png",
                  "version": "CiRCLE",
                  "sheets": [
                    { "type": "dx", "difficulty": "master", "level": "14", "internalLevelValue": 14.0, "regions": { "jp": true, "intl": true }, "version": "CiRCLE" }
                  ]
                },
                {
                  "songId": "s_circle_plus",
                  "category": "maimai",
                  "title": "Circle Plus Song",
                  "artist": "A",
                  "bpm": 150.0,
                  "imageName": "cp.png",
                  "version": "CiRCLE PLUS",
                  "sheets": [
                    { "type": "dx", "difficulty": "master", "level": "14", "internalLevelValue": 14.2, "regions": { "jp": true, "intl": true }, "version": "CiRCLE PLUS" }
                  ]
                },
                {
                  "songId": "s_magical",
                  "category": "maimai",
                  "title": "Magical Song",
                  "artist": "A",
                  "bpm": 150.0,
                  "imageName": "m.png",
                  "version": "MAGiCAL",
                  "sheets": [
                    { "type": "dx", "difficulty": "master", "level": "14+", "internalLevelValue": 14.7, "regions": { "jp": true, "intl": true }, "version": "MAGiCAL" }
                  ]
                },
                {
                  "songId": "s_magical_plus",
                  "category": "maimai",
                  "title": "Magical Plus Song",
                  "artist": "A",
                  "bpm": 150.0,
                  "imageName": "mp.png",
                  "version": "MAGiCAL PLUS",
                  "sheets": [
                    { "type": "dx", "difficulty": "master", "level": "14+", "internalLevelValue": 14.9, "regions": { "jp": true, "intl": false }, "version": "MAGiCAL PLUS" }
                  ]
                }
              ]
            }
        """.trimIndent()
        val catalog = MaimaiAPI.json.decodeFromString<ZetarakuCatalog>(json)

        val intlSongs = ZetarakuCatalogConverter.convertWithMigrations(
            catalog = catalog,
            region = MaimaiConstants.ServerRegion.INTL,
            cnReference = emptyList(),
            dxIdMap = null
        ).songs
        val intlByTitle = intlSongs.associateBy { it.title }
        assertEquals(false, intlByTitle["Circle Song"]?.basicInfo?.isNew)
        assertEquals(true, intlByTitle["Circle Plus Song"]?.basicInfo?.isNew)
        assertEquals(true, intlByTitle["Magical Song"]?.basicInfo?.isNew)

        // JP has MAGiCAL PLUS active -> B15 window advances to the latest 2 (MAGiCAL + MAGiCAL PLUS), NOT CiRCLE PLUS
        val jpSongs = ZetarakuCatalogConverter.convertWithMigrations(
            catalog = catalog,
            region = MaimaiConstants.ServerRegion.JP,
            cnReference = emptyList(),
            dxIdMap = null
        ).songs
        val jpByTitle = jpSongs.associateBy { it.title }
        assertEquals(false, jpByTitle["Circle Plus Song"]?.basicInfo?.isNew)
        assertEquals(true, jpByTitle["Magical Song"]?.basicInfo?.isNew)
        assertEquals(true, jpByTitle["Magical Plus Song"]?.basicInfo?.isNew)
    }

    @Test
    fun testShortGenreAndAllVersionAndRankAssetsExist() {
        assertEquals("VOCALOID", MaimaiConstants.shortGenreDisplayName("niconico & VOCALOID"))
        assertEquals("流行&动漫", MaimaiConstants.shortGenreDisplayName("流行&动漫"))

        for (ver in MaimaiConstants.cnVersions) {
            assertNotNull("Missing CN version asset: ${ver.asset}", MaimaiConstants.drawableResId(ver.asset))
        }
        for (ver in MaimaiConstants.jpVersions) {
            assertNotNull("Missing JP version asset: ${ver.asset}", MaimaiConstants.drawableResId(ver.asset))
        }

        for (rate in MaimaiConstants.validRankKeys) {
            val cnAsset = MaimaiConstants.rankAssetName(rate, MaimaiConstants.ServerRegion.CN)
            val intlAsset = MaimaiConstants.rankAssetName(rate, MaimaiConstants.ServerRegion.INTL)
            val jpAsset = MaimaiConstants.rankAssetName(rate, MaimaiConstants.ServerRegion.JP)
            assertTrue(cnAsset.contains("_prism_"))
            assertTrue(intlAsset.contains("_circle_"))
            assertEquals(intlAsset, jpAsset)
            assertNotNull("Missing PRiSM rank asset: $cnAsset", MaimaiConstants.drawableResId(cnAsset))
            assertNotNull("Missing CiRCLE rank asset: $jpAsset", MaimaiConstants.drawableResId(jpAsset))
        }
        assertNotNull(MaimaiConstants.drawableResId(MaimaiConstants.rankAssetName("ap", MaimaiConstants.ServerRegion.JP)))
        assertNotNull(MaimaiConstants.drawableResId(Best50Renderer.ratingPlateName(15_500)))
    }
}
