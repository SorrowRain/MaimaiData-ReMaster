import XCTest
@testable import MaimaiData

final class SyncEngineAndIdentityTests: XCTestCase {
    private struct ExpectedRecordSummary: Decodable {
        let songId: Int
        let levelIndex: Int
        let type: String
        let achievements: Double
        let fc: String
        let fs: String
        let dxScore: Int
        let ra: Int
        let rate: String
    }

    private struct QuickSyncCase: Decodable {
        let existing: [ProberRecord]
        let incoming: [SyncBackendRecordDTO]
        let expected: [ExpectedRecordSummary]
    }

    private struct FullSyncCase: Decodable {
        let incoming: [SyncBackendRecordDTO]
        let expected: [ExpectedRecordSummary]
    }

    private struct DivingFishWritebackCase: Decodable {
        let musicTitles: [String: String]
        let local: [ProberRecord]
        let cloud: [ProberRecord]
        let expected: [DivingFishUploadRecordDTO]
    }

    private struct LXNSWritebackCase: Decodable {
        let local: [ProberRecord]
        let cloud: [ProberRecord]
        let expected: [LXNSUploadScoreDTO]
    }

    private struct ExpectedRatingSnapshot: Decodable {
        let cnRating: Int
        let b35Rating: Int
        let b15Rating: Int
    }

    private struct CNRatingSnapshotCase: Decodable {
        let records: [ProberRecord]
        let expected: ExpectedRatingSnapshot
    }

    private struct SyncVectorBundle: Decodable {
        let songs: [SongData]
        let quickSync: QuickSyncCase
        let fullSync: FullSyncCase
        let divingFishWriteback: DivingFishWritebackCase
        let lxnsWriteback: LXNSWritebackCase
        let cnRatingSnapshot: CNRatingSnapshotCase
    }

    private func loadVectors() throws -> SyncVectorBundle {
        let testFileURL = URL(fileURLWithPath: #filePath)
        let repoRoot = testFileURL
            .deletingLastPathComponent() // MaimaiDataTests
            .deletingLastPathComponent() // MaimaiData-iOS
            .deletingLastPathComponent() // MaimaiData
        let vectorURL = repoRoot
            .appendingPathComponent("shared_test_vectors", isDirectory: true)
            .appendingPathComponent("sync_engine_vectors.json")
        let data = try Data(contentsOf: vectorURL)
        return try JSONDecoder().decode(SyncVectorBundle.self, from: data)
    }

    func testSharedVectorsQuickAndFullSync() throws {
        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })

        let quickIncoming = ScoreSyncEngine.normalizeBackendRecords(
            bundle.quickSync.incoming,
            songsById: songsById,
            region: .cn
        )
        let quickMerged = ScoreSyncEngine.mergeQuickSync(
            existing: bundle.quickSync.existing,
            incoming: quickIncoming
        )
        XCTAssertEqual(quickMerged.count, bundle.quickSync.expected.count)
        for (idx, exp) in bundle.quickSync.expected.enumerated() {
            let actual = quickMerged[idx]
            XCTAssertEqual(actual.songId, exp.songId)
            XCTAssertEqual(actual.levelIndex, exp.levelIndex)
            XCTAssertEqual(actual.type, exp.type)
            XCTAssertEqual(actual.achievements, exp.achievements, accuracy: 1e-6)
            XCTAssertEqual(actual.fc, exp.fc)
            XCTAssertEqual(actual.fs, exp.fs)
            XCTAssertEqual(actual.dxScore, exp.dxScore)
            XCTAssertEqual(actual.computedRating, exp.ra)
            XCTAssertEqual(actual.rate, exp.rate)
        }

