package dev.sorrowrain.maimaidata

import dev.sorrowrain.maimaidata.models.ChannelBindingStatus
import dev.sorrowrain.maimaidata.models.DivingFishBinding
import dev.sorrowrain.maimaidata.models.DivingFishUploadRecordDTO
import dev.sorrowrain.maimaidata.models.LXNSBinding
import dev.sorrowrain.maimaidata.models.LXNSConfiguration
import dev.sorrowrain.maimaidata.models.LXNSUploadScoreDTO
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.PlayerIdentity
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.models.ScoreProfileSecret
import dev.sorrowrain.maimaidata.models.ScoreProvider
import dev.sorrowrain.maimaidata.models.SelfHostedBinding
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import dev.sorrowrain.maimaidata.models.SyncBackendRecordDTO
import dev.sorrowrain.maimaidata.models.SyncChannelKind
import dev.sorrowrain.maimaidata.models.SyncMode
import dev.sorrowrain.maimaidata.models.SyncPlan
import dev.sorrowrain.maimaidata.models.WritebackChannelStatus
import dev.sorrowrain.maimaidata.models.WritebackSummary
import dev.sorrowrain.maimaidata.services.AccountCredentialStore
import dev.sorrowrain.maimaidata.services.MaimaiAPI
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.services.ScoreSyncEngine
import dev.sorrowrain.maimaidata.services.SongFilter
import dev.sorrowrain.maimaidata.services.SyncDiagnostics
import dev.sorrowrain.maimaidata.services.SyncHistoryStore
import dev.sorrowrain.maimaidata.services.SyncServiceConfig
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class SyncEngineAndIdentityTests {

    @Serializable
    private data class ExpectedRecordSummary(
        val songId: Int,
        val levelIndex: Int,
        val type: String,
        val achievements: Double,
        val fc: String,
        val fs: String,
        val dxScore: Int,
        val ra: Int,
        val rate: String
    )

    @Serializable
    private data class QuickSyncCase(
        val existing: List<ProberRecord>,
        val incoming: List<SyncBackendRecordDTO>,
        val expected: List<ExpectedRecordSummary>
    )

    @Serializable
    private data class FullSyncCase(
        val incoming: List<SyncBackendRecordDTO>,
        val expected: List<ExpectedRecordSummary>
    )

    @Serializable
    private data class DivingFishWritebackCase(
        val musicTitles: Map<String, String>,
        val local: List<ProberRecord>,
        val cloud: List<ProberRecord>,
        val expected: List<DivingFishUploadRecordDTO>
    )

    @Serializable
    private data class LXNSWritebackCase(
        val local: List<ProberRecord>,
        val cloud: List<ProberRecord>,
        val expected: List<LXNSUploadScoreDTO>
    )

    @Serializable
    private data class ExpectedRatingSnapshot(
        val cnRating: Int,
        val b35Rating: Int,
        val b15Rating: Int
    )

    @Serializable
    private data class CNRatingSnapshotCase(
        val records: List<ProberRecord>,
        val expected: ExpectedRatingSnapshot
    )

    @Serializable
    private data class SyncVectorBundle(
        val songs: List<SongData>,
        val quickSync: QuickSyncCase,
        val fullSync: FullSyncCase,
        val divingFishWriteback: DivingFishWritebackCase,
        val lxnsWriteback: LXNSWritebackCase,
        val cnRatingSnapshot: CNRatingSnapshotCase
    )

    private fun resolveVectorFile(): File {
        val candidates = listOf(
            File("shared_test_vectors/sync_engine_vectors.json"),
            File("../shared_test_vectors/sync_engine_vectors.json"),
            File("../../shared_test_vectors/sync_engine_vectors.json"),
            File("../../../shared_test_vectors/sync_engine_vectors.json")
        )
        return candidates.firstOrNull { it.exists() }
            ?: error("Unable to locate shared_test_vectors/sync_engine_vectors.json from ${File(".").absolutePath}")
    }

    private fun loadVectors(): SyncVectorBundle =
        MaimaiAPI.json.decodeFromString(resolveVectorFile().readText(Charsets.UTF_8))

    @Test
    fun testSharedVectorsQuickAndFullSync() {
        val bundle = loadVectors()
        val songsById = bundle.songs.associateBy { it.numericId }

        val quickIncoming = ScoreSyncEngine.normalizeBackendRecords(
            dtos = bundle.quickSync.incoming,
            songsById = songsById,
            region = MaimaiConstants.ServerRegion.CN
        )
        val quickMerged = ScoreSyncEngine.mergeQuickSync(
            existing = bundle.quickSync.existing,
            incoming = quickIncoming
        )
        assertEquals(bundle.quickSync.expected.size, quickMerged.size)
        for ((idx, exp) in bundle.quickSync.expected.withIndex()) {
            val actual = quickMerged[idx]
            assertEquals(exp.songId, actual.songId)
            assertEquals(exp.levelIndex, actual.levelIndex)
            assertEquals(exp.type, actual.type)
            assertEquals(exp.achievements, actual.achievements, 1e-6)
            assertEquals(exp.fc, actual.fc)
            assertEquals(exp.fs, actual.fs)
            assertEquals(exp.dxScore, actual.dxScore)
            assertEquals(exp.ra, actual.computedRating)
            assertEquals(exp.rate, actual.rate)
        }

        val fullIncoming = ScoreSyncEngine.normalizeBackendRecords(
            dtos = bundle.fullSync.incoming,
            songsById = songsById,
            region = MaimaiConstants.ServerRegion.CN
        )
        val fullApplied = ScoreSyncEngine.applyFullSync(fullIncoming)
        assertEquals(bundle.fullSync.expected.size, fullApplied.size)
        for ((idx, exp) in bundle.fullSync.expected.withIndex()) {
            val actual = fullApplied[idx]
            assertEquals(exp.songId, actual.songId)
            assertEquals(exp.levelIndex, actual.levelIndex)
            assertEquals(exp.type, actual.type)
            assertEquals(exp.achievements, actual.achievements, 1e-6)
            assertEquals(exp.fc, actual.fc)
            assertEquals(exp.fs, actual.fs)
            assertEquals(exp.dxScore, actual.dxScore)
            assertEquals(exp.ra, actual.computedRating)
            assertEquals(exp.rate, actual.rate)
        }
    }

    @Test
    fun testSharedVectorsWritebackAndCNRatingSnapshot() {
        val bundle = loadVectors()
        val songsById = bundle.songs.associateBy { it.numericId }
        val dfTitles = bundle.divingFishWriteback.musicTitles.mapKeys { it.key.toInt() }

        assertTrue(
            ScoreSyncEngine.needsDivingFishTitleMap(
                localRecords = bundle.divingFishWriteback.local,
                cloudRecords = bundle.divingFishWriteback.cloud,
                cnSongsById = songsById
            )
        )
        assertFalse(
            ScoreSyncEngine.needsDivingFishTitleMap(
                localRecords = bundle.divingFishWriteback.cloud,
                cloudRecords = bundle.divingFishWriteback.cloud,
                cnSongsById = songsById
            )
        )

        val dfResult = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords = bundle.divingFishWriteback.local,
            cloudRecords = bundle.divingFishWriteback.cloud,
            songsById = songsById,
            cnSongsById = songsById,
            musicTitlesById = dfTitles
        )
        assertEquals(bundle.divingFishWriteback.expected, dfResult.uploads)

        val lxResult = ScoreSyncEngine.buildLXNSWriteback(
            localRecords = bundle.lxnsWriteback.local,
            cloudRecords = bundle.lxnsWriteback.cloud,
            cnSongsById = songsById
        )
        assertEquals(bundle.lxnsWriteback.expected, lxResult.uploads)

        val snapshot = ScoreSyncEngine.computeCNRatingSnapshot(
            records = bundle.cnRatingSnapshot.records,
            songsById = songsById
        )
        assertEquals(bundle.cnRatingSnapshot.expected.cnRating, snapshot.cnRating)
        assertEquals(bundle.cnRatingSnapshot.expected.b35Rating, snapshot.b35Rating)
        assertEquals(bundle.cnRatingSnapshot.expected.b15Rating, snapshot.b15Rating)
    }

    @Test
    fun testMechanicalLegacyProfileUpgradeAndHistoryStore() {
        val profiles = listOf(
            ScoreProfile(
                id = "11111111-1111-1111-1111-111111111111",
                provider = ScoreProvider.LXNS,
                authMethod = ScoreAuthMethod.LXNS_OAUTH,
                accountKey = "oidc_user_1",
                username = "oauthUser",
                nickname = "OAuthPlayer",
                lastSyncedAtEpochMs = 1_700_000_000_000L
            ),
            ScoreProfile(
                id = "22222222-2222-2222-2222-222222222222",
                provider = ScoreProvider.LXNS,
                authMethod = ScoreAuthMethod.LXNS_PERSONAL_TOKEN,
                accountKey = "fc_987654321",
                username = "pt",
                nickname = "PersonalPlayer",
                lastSyncedAtEpochMs = 1_700_000_100_000L
            ),
            ScoreProfile(
                id = "33333333-3333-3333-3333-333333333333",
                provider = ScoreProvider.DIVING_FISH,
                authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD,
                accountKey = "dfUser",
                username = "dfUser",
                nickname = "FishPlayer",
                lastSyncedAtEpochMs = 1_700_000_200_000L
            )
        )
        val secrets = mapOf(
            "11111111-1111-1111-1111-111111111111" to ScoreProfileSecret(
                accessToken = "acc_1",
                refreshToken = "ref_1",
                accessTokenExpiresAtEpochSec = 1_700_003_600L
            ),
            "22222222-2222-2222-2222-222222222222" to ScoreProfileSecret(
                personalToken = "pt_secret"
            ),
            "33333333-3333-3333-3333-333333333333" to ScoreProfileSecret(
                password = "pw",
                cookie = "jwt_token=abc"
            )
        )

        val upgraded = ScoreSyncEngine.upgradeLegacyProfiles(profiles, secrets, nowEpochMs = 1_700_000_999_000L)
        assertEquals(3, upgraded.size)
        assertFalse(upgraded[0].identity.lxns!!.hasWriteScope)
        assertTrue(upgraded[0].identity.needsLXNSReauthForWrite)
        assertEquals("ref_1", upgraded[0].lxns!!.refreshToken)

        assertTrue(upgraded[1].identity.lxns!!.hasWriteScope)
        assertEquals(987654321L, upgraded[1].identity.lxns!!.friendCode)
        assertEquals(LXNSConfiguration.SCOPES, upgraded[1].lxns!!.grantedScopes)

        assertEquals("dfUser", upgraded[2].identity.divingFish!!.username)
        assertEquals("jwt_token=abc", upgraded[2].divingFish!!.cookie)

        val tempDir = Files.createTempDirectory("maimai_hist_test").toFile()
        try {
            val bundle = loadVectors()
            val songsById = bundle.songs.associateBy { it.numericId }
            val store = SyncHistoryStore(tempDir)
            val id = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

            store.appendSnapshot(id, SyncMode.QUICK, bundle.cnRatingSnapshot.records, songsById, timestampEpochMs = 100L)
            store.appendSnapshot(id, SyncMode.FULL, bundle.cnRatingSnapshot.records, songsById, timestampEpochMs = 200L)
            store.appendSnapshot(id, SyncMode.FULL, bundle.cnRatingSnapshot.records, songsById, timestampEpochMs = 300L)

            // Inject a corrupt JSONL line to confirm loadHistory skips malformed entries safely.
            store.historyFile(id).appendText("not-valid-json\n", Charsets.UTF_8)

            val entries = store.loadHistory(id)
            assertEquals(3, entries.size)
            assertFalse(entries[0].isBadgeBaseline)
            assertTrue(entries[1].isBadgeBaseline)
            assertFalse(entries[2].isBadgeBaseline)
            assertEquals(911, entries[1].cnRating)
            assertEquals(4, entries[1].playedSongsCount)

            store.deleteHistory(id)
            assertTrue(store.loadHistory(id).isEmpty())
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testUploadRetryPolicyAndBuddyProjection() {
        assertTrue(MaimaiAPI.shouldRetryUploadLogin(MaimaiAPIException.Unauthorized("expired")))
        assertTrue(MaimaiAPI.shouldRetryUploadLogin(MaimaiAPIException.HttpError(401, null)))
        assertTrue(MaimaiAPI.shouldRetryUploadLogin(MaimaiAPIException.HttpError(403, null)))
        assertFalse(MaimaiAPI.shouldRetryUploadLogin(MaimaiAPIException.HttpError(400, "invalid payload")))

        val bundle = loadVectors()
        val songsById = bundle.songs.associateBy { it.numericId }
        val stdSong = songsById[8]!!
        val apRec = ProberRecord(
            achievements = 100.5,
            ds = 12.4,
            fc = "ap",
            levelIndex = 3,
            songId = 8,
            title = "True Love Song",
            type = "SD"
        )
        assertEquals(279, apRec.projected(stdSong, MaimaiConstants.ServerRegion.CN).computedRating)
        assertEquals(280, apRec.projected(stdSong, MaimaiConstants.ServerRegion.JP).computedRating)

        val utageSong = songsById[110281]!!
        assertEquals(1, utageSong.chartArrayIndex(11))
        val buddy2P = ProberRecord(
            achievements = 101.0,
            ds = 13.0,
            fc = "app",
            levelIndex = 11,
            songId = 110281,
            title = "[協]ダブルラリアット",
            type = "utage"
        )
        assertEquals(0, buddy2P.projected(utageSong, MaimaiConstants.ServerRegion.JP).computedRating)
    }

    @Test
    fun testLenientBackendDTOsNormalizeCachedUtageAndINTLProjection() {
        // 1. Lenient decode with omitted optional fields
        val minimalRespJson = """
            {
              "mode": "quick",
              "profile": { "userId": 888999 },
              "records": [
                { "songId": 8, "levelIndex": 3, "achievements": 100.5 }
              ]
            }
        """.trimIndent()
        val decodedResp = MaimaiAPI.json.decodeFromString<dev.sorrowrain.maimaidata.models.SyncBackendResponse>(minimalRespJson)
        assertEquals(1, decodedResp.records.size)
        assertEquals(0, decodedResp.records[0].dxScore)
        assertEquals(0L, decodedResp.syncedAtUnixMs)

        val minimalErrJson = """{ "error": {} }"""
        val decodedErr = MaimaiAPI.json.decodeFromString<dev.sorrowrain.maimaidata.models.SyncBackendErrorEnvelope>(minimalErrJson)
        assertEquals("", decodedErr.error?.code)
        assertEquals("", decodedErr.error?.message)

        // 2. normalizeCachedRecords merges colliding legacy (_0) & canonical (_10) Utage records
        val legacyUtage = ProberRecord(
            achievements = 98.5,
            ds = 13.0,
            fc = "fc",
            fs = "",
            level = "13?",
            levelIndex = 0,
            ra = 250,
            rate = "SSS",
            songId = 110281,
            title = "[協]ダブルラリアット",
            type = "DX",
            dxScore = 1500
        )
        val canonicalUtage = ProberRecord(
            achievements = 100.2,
            ds = 0.0,
            fc = "",
            fs = "fsd",
            level = "13?",
            levelIndex = 10,
            ra = 0,
            rate = "SSS+",
            songId = 110281,
            title = "[協]ダブルラリアット",
            type = "utage",
            dxScore = 1800
        )
        val normalized = ScoreSyncEngine.normalizeCachedRecords(listOf(legacyUtage, canonicalUtage))
        assertEquals(1, normalized.size)
        assertEquals(10, normalized[0].levelIndex)
        assertEquals("utage", normalized[0].type)
        assertEquals(0, normalized[0].computedRating)
        assertEquals(100.2, normalized[0].achievements, 1e-6)
        assertEquals("fc", normalized[0].fc)
        assertEquals("fsd", normalized[0].fs)
        assertEquals(1800, normalized[0].dxScore)

        // 3. INTL projection preserves no-AP-bonus rule without mutating base record
        val bundle = loadVectors()
        val songsById = bundle.songs.associateBy { it.numericId }
        val stdSong = songsById[8]!!
        val baseAp = ProberRecord(
            achievements = 100.5,
            ds = 12.4,
            fc = "ap",
            levelIndex = 3,
            songId = 8,
            title = "True Love Song",
            type = "SD"
        )
        val intlProjected = baseAp.projected(stdSong, MaimaiConstants.ServerRegion.INTL)
        assertEquals(280, intlProjected.computedRating)
        assertEquals(12.4, intlProjected.ds, 1e-6)
        assertEquals(279, baseAp.computedRating)
        assertEquals(12.4, baseAp.ds, 1e-6)
    }

    @Test
    fun testSyncServiceConfigResolution() {
        assertFalse(SyncServiceConfig.resolve(hostOrUrl = "", accessKey = "key").isConfigured)
        assertFalse(SyncServiceConfig.resolve(hostOrUrl = "sync.example.com", accessKey = "  ").isConfigured)

        val prod = SyncServiceConfig.resolve(hostOrUrl = "sync.example.com", accessKey = "abc")
        assertTrue(prod.isConfigured)
        assertEquals("https://sync.example.com", prod.serverBaseURL)
        assertEquals("abc", prod.accessKey)

        val local = SyncServiceConfig.resolve(hostOrUrl = "localhost:8080", accessKey = "abc")
        assertEquals("http://localhost:8080", local.serverBaseURL)

        val loopback = SyncServiceConfig.resolve(hostOrUrl = "127.0.0.1:8080", accessKey = "abc")
        assertEquals("http://127.0.0.1:8080", loopback.serverBaseURL)

        val explicit = SyncServiceConfig.resolve(hostOrUrl = "https://alt.example.com:8443", accessKey = "abc")
        assertEquals("https://alt.example.com:8443", explicit.serverBaseURL)
    }

    @Test
    fun testPlayerIdentityBindingAndUploadTargets() {
        val empty = PlayerIdentity()
        assertEquals("未绑定", empty.boundChannelsSummary)
        assertEquals(ChannelBindingStatus.UNAVAILABLE, empty.bindingStatus(SyncChannelKind.SELF_HOSTED, cnDirectAvailable = false))
        assertEquals(ChannelBindingStatus.UNBOUND, empty.bindingStatus(SyncChannelKind.SELF_HOSTED, cnDirectAvailable = true))
        assertEquals(ChannelBindingStatus.UNBOUND, empty.bindingStatus(SyncChannelKind.LXNS, cnDirectAvailable = true))
        assertEquals(ChannelBindingStatus.UNBOUND, empty.bindingStatus(SyncChannelKind.DIVING_FISH, cnDirectAvailable = true))
        assertTrue(empty.uploadTargets.isEmpty())

        val readOnlyIdentity = PlayerIdentity(
            selfHosted = SelfHostedBinding(externalUserId = 1001L, displayName = "国服号", playerRating = 15000, boundAtEpochMs = 1000L),
            lxns = LXNSBinding(authMethod = ScoreAuthMethod.LXNS_OAUTH, accountKey = "oidc_1", displayName = "落雪号", friendCode = null, hasWriteScope = false, boundAtEpochMs = 1000L)
        )
        assertEquals(ChannelBindingStatus.BOUND, readOnlyIdentity.bindingStatus(SyncChannelKind.SELF_HOSTED, cnDirectAvailable = true))
        assertEquals(ChannelBindingStatus.NEEDS_REAUTH, readOnlyIdentity.bindingStatus(SyncChannelKind.LXNS, cnDirectAvailable = true))
        assertEquals("国服直连 · 落雪（需重新授权）", readOnlyIdentity.boundChannelsSummary)
        assertTrue(readOnlyIdentity.uploadTargets.isEmpty())

        val fullIdentity = PlayerIdentity(
            selfHosted = SelfHostedBinding(externalUserId = 1001L, displayName = "国服号", playerRating = 15000, boundAtEpochMs = 1000L),
            lxns = LXNSBinding(authMethod = ScoreAuthMethod.LXNS_OAUTH, accountKey = "oidc_1", displayName = "落雪号", friendCode = null, hasWriteScope = true, boundAtEpochMs = 1000L),
            divingFish = DivingFishBinding(authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD, accountKey = "df1", username = "df1", nickname = "水鱼号", boundAtEpochMs = 1000L)
        )
        assertEquals(ChannelBindingStatus.BOUND, fullIdentity.bindingStatus(SyncChannelKind.LXNS, cnDirectAvailable = true))
        assertEquals(ChannelBindingStatus.BOUND, fullIdentity.bindingStatus(SyncChannelKind.DIVING_FISH, cnDirectAvailable = true))
        assertEquals("国服直连 · 落雪 · 水鱼", fullIdentity.boundChannelsSummary)
        assertEquals(listOf(SyncChannelKind.LXNS, SyncChannelKind.DIVING_FISH), fullIdentity.uploadTargets)
    }

    @Test
    fun testPlayerIdentityLastSyncTextFormatting() {
        val tz = TimeZone.getTimeZone("Asia/Shanghai")
        fun epochMs(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            Calendar.getInstance(tz, Locale.ROOT).apply {
                set(year, month - 1, day, hour, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis

        val nowMs = epochMs(2026, 5, 20, 15, 0)
        assertNull(PlayerIdentity(lastSyncedAtEpochMs = null).lastSyncText(nowMs = nowMs, timeZone = tz))
        assertNull(PlayerIdentity(lastSyncedAtEpochMs = 0L).lastSyncText(nowMs = nowMs, timeZone = tz))

        val todayIdentity = PlayerIdentity(lastSyncedAtEpochMs = epochMs(2026, 5, 20, 9, 5))
        assertEquals("上次同步 今天 09:05", todayIdentity.lastSyncText(nowMs = nowMs, timeZone = tz))

        val yesterdayIdentity = PlayerIdentity(lastSyncedAtEpochMs = epochMs(2026, 5, 19, 23, 45))
        assertEquals("上次同步 昨天 23:45", yesterdayIdentity.lastSyncText(nowMs = nowMs, timeZone = tz))

        val olderIdentity = PlayerIdentity(lastSyncedAtEpochMs = epochMs(2026, 5, 2, 8, 30))
        assertEquals("上次同步 05-02 08:30", olderIdentity.lastSyncText(nowMs = nowMs, timeZone = tz))
    }

    @Test
    fun testWritebackSummaryText() {
        assertEquals("", WritebackSummary(results = emptyList()).summaryText)
        val mixed = WritebackSummary(
            results = listOf(
                WritebackChannelStatus(channel = SyncChannelKind.LXNS, uploadedCount = 12, skippedCount = 0, errorMessage = null),
                WritebackChannelStatus(channel = SyncChannelKind.DIVING_FISH, uploadedCount = 0, skippedCount = 0, errorMessage = null)
            )
        )
        assertTrue(mixed.isAllSucceeded)
        assertTrue(mixed.hasAnySucceeded)
        assertEquals("上传成功", mixed.summaryText)

        val failed = WritebackSummary(
            results = listOf(
                WritebackChannelStatus(channel = SyncChannelKind.DIVING_FISH, uploadedCount = 0, skippedCount = 0, errorMessage = "网络超时")
            )
        )
        assertFalse(failed.isAllSucceeded)
        assertFalse(failed.hasAnySucceeded)
        assertEquals("水鱼上传失败：网络超时", failed.summaryText)

        val withSkipped = WritebackSummary(
            results = listOf(
                WritebackChannelStatus(channel = SyncChannelKind.LXNS, uploadedCount = 5, skippedCount = 2, errorMessage = null),
                WritebackChannelStatus(channel = SyncChannelKind.DIVING_FISH, uploadedCount = 0, skippedCount = 1, errorMessage = null)
            )
        )
        assertTrue(withSkipped.isAllSucceeded)
        assertEquals("上传成功", withSkipped.summaryText)

        val partial = WritebackSummary(
            results = listOf(
                WritebackChannelStatus(channel = SyncChannelKind.LXNS, uploadedCount = 3, skippedCount = 0, errorMessage = null),
                WritebackChannelStatus(channel = SyncChannelKind.DIVING_FISH, uploadedCount = 0, skippedCount = 0, errorMessage = "网络超时")
            )
        )
        assertFalse(partial.isAllSucceeded)
        assertTrue(partial.hasAnySucceeded)
        assertEquals("落雪上传成功，水鱼上传失败：网络超时", partial.summaryText)
    }

    @Test
    fun testIdentityB50NameSyncPlanAndUploadEligibility() {
        val empty = PlayerIdentity(label = "备用档案")
        assertEquals("备用档案", empty.primaryDisplayName)
        assertEquals("备用档案", empty.b50PlayerName)
        assertEquals(SyncPlan.None, empty.syncPlan)
        assertFalse(empty.canUploadToProbers)

        val lxnsOnly = PlayerIdentity(
            label = "备用档案",
            customNickname = "  B50昵称  ",
            lxns = LXNSBinding(
                authMethod = ScoreAuthMethod.LXNS_OAUTH,
                accountKey = "o1",
                displayName = "落雪号",
                friendCode = null,
                hasWriteScope = true,
                boundAtEpochMs = 1000L
            )
        )
        assertEquals("落雪号", lxnsOnly.primaryDisplayName)
        assertEquals("B50昵称", lxnsOnly.b50PlayerName)
        assertEquals(SyncPlan.SingleProber(SyncChannelKind.LXNS), lxnsOnly.syncPlan)
        assertFalse(lxnsOnly.canUploadToProbers)

        val dfOnly = PlayerIdentity(
            divingFish = DivingFishBinding(
                authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD,
                accountKey = "d1",
                username = "d1",
                nickname = "水鱼号",
                boundAtEpochMs = 1000L
            )
        )
        assertEquals(SyncPlan.SingleProber(SyncChannelKind.DIVING_FISH), dfOnly.syncPlan)
        assertFalse(dfOnly.canUploadToProbers)

        val dualProber = PlayerIdentity(lxns = lxnsOnly.lxns, divingFish = dfOnly.divingFish)
        assertEquals(SyncPlan.MergedProbers, dualProber.syncPlan)
        assertTrue(dualProber.canUploadToProbers)

        val readOnlyDual = PlayerIdentity(
            lxns = LXNSBinding(
                authMethod = ScoreAuthMethod.LXNS_OAUTH,
                accountKey = "o1",
                displayName = "落雪号",
                friendCode = null,
                hasWriteScope = false,
                boundAtEpochMs = 1000L
            ),
            divingFish = dfOnly.divingFish
        )
        assertEquals(SyncPlan.MergedProbers, readOnlyDual.syncPlan)
        assertEquals(listOf(SyncChannelKind.DIVING_FISH), readOnlyDual.uploadTargets)
        assertTrue(readOnlyDual.canUploadToProbers)

        val cnReadOnlyLXNS = PlayerIdentity(
            selfHosted = SelfHostedBinding(externalUserId = 1001L, displayName = "国服号", playerRating = 15000, boundAtEpochMs = 1000L),
            lxns = readOnlyDual.lxns
        )
        assertEquals(SyncPlan.CNDirectQuick, cnReadOnlyLXNS.syncPlan)
        assertFalse(cnReadOnlyLXNS.canUploadToProbers)

        val cnWriteLXNS = PlayerIdentity(
            selfHosted = cnReadOnlyLXNS.selfHosted,
            lxns = lxnsOnly.lxns
        )
        assertEquals(SyncPlan.CNDirectQuick, cnWriteLXNS.syncPlan)
        assertTrue(cnWriteLXNS.canUploadToProbers)
    }

    @Test
    fun testPureCNWritebackAndSnapshotFiltering() {
        val bundle = loadVectors()
        val activeCandidates = bundle.songs.filter { !it.isUtage && it.ds.size >= 2 }
        assertTrue(activeCandidates.size >= 2)
        val activeSong = activeCandidates[0]
        val disabledSong = activeCandidates[1].copy(isDisabled = true)

        val cnSongsById: Map<Int, SongData> = mapOf(
            activeSong.numericId to activeSong,
            disabledSong.numericId to disabledSong
        )
        val activeIdx = activeSong.ds.size - 1
        val disabledIdx = disabledSong.ds.size - 1
        val unknownDeletedId = 181

        val zeroRecord = ProberRecord(
            achievements = 0.0,
            ds = activeSong.ds[0],
            level = activeSong.level[0],
            levelIndex = 0,
            rate = "d",
            songId = activeSong.numericId,
            title = activeSong.title,
            type = activeSong.type
        )
        val activeRecord = ProberRecord(
            achievements = 100.5,
            ds = activeSong.ds[activeIdx],
            fc = "fc",
            level = activeSong.level[activeIdx],
            levelIndex = activeIdx,
            rate = "sssp",
            songId = activeSong.numericId,
            title = activeSong.title,
            type = activeSong.type
        )
        val disabledRecord = ProberRecord(
            achievements = 100.2,
            ds = disabledSong.ds[disabledIdx],
            level = disabledSong.level[disabledIdx],
            levelIndex = disabledIdx,
            rate = "sss",
            songId = disabledSong.numericId,
            title = disabledSong.title,
            type = disabledSong.type
        )
        val deletedRecord = ProberRecord(
            achievements = 99.8,
            ds = 13.5,
            level = "13+",
            levelIndex = 3,
            rate = "sssp",
            songId = unknownDeletedId,
            title = "Deleted Song",
            type = MaimaiConstants.CHART_TYPE_SD
        )

        assertFalse(ScoreSyncEngine.isEligibleForCNProberUpload(zeroRecord, cnSongsById))
        assertFalse(ScoreSyncEngine.isEligibleForCNProberUpload(disabledRecord, cnSongsById))
        assertFalse(ScoreSyncEngine.isEligibleForCNProberUpload(deletedRecord, cnSongsById))
        assertTrue(ScoreSyncEngine.isEligibleForCNProberUpload(activeRecord, cnSongsById))

        val records = listOf(zeroRecord, activeRecord, disabledRecord, deletedRecord)
        val lxnsResult = ScoreSyncEngine.buildLXNSWriteback(
            localRecords = records,
            cloudRecords = emptyList(),
            cnSongsById = cnSongsById
        )
        assertEquals(1, lxnsResult.uploads.size)
        assertEquals(2, lxnsResult.skippedDeletedCount)
        assertTrue(disabledSong.numericId in lxnsResult.sampleSkippedSongIds)
        assertTrue(unknownDeletedId in lxnsResult.sampleSkippedSongIds)

        val dfResult = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords = records,
            cloudRecords = emptyList(),
            songsById = cnSongsById,
            cnSongsById = cnSongsById,
            musicTitlesById = mapOf(activeSong.numericId to activeSong.title)
        )
        assertEquals(1, dfResult.uploads.size)
        assertEquals(activeSong.title, dfResult.uploads[0].title)
        assertEquals(2, dfResult.skippedDeletedCount)

        val snapAll = ScoreSyncEngine.computeCNRatingSnapshot(records, cnSongsById)
        val snapActive = ScoreSyncEngine.computeCNRatingSnapshot(listOf(activeRecord), cnSongsById)
        assertEquals(snapActive, snapAll)
        assertTrue(snapAll.cnRating > 0)
    }

    @Test
    fun testLegacyB50NicknameMigration() {
        val initial = listOf(
            PlayerIdentity(label = "档案1", customNickname = ""),
            PlayerIdentity(label = "档案2", customNickname = "已有名称")
        )

        val skipped = ScoreSyncEngine.migrateLegacyB50Nickname(
            identities = initial,
            legacyUseProberNickname = true,
            legacyCustomNickname = "旧昵称"
        )
        assertFalse(skipped.didChange)
        assertEquals("", skipped.identities[0].customNickname)

        val migrated = ScoreSyncEngine.migrateLegacyB50Nickname(
            identities = initial,
            legacyUseProberNickname = false,
            legacyCustomNickname = "  新B50名  "
        )
        assertTrue(migrated.didChange)
        assertEquals("新B50名", migrated.identities[0].customNickname)
        assertEquals("已有名称", migrated.identities[1].customNickname)
    }

    @Test
    fun testSongFilterAlwaysHidesDisabledSongs() {
        val activeSong = loadVectors().songs.first { !it.isUtage }
        val disabledSong = activeSong.copy(
            id = "181",
            title = "Deleted Track",
            isDisabled = true
        )

        val filtered = SongFilter.apply(
            songs = listOf(activeSong, disabledSong),
            criteria = SongFilterCriteria(),
            region = MaimaiConstants.ServerRegion.CN
        )
        assertEquals(listOf(activeSong.numericId), filtered.map { it.numericId })
    }

    @Test
    fun testFailClosedWritebackWhenCNCatalogEmpty() {
        val bundle = loadVectors()
        val songsById = bundle.songs.associateBy { it.numericId }
        val localRecord = ProberRecord(
            achievements = 100.5,
            ds = 12.4,
            fc = "ap",
            levelIndex = 3,
            songId = 8,
            title = "True Love Song",
            type = "SD"
        )
        val emptyCatalog = emptyMap<Int, SongData>()

        assertFalse(
            ScoreSyncEngine.isEligibleForCNProberUpload(record = localRecord, cnSongsById = emptyCatalog)
        )
        assertFalse(
            ScoreSyncEngine.needsDivingFishTitleMap(
                localRecords = listOf(localRecord),
                cloudRecords = emptyList(),
                cnSongsById = emptyCatalog
            )
        )

        val lxResult = ScoreSyncEngine.buildLXNSWriteback(
            localRecords = listOf(localRecord),
            cloudRecords = emptyList(),
            cnSongsById = emptyCatalog
        )
        assertTrue(lxResult.uploads.isEmpty())
        assertEquals(1, lxResult.skippedDeletedCount)

        val dfResult = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords = listOf(localRecord),
            cloudRecords = emptyList(),
            songsById = songsById,
            cnSongsById = emptyCatalog,
            musicTitlesById = mapOf(8 to "True Love Song")
        )
        assertTrue(dfResult.uploads.isEmpty())
        assertEquals(1, dfResult.skippedDeletedCount)
    }

    @Test
    fun testPlayerIdentityLossyDecodeAndLegacyFields() {
        val rawJson = """
            [
              {
                "id": "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
                "label": "主号",
                "lastSyncMode": "future_mode"
              },
              {
                "bad": 1
              }
            ]
        """.trimIndent()
        val recovered = AccountCredentialStore.decodeIdentitiesLossy(rawJson)
        assertTrue(recovered != null && recovered.size == 1)
        val item = recovered!![0]
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", item.id)
        assertEquals("主号", item.label)
        assertEquals("", item.customNickname)
        assertFalse(item.hasMixedScoreSources)
        assertNull(item.lastSyncMode)
    }

    @Test
    fun testSyncHistoryStoreRepairsTornLineBeforeAppending() {
        val tempDir = Files.createTempDirectory("maimai_hist_torn").toFile()
        try {
            val bundle = loadVectors()
            val songsById = bundle.songs.associateBy { it.numericId }
            val store = SyncHistoryStore(tempDir)
            val id = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

            store.historyFile(id).writeText("{\"broken\":1", Charsets.UTF_8)
            store.appendSnapshot(id, SyncMode.QUICK, bundle.cnRatingSnapshot.records, songsById, timestampEpochMs = 500L)

            val entries = store.loadHistory(id)
            assertEquals(1, entries.size)
            assertEquals(500L, entries[0].timestampEpochMs)
            assertEquals(911, entries[0].cnRating)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    @Test
    fun testSyncDiagnosticsRedactsSensitiveTokens() {
        val raw = "err\nSGWCMAIDabc123def Bearer tok_abc.def jwt_token=sec_val token=mysecret123 cookie:sid999 hash=0123456789abcdef0123456789abcdef"
        val sanitized = SyncDiagnostics.sanitize(raw, limit = 400)
        assertFalse(sanitized.contains("\n"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertFalse(sanitized.contains("SGWCMAIDabc123def"))
        assertFalse(sanitized.contains("tok_abc.def"))
        assertFalse(sanitized.contains("sec_val"))
        assertFalse(sanitized.contains("mysecret123"))
        assertFalse(sanitized.contains("sid999"))
        assertFalse(sanitized.contains("0123456789abcdef0123456789abcdef"))
    }
}
