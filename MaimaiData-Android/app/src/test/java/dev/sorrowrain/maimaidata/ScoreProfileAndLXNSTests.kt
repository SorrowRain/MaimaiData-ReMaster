package dev.sorrowrain.maimaidata

import dev.sorrowrain.maimaidata.components.ToastState
import dev.sorrowrain.maimaidata.models.CNPlateCatalog
import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.LXNSScore
import dev.sorrowrain.maimaidata.models.LXNSSongListResponse
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.models.SavedAccount
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.ScoreProvider
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.AccountCredentialStore
import dev.sorrowrain.maimaidata.services.LXNSCatalogConverter
import dev.sorrowrain.maimaidata.services.MaimaiAPI
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.services.PKCEHelper
import dev.sorrowrain.maimaidata.services.ZetarakuVersionEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScoreProfileAndLXNSTests {

    private fun resolveAssetFile(fileName: String): File {
        val candidates = listOf(
            File("src/main/assets/$fileName"),
            File("app/src/main/assets/$fileName"),
            File("../app/src/main/assets/$fileName")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Unable to locate bundled asset $fileName from working directory ${File(".").absolutePath}")
    }

    @Test
    fun testLXNSScoreConversionStandardDXAndUtageMapping() {
        val sdSong = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "Kai",
                bpm = 150,
                from = "maimai",
                genre = "舞萌",
                isNew = false,
                title = "True Love Song",
                version = "10000"
            ),
            charts = (0 until 4).map { SongData.RawChart(charter = "-", notes = listOf(63, 23, 8, 2)) },
            ds = listOf(5.0, 7.2, 10.2, 12.4),
            oldDs = null,
            id = "8",
            level = listOf("5", "7", "10", "12"),
            title = "True Love Song",
            type = "SD",
            alias = null
        )

        val dxSong = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "Kai",
                bpm = 150,
                from = "舞萌DX 2021",
                genre = "舞萌",
                isNew = false,
                title = "True Love Song",
                version = "21000"
            ),
            charts = (0 until 4).map { SongData.RawChart(charter = "-", notes = listOf(100, 20, 10, 5, 5)) },
            ds = listOf(4.0, 7.5, 10.8, 13.7),
            oldDs = null,
            id = "10008",
            level = listOf("4", "7+", "10+", "13+"),
            title = "True Love Song",
            type = "DX",
            alias = null
        )

        val lookup = mapOf(8 to sdSong, 10008 to dxSong)

        val rawScores = listOf(
            LXNSScore(
                id = 8,
                songName = "True Love Song",
                level = "12",
                levelIndex = 3,
                achievements = 100.5123,
                fc = "ap",
                fs = "fsd",
                dxScore = 1200,
                dxRating = 279.1,
                rate = "sssp",
                type = "standard"
            ),
            LXNSScore(
                id = 8,
                songName = "True Love Song",
                level = "13+",
                levelIndex = 3,
                achievements = 100.5000,
                fc = "fc",
                fs = null,
                dxScore = 1500,
                dxRating = 308.4,
                rate = "sssp",
                type = "dx"
            ),
            LXNSScore(
                id = 100018,
                songName = "[協]Love You",
                level = "12?",
                levelIndex = 0,
                achievements = 99.2000,
                fc = null,
                fs = "sync",
                dxScore = 980,
                dxRating = 0.0,
                rate = "ss",
                type = "utage"
            )
        )

        val converted = LXNSCatalogConverter.convertScores(rawScores, songsById = lookup)
        assertEquals(3, converted.size)

        val bySongId = converted.associateBy { it.songId }
        val stdRec = bySongId[8]!!
        assertEquals("SD", stdRec.type)
        assertEquals(12.4, stdRec.ds, 0.0001)
        assertEquals("ap", stdRec.fc)
        assertEquals("fsd", stdRec.fs)
        assertEquals(RatingCalculator.singleSongRating(ds = 12.4, achievement = 100.5123), stdRec.computedRating)

        val dxRec = bySongId[10008]!!
        assertEquals("DX", dxRec.type)
        assertEquals(13.7, dxRec.ds, 0.0001)
        assertEquals("13+", dxRec.level)
        assertEquals(RatingCalculator.singleSongRating(ds = 13.7, achievement = 100.5000), dxRec.computedRating)

        val utageRec = bySongId[100018]!!
        assertEquals("DX", utageRec.type)
        assertEquals("[協]Love You", utageRec.title)
        assertEquals("ss", utageRec.rate)
    }

    @Test
    fun testLXNSCatalogBuddyUtageAndMajorVersionOldDsPromotion() {
        val songJson = """
            {
              "versions": [
                { "id": 12, "title": "FiNALE", "version": 19900 },
                { "id": 23, "title": "舞萌DX 2025", "version": 25000 },
                { "id": 24, "title": "舞萌DX 2026", "version": 25500 }
              ],
              "songs": [
                {
                  "id": 834,
                  "title": "PANDORA PARADOXXX",
                  "artist": "削除",
                  "genre": "maimai",
                  "bpm": 150,
                  "version": 19900,
                  "difficulties": {
                    "standard": [
                      { "type": "standard", "difficulty": 0, "level": "7", "level_value": 7.0, "note_designer": "-", "version": 19900, "notes": { "tap": 100, "hold": 10, "slide": 10, "touch": 0, "break": 5 } },
                      { "type": "standard", "difficulty": 1, "level": "9+", "level_value": 9.8, "note_designer": "-", "version": 19900, "notes": { "tap": 200, "hold": 20, "slide": 20, "touch": 0, "break": 10 } },
                      { "type": "standard", "difficulty": 2, "level": "13+", "level_value": 13.8, "note_designer": "P", "version": 19900, "notes": { "tap": 500, "hold": 50, "slide": 50, "touch": 0, "break": 20 } },
                      { "type": "standard", "difficulty": 3, "level": "14+", "level_value": 14.9, "note_designer": "P", "version": 19900, "notes": { "tap": 800, "hold": 80, "slide": 80, "touch": 0, "break": 40 } },
                      { "type": "standard", "difficulty": 4, "level": "15", "level_value": 15.0, "note_designer": "P", "version": 19900, "notes": { "tap": 900, "hold": 90, "slide": 90, "touch": 0, "break": 60 } }
                    ],
                    "dx": []
                  }
                },
                {
                  "id": 110281,
                  "title": "[回]ダブルラリアット",
                  "artist": "アゴアニキ",
                  "genre": "宴会場",
                  "bpm": 138,
                  "version": 25000,
                  "difficulties": {
                    "standard": [],
                    "dx": [],
                    "utage": [
                      {
                        "type": "utage",
                        "difficulty": 0,
                        "level": "13?",
                        "level_value": 0,
                        "note_designer": "-",
                        "version": 25000,
                        "kanji": "回",
                        "description": "LET'S PARTY!!",
                        "is_buddy": true,
                        "notes": {
                          "left": { "total": 653, "tap": 557, "hold": 20, "slide": 55, "touch": 0, "break": 21 },
                          "right": { "total": 653, "tap": 557, "hold": 20, "slide": 55, "touch": 0, "break": 21 }
                        }
                      }
                    ]
                  }
                }
              ]
            }
        """.trimIndent()

        val priorSong = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "削除",
                bpm = 150,
                from = "maimai FiNALE",
                genre = "舞萌",
                isNew = false,
                title = "PANDORA PARADOXXX",
                version = "25000"
            ),
            charts = (0 until 5).map { SongData.RawChart(charter = "-", notes = listOf(100, 10, 10, 5)) },
            ds = listOf(7.0, 9.7, 13.7, 14.9, 15.0),
            oldDs = null,
            id = "834",
            level = listOf("7", "9+", "13+", "14+", "15"),
            title = "PANDORA PARADOXXX",
            type = "SD",
            alias = listOf("潘多拉")
        )

        val decodedSongs = MaimaiAPI.json.decodeFromString<LXNSSongListResponse>(songJson)
        val converted = LXNSCatalogConverter.convertCatalog(
            songResponse = decodedSongs,
            aliasResponse = null,
            existingBaseline = listOf(priorSong)
        )

        assertEquals(25500, converted.latestMajorVersion)
        assertEquals(2, converted.songs.size)

        val pandora = converted.songs.firstOrNull { it.id == "834" }
        assertNotNull(pandora)
        assertEquals("maimai FiNALE", pandora!!.basicInfo.from)
        assertEquals("舞萌", pandora.basicInfo.genre)
        assertEquals(listOf("潘多拉"), pandora.alias)
        assertEquals(13.7, pandora.resolvedCharts[2].oldDs!!, 0.0001)
        assertEquals(13.8, pandora.resolvedCharts[2].ds, 0.0001)

        val buddyUtage = converted.songs.firstOrNull { it.id == "110281" }
        assertNotNull(buddyUtage)
        assertTrue(buddyUtage!!.isUtage)
        assertEquals(2, buddyUtage.resolvedCharts.size)
        assertEquals(DifficultyType.UTAGE, buddyUtage.resolvedCharts[0].difficulty)
        assertEquals(DifficultyType.UTAGE_PLAYER2, buddyUtage.resolvedCharts[1].difficulty)
        assertEquals("回", buddyUtage.resolvedCharts[0].kanji)
        assertEquals(653, buddyUtage.resolvedCharts[0].totalNotes)
    }

    @Test
    fun testBundledSeedSongsAndPlateRulesDecodeCleanly() {
        val songSeedFile = resolveAssetFile("songdata_seed.json")
        val songs = MaimaiAPI.json.decodeFromString<List<SongData>>(songSeedFile.readText(Charsets.UTF_8))
        assertTrue(songs.size >= 1400)

        val plateSeedFile = resolveAssetFile("plate_rules_seed.json")
        val plateCatalog = MaimaiAPI.json.decodeFromString<CNPlateCatalog>(plateSeedFile.readText(Charsets.UTF_8))
        assertTrue(plateCatalog.groups.size >= 19)

        val shinGroup = plateCatalog.groups.firstOrNull { it.id == "真" }
        assertNotNull(shinGroup)
        assertEquals(85, shinGroup!!.basicToMasterSongIds.size)
        assertEquals("maimai", shinGroup.resolvedVersionAsset)
        assertFalse(shinGroup.basicToMasterSongIds.contains(70))
        assertEquals(listOf("真極", "真神", "真舞舞"), shinGroup.badges)

        val maiGroup = plateCatalog.groups.firstOrNull { it.id == "舞" }
        assertNotNull(maiGroup)
        assertEquals(516, maiGroup!!.basicToMasterSongIds.size)
        assertEquals(59, maiGroup.remasterSongIds.size)
    }

    @Test
    fun testLegacyAccountMigrationAndOAuthCallbackValidation() {
        val legacyAccounts = listOf(
            SavedAccount(username = "alice", password = "pw1"),
            SavedAccount(username = "bob", password = "pw2")
        )
        val migrated = AccountCredentialStore.buildMigratedProfiles(
            legacyAccounts = legacyAccounts,
            currentUsername = "bob",
            currentPassword = "pw2",
            currentCookie = "jwt_token=xyz",
            currentNickname = "ＢＯＢ"
        )
        assertEquals(2, migrated.entries.size)
        val activeId = migrated.activeProfileId
        assertNotNull(activeId)
        val activeEntry = migrated.entries.firstOrNull { it.profile.id == activeId }
        assertNotNull(activeEntry)
        assertEquals(ScoreProvider.DIVING_FISH, activeEntry!!.profile.provider)
        assertEquals(ScoreAuthMethod.DIVING_FISH_PASSWORD, activeEntry.profile.authMethod)
        assertEquals("bob", activeEntry.profile.username)
        assertEquals("ＢＯＢ", activeEntry.profile.nickname)
        assertEquals("ＢＯＢ", activeEntry.profile.displayTitle)
        assertEquals("jwt_token=xyz", activeEntry.secret.cookie)

        // RFC 7636 Appendix B S256 test vector
        val rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals(
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            PKCEHelper.codeChallengeS256(rfcVerifier)
        )

        val validCallback = "maimaidata://oauth/lxns?code=AUTH123&state=STATE_OK"
        assertEquals("AUTH123", MaimaiAPI.parseOAuthCallback(validCallback, expectedState = "STATE_OK"))
        assertThrows(MaimaiAPIException.OAuthStateMismatch::class.java) {
            MaimaiAPI.parseOAuthCallback(validCallback, expectedState = "WRONG_STATE")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testToastStateCancelsPreviousDismissTimerOnRapidSwitches() = runTest {
        val toast = ToastState(this)
        toast.show("已切换至 A", durationMs = 120L)
        assertEquals("已切换至 A", toast.message)
        assertFalse(toast.isError)

        advanceTimeBy(60L)
        runCurrent()
        toast.show("已切换至 B", isError = true, durationMs = 280L)
        assertEquals("已切换至 B", toast.message)
        assertTrue(toast.isError)

        // At 60 + 100 = 160ms, the first 120ms timer would have fired if not cancelled.
        advanceTimeBy(100L)
        runCurrent()
        assertEquals("已切换至 B", toast.message)

        // After the second timer completes, toast clears cleanly.
        advanceTimeBy(200L)
        runCurrent()
        assertNull(toast.message)
    }

    @Test
    fun testOverseasPlateScopesCoverJPAndINTLWithExclusionsAndDynamicFutureKanji() {
        val plateSeedFile = resolveAssetFile("plate_rules_seed.json")
        val plateCatalog = MaimaiAPI.json.decodeFromString<CNPlateCatalog>(plateSeedFile.readText(Charsets.UTF_8))

        fun makeOverseasSong(
            id: String,
            title: String,
            from: String,
            type: String = "SD",
            chartCount: Int = 4
        ): SongData = SongData(
            basicInfo = SongData.BasicInfo(
                artist = "Artist",
                bpm = 150,
                from = from,
                genre = "舞萌",
                isNew = false,
                title = title,
                version = from
            ),
            charts = (0 until chartCount).map {
                SongData.RawChart(
                    charter = "-",
                    notes = if (type == "DX") listOf(100, 10, 10, 10, 5) else listOf(100, 10, 10, 5)
                )
            },
            ds = List(chartCount) { 13.0 },
            oldDs = null,
            id = id,
            level = List(chartCount) { "13" },
            title = title,
            type = type,
            alias = null
        )

        val jingleBells = makeOverseasSong(id = "70", title = "ジングルベル", from = "maimai")
        val trueLove = makeOverseasSong(id = "8", title = "True Love Song", from = "maimai")
        val zenzenzense = makeOverseasSong(id = "853", title = "前前前世", from = "MURASAKi PLUS")
        val hibana = makeOverseasSong(id = "792", title = "ヒバナ", from = "MiLK", chartCount = 5)
        val dxEraRemaster = makeOverseasSong(id = "500", title = "JACKY [Remix]", from = "PiNK", chartCount = 5)
        val circlePlusSong = makeOverseasSong(id = "11800", title = "CiRCLE+ Song", from = "CiRCLE PLUS", type = "DX")
        val magicalSong = makeOverseasSong(id = "11900", title = "MAGiCAL Song", from = "MAGiCAL", type = "DX")

        val jpSongs = listOf(jingleBells, trueLove, zenzenzense, hibana, dxEraRemaster, circlePlusSong, magicalSong)
        val jpScopes = LXNSCatalogConverter.buildPlateScopes(
            region = MaimaiConstants.ServerRegion.JP,
            cnPlateGroups = plateCatalog.groups,
            activeSongs = jpSongs
        )

        val jpShin = jpScopes.firstOrNull { it.id == "真" }!!
        assertTrue(jpShin.contains(trueLove, difficulty = DifficultyType.MASTER))
        assertFalse(jpShin.contains(jingleBells, difficulty = DifficultyType.MASTER))

        val jpMai = jpScopes.firstOrNull { it.id == "舞" }!!
        assertTrue(jpMai.contains(jingleBells, difficulty = DifficultyType.MASTER))
        assertFalse(jpMai.contains(zenzenzense, difficulty = DifficultyType.MASTER))

        val offlineHibana = makeOverseasSong(id = "zeta_std_hibana", title = "ヒバナ", from = "MiLK", chartCount = 5)
        assertTrue(jpMai.contains(hibana, difficulty = DifficultyType.REMASTER))
        assertTrue(jpMai.contains(offlineHibana, difficulty = DifficultyType.REMASTER))
        assertFalse(jpMai.contains(dxEraRemaster, difficulty = DifficultyType.REMASTER))

        assertTrue(jpScopes.any { it.title == "彩 · PRiSM PLUS" })
        assertTrue(jpScopes.any { it.title == "丸 · CiRCLE" })
        assertTrue(jpScopes.any { it.title == "廻 · CiRCLE PLUS" })
        assertTrue(jpScopes.any { it.title == "MAGiCAL" })

        // On INTL before MAGiCAL launches, CiRCLE PLUS has no plate kanji yet
        val intlSongsBeforeMagical = listOf(trueLove, circlePlusSong)
        val intlScopes = LXNSCatalogConverter.buildPlateScopes(
            region = MaimaiConstants.ServerRegion.INTL,
            cnPlateGroups = plateCatalog.groups,
            activeSongs = intlSongsBeforeMagical
        )
        assertEquals("CiRCLE PLUS", intlScopes.lastOrNull()?.title)

        // Once INTL receives MAGiCAL (and Zetaraku assigns a future plate kanji in `abbr`), both promote automatically
        val futureScopes = LXNSCatalogConverter.buildPlateScopes(
            region = MaimaiConstants.ServerRegion.INTL,
            cnPlateGroups = plateCatalog.groups,
            activeSongs = jpSongs,
            zetarakuVersions = listOf(
                ZetarakuVersionEntry(version = "MAGiCAL", abbr = "MAGiCAL (魔)"),
                ZetarakuVersionEntry(version = "MAGiCAL PLUS", abbr = "MAGiCAL+")
            )
        )
        assertTrue(futureScopes.any { it.title == "廻 · CiRCLE PLUS" })
        assertTrue(futureScopes.any { it.title == "魔 · MAGiCAL" })
    }
}