        let fullIncoming = ScoreSyncEngine.normalizeBackendRecords(
            bundle.fullSync.incoming,
            songsById: songsById,
            region: .cn
        )
        let fullApplied = ScoreSyncEngine.applyFullSync(incoming: fullIncoming)
        XCTAssertEqual(fullApplied.count, bundle.fullSync.expected.count)
        for (idx, exp) in bundle.fullSync.expected.enumerated() {
            let actual = fullApplied[idx]
            XCTAssertEqual(actual.songId, exp.songId)
            XCTAssertEqual(actual.levelIndex, exp.levelIndex)
            XCTAssertEqual(actual.type, exp.type)
            XCTAssertEqual(actual.achievements, exp.achievements, accuracy: 1e-6)
            XCTAssertEqual(actual.fc, exp.fc)
            XCTAssertEqual(actual.fs, exp.fs)
            XCTAssertEqual(actual.dxScore, exp.dxScore)
            XCTAssertEqual(actual.computedRating, exp.ra)
            XCTAssertEqual(actual.rate, exp.rate)
        }
    }

    func testSharedVectorsWritebackAndCNRatingSnapshot() throws {
        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })
        let dfTitles = Dictionary(uniqueKeysWithValues: bundle.divingFishWriteback.musicTitles.compactMap { k, v in
            Int(k).map { ($0, v) }
        })

        XCTAssertTrue(
            ScoreSyncEngine.needsDivingFishTitleMap(
                localRecords: bundle.divingFishWriteback.local,
                cloudRecords: bundle.divingFishWriteback.cloud,
                cnSongsById: songsById
            )
        )
        XCTAssertFalse(
            ScoreSyncEngine.needsDivingFishTitleMap(
                localRecords: bundle.divingFishWriteback.cloud,
                cloudRecords: bundle.divingFishWriteback.cloud,
                cnSongsById: songsById
            )
        )

        let dfResult = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords: bundle.divingFishWriteback.local,
            cloudRecords: bundle.divingFishWriteback.cloud,
            songsById: songsById,
            cnSongsById: songsById,
            musicTitlesById: dfTitles
        )
        XCTAssertEqual(dfResult.uploads, bundle.divingFishWriteback.expected)

        let lxResult = ScoreSyncEngine.buildLXNSWriteback(
            localRecords: bundle.lxnsWriteback.local,
            cloudRecords: bundle.lxnsWriteback.cloud,
            cnSongsById: songsById
        )
        XCTAssertEqual(lxResult.uploads, bundle.lxnsWriteback.expected)

        let snapshot = ScoreSyncEngine.computeCNRatingSnapshot(
            records: bundle.cnRatingSnapshot.records,
            songsById: songsById
        )
        XCTAssertEqual(snapshot.cnRating, bundle.cnRatingSnapshot.expected.cnRating)
        XCTAssertEqual(snapshot.b35Rating, bundle.cnRatingSnapshot.expected.b35Rating)
        XCTAssertEqual(snapshot.b15Rating, bundle.cnRatingSnapshot.expected.b15Rating)
    }

    func testMechanicalLegacyProfileUpgradeAndHistoryStore() throws {
        let id1 = UUID(uuidString: "11111111-1111-1111-1111-111111111111")!
        let id2 = UUID(uuidString: "22222222-2222-2222-2222-222222222222")!
        let id3 = UUID(uuidString: "33333333-3333-3333-3333-333333333333")!

        let profiles = [
            ScoreProfile(
                id: id1,
                provider: .lxns,
                authMethod: .lxnsOAuth,
                accountKey: "oidc_user_1",
                username: "oauthUser",
                nickname: "OAuthPlayer",
                lastSyncedAt: Date(timeIntervalSince1970: 1_700_000_000)
            ),
            ScoreProfile(
                id: id2,
                provider: .lxns,
                authMethod: .lxnsPersonalToken,
                accountKey: "fc_987654321",
                username: "pt",
                nickname: "PersonalPlayer",
                lastSyncedAt: Date(timeIntervalSince1970: 1_700_000_100)
            ),
            ScoreProfile(
                id: id3,
                provider: .divingFish,
                authMethod: .divingFishPassword,
                accountKey: "dfUser",
                username: "dfUser",
                nickname: "FishPlayer",
                lastSyncedAt: Date(timeIntervalSince1970: 1_700_000_200)
            )
        ]

        let secrets: [UUID: ScoreProfileSecret] = [
            id1: ScoreProfileSecret(
                accessToken: "acc_1",
                refreshToken: "ref_1",
                accessTokenExpiresAt: 1_700_003_600
            ),
            id2: ScoreProfileSecret(personalToken: "pt_secret"),
            id3: ScoreProfileSecret(password: "pw", cookie: "jwt_token=abc")
        ]

        let upgraded = ScoreSyncEngine.upgradeLegacyProfiles(
            profiles,
            secretsById: secrets,
            nowEpochMs: 1_700_000_999_000
        )
        XCTAssertEqual(upgraded.count, 3)
        XCTAssertEqual(upgraded[0].identity.lxns?.hasWriteScope, false)
        XCTAssertTrue(upgraded[0].identity.needsLXNSReauthForWrite)
        XCTAssertEqual(upgraded[0].lxns?.refreshToken, "ref_1")

        XCTAssertEqual(upgraded[1].identity.lxns?.hasWriteScope, true)
        XCTAssertEqual(upgraded[1].identity.lxns?.friendCode, 987_654_321)
        XCTAssertEqual(upgraded[1].lxns?.grantedScopes, LXNSConfiguration.scopes)

        XCTAssertEqual(upgraded[2].identity.divingFish?.username, "dfUser")
        XCTAssertEqual(upgraded[2].df?.cookie, "jwt_token=abc")

        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("maimai_hist_test_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })
        let store = SyncHistoryStore(directoryURL: tempDir)
        let targetId = "AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE"

        try store.appendSnapshot(
            identityId: targetId,
            syncMode: .quick,
            records: bundle.cnRatingSnapshot.records,
            songsById: songsById,
            timestampEpochMs: 100
        )
        try store.appendSnapshot(
            identityId: targetId,
            syncMode: .full,
            records: bundle.cnRatingSnapshot.records,
            songsById: songsById,
            timestampEpochMs: 200
        )
        try store.appendSnapshot(
            identityId: targetId,
            syncMode: .full,
            records: bundle.cnRatingSnapshot.records,
            songsById: songsById,
            timestampEpochMs: 300
        )

        let histFile = store.historyFileURL(for: targetId)
        let handle = try FileHandle(forWritingTo: histFile)
        try handle.seekToEnd()
        try handle.write(contentsOf: Data("not-valid-json\n".utf8))
        try handle.close()

        let entries = store.loadHistory(for: targetId)
        XCTAssertEqual(entries.count, 3)
        XCTAssertFalse(entries[0].isBadgeBaseline)
        XCTAssertTrue(entries[1].isBadgeBaseline)
        XCTAssertFalse(entries[2].isBadgeBaseline)
        XCTAssertEqual(entries[1].cnRating, 911)
        XCTAssertEqual(entries[1].playedSongsCount, 4)

        try store.deleteHistory(for: targetId)
        XCTAssertTrue(store.loadHistory(for: targetId).isEmpty)
    }

    func testUploadRetryPolicyAndBuddyProjection() throws {
        XCTAssertTrue(MaimaiAPI.shouldRetryUploadLogin(after: MaimaiAPIError.unauthorized("expired")))
        XCTAssertTrue(MaimaiAPI.shouldRetryUploadLogin(after: MaimaiAPIError.httpError(401, nil)))
        XCTAssertTrue(MaimaiAPI.shouldRetryUploadLogin(after: MaimaiAPIError.httpError(403, nil)))
        XCTAssertFalse(MaimaiAPI.shouldRetryUploadLogin(after: MaimaiAPIError.httpError(400, "invalid payload")))

        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })
        let stdSong = try XCTUnwrap(songsById[8])
        let apRec = ProberRecord(
            achievements: 100.5,
            ds: 12.4,
            fc: "ap",
            levelIndex: 3,
            songId: 8,
            title: "True Love Song",
            type: "SD"
        )
        XCTAssertEqual(apRec.projected(onto: stdSong, region: .cn).computedRating, 279)
        XCTAssertEqual(apRec.projected(onto: stdSong, region: .jp).computedRating, 280)

        let utageSong = try XCTUnwrap(songsById[110281])
        XCTAssertEqual(utageSong.chartArrayIndex(forRecordLevelIndex: 11), 1)
        let buddy2P = ProberRecord(
            achievements: 101.0,
            ds: 13.0,
            fc: "app",
            levelIndex: 11,
            songId: 110281,
            title: "[協]ダブルラリアット",
            type: "utage"
        )
        XCTAssertEqual(buddy2P.projected(onto: utageSong, region: .jp).computedRating, 0)
    }

    func testLenientBackendDTOsNormalizeCachedUtageAndINTLProjection() throws {
        // 1. Lenient decode with omitted optional fields
        let minimalRespJSON = """
        {
          "mode": "quick",
          "profile": { "userId": 888999 },
          "records": [
            { "songId": 8, "levelIndex": 3, "achievements": 100.5 }
          ]
        }
        """
        let decodedResp = try JSONDecoder().decode(SyncBackendResponse.self, from: Data(minimalRespJSON.utf8))
        XCTAssertEqual(decodedResp.records.count, 1)
        XCTAssertEqual(decodedResp.records[0].dxScore, 0)
        XCTAssertEqual(decodedResp.recordCount, 1)
        XCTAssertEqual(decodedResp.syncedAtUnixMs, 0)

        let minimalErrJSON = """
        { "error": {} }
        """
        let decodedErr = try JSONDecoder().decode(SyncBackendErrorEnvelope.self, from: Data(minimalErrJSON.utf8))
        XCTAssertEqual(decodedErr.error.code, "")
        XCTAssertEqual(decodedErr.error.message, "")

        // 2. normalizeCachedRecords merges colliding legacy (_0) & canonical (_10) Utage records
        let legacyUtage = ProberRecord(
            achievements: 98.5,
            ds: 13.0,
            fc: "fc",
            fs: "",
            level: "13?",
            levelIndex: 0,
            ra: 250,
            rate: "SSS",
            songId: 110281,
            title: "[協]ダブルラリアット",
            type: "DX",
            dxScore: 1500
        )
        let canonicalUtage = ProberRecord(
            achievements: 100.2,
            ds: 0,
            fc: "",
            fs: "fsd",
            level: "13?",
            levelIndex: 10,
            ra: 0,
            rate: "SSS+",
            songId: 110281,
            title: "[協]ダブルラリアット",
            type: "utage",
            dxScore: 1800
        )
        let normalized = ScoreSyncEngine.normalizeCachedRecords([legacyUtage, canonicalUtage])
        XCTAssertEqual(normalized.count, 1)
        XCTAssertEqual(normalized[0].levelIndex, 10)
        XCTAssertEqual(normalized[0].type, "utage")
        XCTAssertEqual(normalized[0].computedRating, 0)
        XCTAssertEqual(normalized[0].achievements, 100.2, accuracy: 1e-6)
        XCTAssertEqual(normalized[0].fc, "fc")
        XCTAssertEqual(normalized[0].fs, "fsd")
        XCTAssertEqual(normalized[0].dxScore, 1800)

        // 3. INTL projection preserves no-AP-bonus rule without mutating base record
        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })
        let stdSong = try XCTUnwrap(songsById[8])
        let baseAp = ProberRecord(
            achievements: 100.5,
            ds: 12.4,
            fc: "ap",
            levelIndex: 3,
            songId: 8,
            title: "True Love Song",
            type: "SD"
        )
        let intlProjected = baseAp.projected(onto: stdSong, region: .intl)
        XCTAssertEqual(intlProjected.computedRating, 280)
        XCTAssertEqual(intlProjected.ds, 12.4, accuracy: 1e-6)
        XCTAssertEqual(baseAp.computedRating, 279)
        XCTAssertEqual(baseAp.ds, 12.4, accuracy: 1e-6)
    }

    func testSyncServiceConfigResolution() {
        XCTAssertFalse(SyncServiceConfig.resolve(hostOrURL: "", accessKey: "key").isConfigured)
        XCTAssertFalse(SyncServiceConfig.resolve(hostOrURL: "sync.example.com", accessKey: "  ").isConfigured)
        XCTAssertFalse(SyncServiceConfig.resolve(hostOrURL: "$(SYNC_SERVICE_HOST)", accessKey: "key").isConfigured)
        XCTAssertFalse(SyncServiceConfig.builtIn(infoDictionary: [
            "SyncServiceHost": "$(SYNC_SERVICE_HOST)",
            "SyncServiceAccessKey": "$(SYNC_SERVICE_ACCESS_KEY)"
        ]).isConfigured)

        let prod = SyncServiceConfig.resolve(hostOrURL: "sync.example.com", accessKey: "abc")
        XCTAssertTrue(prod.isConfigured)
        XCTAssertEqual(prod.serverBaseURL, "https://sync.example.com")
        XCTAssertEqual(prod.accessKey, "abc")

        let local = SyncServiceConfig.resolve(hostOrURL: "localhost:8080", accessKey: "abc")
        XCTAssertEqual(local.serverBaseURL, "http://localhost:8080")

        let loopback = SyncServiceConfig.resolve(hostOrURL: "127.0.0.1:8080", accessKey: "abc")
        XCTAssertEqual(loopback.serverBaseURL, "http://127.0.0.1:8080")

        let explicit = SyncServiceConfig.resolve(hostOrURL: "https://alt.example.com:8443", accessKey: "abc")
        XCTAssertEqual(explicit.serverBaseURL, "https://alt.example.com:8443")
    }

    func testPlayerIdentityBindingAndUploadTargets() {
        let empty = PlayerIdentity()
        XCTAssertEqual(empty.boundChannelsSummary, "未绑定")
        XCTAssertEqual(empty.bindingStatus(for: .selfHosted, cnDirectAvailable: false), .unavailable)
        XCTAssertEqual(empty.bindingStatus(for: .selfHosted, cnDirectAvailable: true), .unbound)
        XCTAssertEqual(empty.bindingStatus(for: .lxns, cnDirectAvailable: true), .unbound)
        XCTAssertEqual(empty.bindingStatus(for: .divingFish, cnDirectAvailable: true), .unbound)
        XCTAssertTrue(empty.uploadTargets.isEmpty)

        let readOnlyIdentity = PlayerIdentity(
            selfHosted: SelfHostedBinding(externalUserId: 1001, displayName: "国服号", playerRating: 15000, boundAtEpochMs: 1000),
            lxns: LXNSBinding(authMethod: .lxnsOAuth, accountKey: "oidc_1", displayName: "落雪号", friendCode: nil, hasWriteScope: false, boundAtEpochMs: 1000)
        )
        XCTAssertEqual(readOnlyIdentity.bindingStatus(for: .selfHosted, cnDirectAvailable: true), .bound)
        XCTAssertEqual(readOnlyIdentity.bindingStatus(for: .lxns, cnDirectAvailable: true), .needsReauth)
        XCTAssertEqual(readOnlyIdentity.boundChannelsSummary, "国服直连 · 落雪（需重新授权）")
        XCTAssertTrue(readOnlyIdentity.uploadTargets.isEmpty)

        let fullIdentity = PlayerIdentity(
            selfHosted: SelfHostedBinding(externalUserId: 1001, displayName: "国服号", playerRating: 15000, boundAtEpochMs: 1000),
            lxns: LXNSBinding(authMethod: .lxnsOAuth, accountKey: "oidc_1", displayName: "落雪号", friendCode: nil, hasWriteScope: true, boundAtEpochMs: 1000),
            divingFish: DivingFishBinding(authMethod: .divingFishPassword, accountKey: "df1", username: "df1", nickname: "水鱼号", boundAtEpochMs: 1000)
        )
        XCTAssertEqual(fullIdentity.bindingStatus(for: .lxns, cnDirectAvailable: true), .bound)
        XCTAssertEqual(fullIdentity.bindingStatus(for: .divingFish, cnDirectAvailable: true), .bound)
        XCTAssertEqual(fullIdentity.boundChannelsSummary, "国服直连 · 落雪 · 水鱼")
        XCTAssertEqual(fullIdentity.uploadTargets, [.lxns, .divingFish])
    }

    func testPlayerIdentityLastSyncTextFormatting() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let now = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 5, day: 20, hour: 15, minute: 0)))

        XCTAssertNil(PlayerIdentity(lastSyncedAtEpochMs: nil).lastSyncText(now: now, calendar: calendar))
        XCTAssertNil(PlayerIdentity(lastSyncedAtEpochMs: 0).lastSyncText(now: now, calendar: calendar))

        let todaySync = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 5, day: 20, hour: 9, minute: 5)))
        let todayIdentity = PlayerIdentity(lastSyncedAtEpochMs: Int64(todaySync.timeIntervalSince1970 * 1000))
        XCTAssertEqual(todayIdentity.lastSyncText(now: now, calendar: calendar), "上次同步 今天 09:05")

        let yesterdaySync = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 5, day: 19, hour: 23, minute: 45)))
        let yesterdayIdentity = PlayerIdentity(lastSyncedAtEpochMs: Int64(yesterdaySync.timeIntervalSince1970 * 1000))
        XCTAssertEqual(yesterdayIdentity.lastSyncText(now: now, calendar: calendar), "上次同步 昨天 23:45")

        let olderSync = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: 5, day: 2, hour: 8, minute: 30)))
        let olderIdentity = PlayerIdentity(lastSyncedAtEpochMs: Int64(olderSync.timeIntervalSince1970 * 1000))
        XCTAssertEqual(olderIdentity.lastSyncText(now: now, calendar: calendar), "上次同步 05-02 08:30")
    }

    func testWritebackSummaryText() {
        XCTAssertEqual(WritebackSummary(results: []).summaryText, "")
        let mixed = WritebackSummary(results: [
            WritebackChannelStatus(channel: .lxns, uploadedCount: 12, skippedCount: 0, errorMessage: nil),
            WritebackChannelStatus(channel: .divingFish, uploadedCount: 0, skippedCount: 0, errorMessage: nil)
        ])
        XCTAssertTrue(mixed.isAllSucceeded)
        XCTAssertTrue(mixed.hasAnySucceeded)
        XCTAssertEqual(mixed.summaryText, "上传成功")

        let failed = WritebackSummary(results: [
            WritebackChannelStatus(channel: .divingFish, uploadedCount: 0, skippedCount: 0, errorMessage: "网络超时")
        ])
        XCTAssertFalse(failed.isAllSucceeded)
        XCTAssertFalse(failed.hasAnySucceeded)
        XCTAssertEqual(failed.summaryText, "水鱼上传失败：网络超时")

        let withSkipped = WritebackSummary(results: [
            WritebackChannelStatus(channel: .lxns, uploadedCount: 5, skippedCount: 2, errorMessage: nil),
            WritebackChannelStatus(channel: .divingFish, uploadedCount: 0, skippedCount: 1, errorMessage: nil)
        ])
        XCTAssertTrue(withSkipped.isAllSucceeded)
        XCTAssertEqual(withSkipped.summaryText, "上传成功")

        let partial = WritebackSummary(results: [
            WritebackChannelStatus(channel: .lxns, uploadedCount: 3, skippedCount: 0, errorMessage: nil),
            WritebackChannelStatus(channel: .divingFish, uploadedCount: 0, skippedCount: 0, errorMessage: "网络超时")
        ])
        XCTAssertFalse(partial.isAllSucceeded)
        XCTAssertTrue(partial.hasAnySucceeded)
        XCTAssertEqual(partial.summaryText, "落雪上传成功，水鱼上传失败：网络超时")
    }

    func testIdentityB50NameSyncPlanAndUploadEligibility() {
        let empty = PlayerIdentity(label: "备用档案")
        XCTAssertEqual(empty.primaryDisplayName, "备用档案")
        XCTAssertEqual(empty.b50PlayerName, "备用档案")
        XCTAssertEqual(empty.syncPlan, .none)
        XCTAssertFalse(empty.canUploadToProbers)

        let lxnsOnly = PlayerIdentity(
            label: "备用档案",
            customNickname: "  B50昵称  ",
            lxns: LXNSBinding(
                authMethod: .lxnsOAuth,
                accountKey: "o1",
                displayName: "落雪号",
                friendCode: nil,
                hasWriteScope: true,
                boundAtEpochMs: 1000
            )
        )
        XCTAssertEqual(lxnsOnly.primaryDisplayName, "落雪号")
        XCTAssertEqual(lxnsOnly.b50PlayerName, "B50昵称")
        XCTAssertEqual(lxnsOnly.syncPlan, .singleProber(.lxns))
        XCTAssertFalse(lxnsOnly.canUploadToProbers)

        let dfOnly = PlayerIdentity(
            divingFish: DivingFishBinding(
                authMethod: .divingFishPassword,
                accountKey: "d1",
                username: "d1",
                nickname: "水鱼号",
                boundAtEpochMs: 1000
            )
        )
        XCTAssertEqual(dfOnly.syncPlan, .singleProber(.divingFish))
        XCTAssertFalse(dfOnly.canUploadToProbers)

        let dualProber = PlayerIdentity(lxns: lxnsOnly.lxns, divingFish: dfOnly.divingFish)
        XCTAssertEqual(dualProber.syncPlan, .mergedProbers)
        XCTAssertTrue(dualProber.canUploadToProbers)

        let readOnlyDual = PlayerIdentity(
            lxns: LXNSBinding(
                authMethod: .lxnsOAuth,
                accountKey: "o1",
                displayName: "落雪号",
                friendCode: nil,
                hasWriteScope: false,
                boundAtEpochMs: 1000
            ),
            divingFish: dfOnly.divingFish
        )
        XCTAssertEqual(readOnlyDual.syncPlan, .mergedProbers)
        XCTAssertEqual(readOnlyDual.uploadTargets, [.divingFish])
        XCTAssertTrue(readOnlyDual.canUploadToProbers)

        let cnReadOnlyLXNS = PlayerIdentity(
            selfHosted: SelfHostedBinding(externalUserId: 1001, displayName: "国服号", playerRating: 15000, boundAtEpochMs: 1000),
            lxns: readOnlyDual.lxns
        )
        XCTAssertEqual(cnReadOnlyLXNS.syncPlan, .cnDirectQuick)
        XCTAssertFalse(cnReadOnlyLXNS.canUploadToProbers)

        let cnWriteLXNS = PlayerIdentity(
            selfHosted: cnReadOnlyLXNS.selfHosted,
            lxns: lxnsOnly.lxns
        )
        XCTAssertEqual(cnWriteLXNS.syncPlan, .cnDirectQuick)
        XCTAssertTrue(cnWriteLXNS.canUploadToProbers)
    }

    func testPureCNWritebackAndSnapshotFiltering() throws {
        let bundle = try loadVectors()
        let activeCandidates = bundle.songs.filter { !$0.isUtage && $0.ds.count >= 2 }
        XCTAssertGreaterThanOrEqual(activeCandidates.count, 2)
        let activeSong = activeCandidates[0]
        var disabledSong = activeCandidates[1]
        disabledSong.isDisabled = true

        let cnSongsById: [Int: SongData] = [
            activeSong.numericId: activeSong,
            disabledSong.numericId: disabledSong
        ]
        let activeIdx = activeSong.ds.count - 1
        let disabledIdx = disabledSong.ds.count - 1
        let unknownDeletedId = 181

        let zeroRecord = ProberRecord(
            achievements: 0.0,
            ds: activeSong.ds[0],
            fc: "",
            fs: "",
            level: activeSong.level[0],
            levelIndex: 0,
            rate: "d",
            songId: activeSong.numericId,
            title: activeSong.title,
            type: activeSong.type
        )
        let activeRecord = ProberRecord(
            achievements: 100.5,
            ds: activeSong.ds[activeIdx],
            fc: "fc",
            fs: "",
            level: activeSong.level[activeIdx],
            levelIndex: activeIdx,
            rate: "sssp",
            songId: activeSong.numericId,
            title: activeSong.title,
            type: activeSong.type
        )
        let disabledRecord = ProberRecord(
            achievements: 100.2,
            ds: disabledSong.ds[disabledIdx],
            fc: "",
            fs: "",
            level: disabledSong.level[disabledIdx],
            levelIndex: disabledIdx,
            rate: "sss",
            songId: disabledSong.numericId,
            title: disabledSong.title,
            type: disabledSong.type
        )
        let deletedRecord = ProberRecord(
            achievements: 99.8,
            ds: 13.5,
            fc: "",
            fs: "",
            level: "13+",
            levelIndex: 3,
            rate: "sssp",
            songId: unknownDeletedId,
            title: "Deleted Song",
            type: MaimaiConstants.chartTypeSD
        )

        XCTAssertFalse(ScoreSyncEngine.isEligibleForCNProberUpload(record: zeroRecord, cnSongsById: cnSongsById))
        XCTAssertFalse(ScoreSyncEngine.isEligibleForCNProberUpload(record: disabledRecord, cnSongsById: cnSongsById))
        XCTAssertFalse(ScoreSyncEngine.isEligibleForCNProberUpload(record: deletedRecord, cnSongsById: cnSongsById))
        XCTAssertTrue(ScoreSyncEngine.isEligibleForCNProberUpload(record: activeRecord, cnSongsById: cnSongsById))

        let records = [zeroRecord, activeRecord, disabledRecord, deletedRecord]
        let lxnsResult = ScoreSyncEngine.buildLXNSWriteback(
            localRecords: records,
            cloudRecords: [],
            cnSongsById: cnSongsById
        )
        XCTAssertEqual(lxnsResult.uploads.count, 1)
        XCTAssertEqual(lxnsResult.skippedDeletedCount, 2)
        XCTAssertTrue(lxnsResult.sampleSkippedSongIds.contains(disabledSong.numericId))
        XCTAssertTrue(lxnsResult.sampleSkippedSongIds.contains(unknownDeletedId))

        let dfResult = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords: records,
            cloudRecords: [],
            songsById: cnSongsById,
            cnSongsById: cnSongsById,
            musicTitlesById: [activeSong.numericId: activeSong.title]
        )
        XCTAssertEqual(dfResult.uploads.count, 1)
        XCTAssertEqual(dfResult.uploads[0].title, activeSong.title)
        XCTAssertEqual(dfResult.skippedDeletedCount, 2)

        let snapAll = ScoreSyncEngine.computeCNRatingSnapshot(records: records, songsById: cnSongsById)
        let snapActive = ScoreSyncEngine.computeCNRatingSnapshot(records: [activeRecord], songsById: cnSongsById)
        XCTAssertEqual(snapAll, snapActive)
        XCTAssertGreaterThan(snapAll.cnRating, 0)
    }

    func testLegacyB50NicknameMigration() {
        let initial = [
            PlayerIdentity(label: "档案1", customNickname: ""),
            PlayerIdentity(label: "档案2", customNickname: "已有名称")
        ]

        let skipped = ScoreSyncEngine.migrateLegacyB50Nickname(
            identities: initial,
            legacyUseProberNickname: true,
            legacyCustomNickname: "旧昵称"
        )
        XCTAssertFalse(skipped.didChange)
        XCTAssertEqual(skipped.identities[0].customNickname, "")

        let migrated = ScoreSyncEngine.migrateLegacyB50Nickname(
            identities: initial,
            legacyUseProberNickname: false,
            legacyCustomNickname: "  新B50名  "
        )
        XCTAssertTrue(migrated.didChange)
        XCTAssertEqual(migrated.identities[0].customNickname, "新B50名")
        XCTAssertEqual(migrated.identities[1].customNickname, "已有名称")
    }

    func testResolveDeletedCNSongsAndSongFilterPlayedDisabled() throws {
        let regions = ZetarakuRegions(jp: false, intl: true, usa: false, cn: false)
        let sheets = [
            ZetarakuSheet(
                type: "std",
                difficulty: "basic",
                level: "3",
                levelValue: 3.0,
                internalLevelValue: 3.0,
                noteDesigner: "-",
                noteCounts: ZetarakuNoteCounts(tap: 50, hold: 5, slide: 2, touch: nil, breakCount: 1, total: 58),
                regions: regions,
                regionOverrides: nil,
                version: nil
            ),
            ZetarakuSheet(
                type: "std",
                difficulty: "advanced",
                level: "6",
                levelValue: 6.0,
                internalLevelValue: 6.0,
                noteDesigner: "-",
                noteCounts: ZetarakuNoteCounts(tap: 90, hold: 8, slide: 6, touch: nil, breakCount: 2, total: 106),
                regions: regions,
                regionOverrides: nil,
                version: nil
            ),
            ZetarakuSheet(
                type: "std",
                difficulty: "expert",
                level: "10",
                levelValue: 10.0,
                internalLevelValue: 10.2,
                noteDesigner: "CharterE",
                noteCounts: ZetarakuNoteCounts(tap: 180, hold: 12, slide: 20, touch: nil, breakCount: 4, total: 216),
                regions: regions,
                regionOverrides: nil,
                version: nil
            ),
            ZetarakuSheet(
                type: "std",
                difficulty: "master",
                level: "13",
                levelValue: 13.0,
                internalLevelValue: 13.4,
                noteDesigner: "CharterM",
                noteCounts: ZetarakuNoteCounts(tap: 320, hold: 20, slide: 45, touch: nil, breakCount: 8, total: 393),
                regions: regions,
                regionOverrides: nil,
                version: nil
            )
        ]
        let zSong = ZetarakuSong(
            songId: "revived_181",
            category: "POPS＆アニメ",
            title: "Revived Track",
            artist: "Tester",
            bpm: 150.0,
            imageName: "revived_181.png",
            version: "FESTiVAL",
            releaseDate: nil,
            comment: nil,
            sheets: sheets
        )
        let catalog = ZetarakuCatalog(songs: [zSong])
        let idMap = DXDataIdMap(
            updatedAt: nil,
            idsByKey: ["Revived Track|流行&动漫|\(MaimaiConstants.chartTypeSD)": 181]
        )

        let resolution = ZetarakuCatalogConverter.resolveDeletedCNSongs(
            unknownIds: [181, 999_999],
            catalog: catalog,
            dxIdMap: idMap
        )
        XCTAssertEqual(resolution.resolved.count, 1)
        XCTAssertEqual(resolution.unresolvableIds, [999_999])
        let resolvedSong = try XCTUnwrap(resolution.resolved.first)
        XCTAssertEqual(resolvedSong.id, "181")
        XCTAssertEqual(resolvedSong.isDisabled, true)
        XCTAssertEqual(resolvedSong.preferRemoteCover, true)
        XCTAssertEqual(resolvedSong.ds, [3.0, 6.0, 10.2, 13.4])

        let activeSong = try XCTUnwrap(try loadVectors().songs.first { !$0.isUtage })
        let unplayedFiltered = SongFilter.apply(
            SongFilterCriteria(),
            to: [activeSong, resolvedSong],
            favoriteIds: [],
            enableAliasSearch: true,
            enableCharterSearch: false,
            region: .cn,
            playedSongIds: []
        )
        XCTAssertEqual(unplayedFiltered.map(\.numericId), [activeSong.numericId])

        let playedFiltered = SongFilter.apply(
            SongFilterCriteria(),
            to: [activeSong, resolvedSong],
            favoriteIds: [],
            enableAliasSearch: true,
            enableCharterSearch: false,
            region: .cn,
            playedSongIds: [181]
        )
        XCTAssertEqual(Set(playedFiltered.map(\.numericId)), [activeSong.numericId, 181])
    }

    func testFailClosedWritebackWhenCNCatalogEmpty() throws {
        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })
        let localRecord = ProberRecord(
            achievements: 100.5,
            ds: 12.4,
            fc: "ap",
            levelIndex: 3,
            songId: 8,
            title: "True Love Song",
            type: "SD"
        )
        let emptyCatalog: [Int: SongData] = [:]

        XCTAssertFalse(
            ScoreSyncEngine.isEligibleForCNProberUpload(record: localRecord, cnSongsById: emptyCatalog)
        )
        XCTAssertFalse(
            ScoreSyncEngine.needsDivingFishTitleMap(
                localRecords: [localRecord],
                cloudRecords: [],
                cnSongsById: emptyCatalog
            )
        )

        let lxResult = ScoreSyncEngine.buildLXNSWriteback(
            localRecords: [localRecord],
            cloudRecords: [],
            cnSongsById: emptyCatalog
        )
        XCTAssertTrue(lxResult.uploads.isEmpty)
        XCTAssertEqual(lxResult.skippedDeletedCount, 1)

        let dfResult = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords: [localRecord],
            cloudRecords: [],
            songsById: songsById,
            cnSongsById: emptyCatalog,
            musicTitlesById: [8: "True Love Song"]
        )
        XCTAssertTrue(dfResult.uploads.isEmpty)
        XCTAssertEqual(dfResult.skippedDeletedCount, 1)
    }

    func testPlayerIdentityLossyDecodeAndLegacyFields() throws {
        let rawJSON = """
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
        """
        let recovered = try XCTUnwrap(AccountCredentialStore.decodeIdentitiesLossy(from: Data(rawJSON.utf8)))
        XCTAssertEqual(recovered.count, 1)
        XCTAssertEqual(recovered[0].id, "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
        XCTAssertEqual(recovered[0].label, "主号")
        XCTAssertEqual(recovered[0].customNickname, "")
        XCTAssertFalse(recovered[0].hasMixedScoreSources)
        XCTAssertNil(recovered[0].lastSyncMode)
    }

    func testResolveDeletedCNSongsSkipsActiveAndNewCNCircleSongs() throws {
        let activeCNRegions = ZetarakuRegions(jp: true, intl: true, usa: false, cn: true)
        let jpOnlyRegions = ZetarakuRegions(jp: true, intl: false, usa: false, cn: false)

        let activeSong = ZetarakuSong(
            songId: "active_cn_101",
            category: "POPS＆アニメ",
            title: "Active CN Track",
            artist: "Tester",
            bpm: 150.0,
            imageName: "active_cn_101.png",
            version: "FESTiVAL",
            releaseDate: nil,
            comment: nil,
            sheets: [
                ZetarakuSheet(
                    type: "std",
                    difficulty: "master",
                    level: "13",
                    levelValue: 13.0,
                    internalLevelValue: 13.2,
                    noteDesigner: "Charter",
                    noteCounts: nil,
                    regions: activeCNRegions,
                    regionOverrides: nil,
                    version: nil
                )
            ]
        )
        let circleSong = ZetarakuSong(
            songId: "new_cn_circle_102",
            category: "POPS＆アニメ",
            title: "New CiRCLE Track",
            artist: "Tester",
            bpm: 160.0,
            imageName: "new_cn_circle_102.png",
            version: "CiRCLE",
            releaseDate: nil,
            comment: nil,
            sheets: [
                ZetarakuSheet(
                    type: "dx",
                    difficulty: "master",
                    level: "13",
                    levelValue: 13.0,
                    internalLevelValue: 13.3,
                    noteDesigner: "Charter",
                    noteCounts: nil,
                    regions: jpOnlyRegions,
                    regionOverrides: nil,
                    version: nil
                )
            ]
        )

        let catalog = ZetarakuCatalog(songs: [activeSong, circleSong])
        let idMap = DXDataIdMap(
            updatedAt: nil,
            idsByKey: [
                "Active CN Track|流行&动漫|\(MaimaiConstants.chartTypeSD)": 101,
                "New CiRCLE Track|流行&动漫|\(MaimaiConstants.chartTypeDX)": 102
            ]
        )

        let resolution = ZetarakuCatalogConverter.resolveDeletedCNSongs(
            unknownIds: [101, 102],
            catalog: catalog,
            dxIdMap: idMap
        )
        XCTAssertTrue(resolution.resolved.isEmpty)
        XCTAssertTrue(resolution.unresolvableIds.isEmpty)
    }

    func testSyncHistoryStoreRepairsTornLineBeforeAppending() throws {
        let tempDir = FileManager.default.temporaryDirectory
            .appendingPathComponent("maimai_hist_torn_\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: tempDir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: tempDir) }

        let bundle = try loadVectors()
        let songsById = Dictionary(uniqueKeysWithValues: bundle.songs.map { ($0.numericId, $0) })
        let store = SyncHistoryStore(directoryURL: tempDir)
        let targetId = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee"

        try Data("{\"broken\":1".utf8).write(to: store.historyFileURL(for: targetId))
        try store.appendSnapshot(
            identityId: targetId,
            syncMode: .quick,
            records: bundle.cnRatingSnapshot.records,
            songsById: songsById,
            timestampEpochMs: 500
        )

        let entries = store.loadHistory(for: targetId)
        XCTAssertEqual(entries.count, 1)
        XCTAssertEqual(entries[0].timestampEpochMs, 500)
        XCTAssertEqual(entries[0].cnRating, 911)
    }

    func testSyncDiagnosticsRedactsSensitiveTokens() {
        let raw = "err\nSGWCMAIDabc123def Bearer tok_abc.def jwt_token=sec_val token=mysecret123 cookie:sid999 hash=0123456789abcdef0123456789abcdef"
        let sanitized = SyncDiagnostics.shared.sanitize(raw, limit: 400)
        XCTAssertFalse(sanitized.contains("\n"))
        XCTAssertTrue(sanitized.contains("[REDACTED]"))
        XCTAssertFalse(sanitized.contains("SGWCMAIDabc123def"))
        XCTAssertFalse(sanitized.contains("tok_abc.def"))
        XCTAssertFalse(sanitized.contains("sec_val"))
        XCTAssertFalse(sanitized.contains("mysecret123"))
        XCTAssertFalse(sanitized.contains("sid999"))
        XCTAssertFalse(sanitized.contains("0123456789abcdef0123456789abcdef"))
    }
}
