import XCTest
@testable import MaimaiData

final class ScoreProfileAndLXNSTests: XCTestCase {
    func testLXNSScoreConversionStandardDXAndUtageMapping() {
        let sdSong = SongData(
            basicInfo: .init(
                artist: "Kai",
                bpm: 150,
                from: "maimai",
                genre: "舞萌",
                isNew: false,
                title: "True Love Song",
                version: "10000"
            ),
            charts: (0..<4).map { _ in .init(charter: "-", notes: [63, 23, 8, 2]) },
            ds: [5.0, 7.2, 10.2, 12.4],
            oldDs: nil,
            id: "8",
            level: ["5", "7", "10", "12"],
            title: "True Love Song",
            type: "SD",
            alias: nil
        )

        let dxSong = SongData(
            basicInfo: .init(
                artist: "Kai",
                bpm: 150,
                from: "舞萌DX 2021",
                genre: "舞萌",
                isNew: false,
                title: "True Love Song",
                version: "21000"
            ),
            charts: (0..<4).map { _ in .init(charter: "-", notes: [100, 20, 10, 5, 5]) },
            ds: [4.0, 7.5, 10.8, 13.7],
            oldDs: nil,
            id: "10008",
            level: ["4", "7+", "10+", "13+"],
            title: "True Love Song",
            type: "DX",
            alias: nil
        )

        let lookup: [Int: SongData] = [8: sdSong, 10008: dxSong]

        let rawScores: [LXNSScore] = [
            LXNSScore(
                id: 8,
                songName: "True Love Song",
                level: "12",
                levelIndex: 3,
                achievements: 100.5123,
                fc: "ap",
                fs: "fsd",
                dxScore: 1200,
                dxRating: 279.1,
                rate: "sssp",
                type: "standard"
            ),
            // Same base song ID (< 10000), but `type == "dx"` -> must map to `10008`!
            LXNSScore(
                id: 8,
                songName: "True Love Song",
                level: "13+",
                levelIndex: 3,
                achievements: 100.5000,
                fc: "fc",
                fs: nil,
                dxScore: 1500,
                dxRating: 308.4,
                rate: "sssp",
                type: "dx"
            ),
            // Utage song (`id >= 100000`) -> keeps `100018` without `+10000`
            LXNSScore(
                id: 100018,
                songName: "[協]Love You",
                level: "12?",
                levelIndex: 0,
                achievements: 99.2000,
                fc: nil,
                fs: "sync",
                dxScore: 980,
                dxRating: 0,
                rate: "ss",
                type: "utage"
            )
        ]

        let converted = LXNSCatalogConverter.convertScores(rawScores, songsById: lookup)
        XCTAssertEqual(converted.count, 3)

        let bySongId = Dictionary(uniqueKeysWithValues: converted.map { ($0.songId, $0) })
        let stdRec = try! XCTUnwrap(bySongId[8])
        XCTAssertEqual(stdRec.type, "SD")
        XCTAssertEqual(stdRec.ds, 12.4)
        XCTAssertEqual(stdRec.fc, "ap")
        XCTAssertEqual(stdRec.fs, "fsd")
        XCTAssertEqual(stdRec.computedRating, RatingCalculator.singleSongRating(ds: 12.4, achievement: 100.5123))

        let dxRec = try! XCTUnwrap(bySongId[10008])
        XCTAssertEqual(dxRec.type, "DX")
        XCTAssertEqual(dxRec.ds, 13.7)
        XCTAssertEqual(dxRec.level, "13+")
        XCTAssertEqual(dxRec.computedRating, RatingCalculator.singleSongRating(ds: 13.7, achievement: 100.5000))

        let utageRec = try! XCTUnwrap(bySongId[100018])
        XCTAssertEqual(utageRec.type, "utage")
        XCTAssertEqual(utageRec.levelIndex, 10)
        XCTAssertEqual(utageRec.computedRating, 0)
        XCTAssertEqual(utageRec.title, "[協]Love You")
        XCTAssertEqual(utageRec.rate, "ss")
    }

    func testLXNSCatalogBuddyUtageAndMajorVersionOldDsPromotion() throws {
        let songJSON = """
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
        """.data(using: .utf8)!

        let priorSong = SongData(
            basicInfo: .init(
                artist: "削除",
                bpm: 150,
                from: "maimai FiNALE",
                genre: "舞萌",
                isNew: false,
                title: "PANDORA PARADOXXX",
                version: "25000"
            ),
            charts: (0..<5).map { _ in .init(charter: "-", notes: [100, 10, 10, 5]) },
            ds: [7.0, 9.7, 13.7, 14.9, 15.0],
            oldDs: nil,
            id: "834",
            level: ["7", "9+", "13+", "14+", "15"],
            title: "PANDORA PARADOXXX",
            type: "SD",
            alias: ["潘多拉"]
        )

        let decodedSongs = try JSONDecoder().decode(LXNSSongListResponse.self, from: songJSON)
        let converted = LXNSCatalogConverter.convertCatalog(
            songResponse: decodedSongs,
            aliasResponse: nil,
            existingBaseline: [priorSong]
        )

        XCTAssertEqual(converted.latestMajorVersion, 25500)
        XCTAssertEqual(converted.songs.count, 2)

        let pandora = try XCTUnwrap(converted.songs.first { $0.id == "834" })
        XCTAssertEqual(pandora.basicInfo.from, "maimai FiNALE")
        XCTAssertEqual(pandora.basicInfo.genre, "舞萌")
        XCTAssertEqual(pandora.alias, ["潘多拉"])
        // Baseline was from major 25000 (< 25500), so its `13.7` is promoted to `oldDs` -> `resolvedCharts[2].oldDs == 13.7`
        XCTAssertEqual(pandora.resolvedCharts[2].oldDs, 13.7)
        XCTAssertEqual(pandora.resolvedCharts[2].ds, 13.8)

        let buddyUtage = try XCTUnwrap(converted.songs.first { $0.id == "110281" })
        XCTAssertTrue(buddyUtage.isUtage)
        XCTAssertEqual(buddyUtage.resolvedCharts.count, 2)
        XCTAssertEqual(buddyUtage.resolvedCharts[0].difficulty, .utage)
        XCTAssertEqual(buddyUtage.resolvedCharts[1].difficulty, .utagePlayer2)
        XCTAssertEqual(buddyUtage.resolvedCharts[0].kanji, "回")
        XCTAssertEqual(buddyUtage.resolvedCharts[0].totalNotes, 653)
    }

    func testBundledSeedSongsAndPlateRulesDecodeCleanly() throws {
        let songSeedURL = try XCTUnwrap(Bundle.main.url(forResource: "songdata_seed", withExtension: "json"))
        let songData = try Data(contentsOf: songSeedURL)
        let songs = try JSONDecoder().decode([SongData].self, from: songData)
        XCTAssertGreaterThanOrEqual(songs.count, 1400)

        let plateSeedURL = try XCTUnwrap(Bundle.main.url(forResource: "plate_rules_seed", withExtension: "json"))
        let plateData = try Data(contentsOf: plateSeedURL)
        let plateCatalog = try JSONDecoder().decode(CNPlateCatalog.self, from: plateData)
        XCTAssertGreaterThanOrEqual(plateCatalog.groups.count, 19)

        let shinGroup = try XCTUnwrap(plateCatalog.groups.first { $0.id == "真" })
        XCTAssertEqual(shinGroup.basicToMasterSongIds.count, 85)
        XCTAssertEqual(shinGroup.resolvedVersionAsset, "maimai")
        // `ジングルベル` (ID 70) is officially excluded from 真極 / 真神 / 真舞舞
        XCTAssertFalse(shinGroup.basicToMasterSongIds.contains(70))
        XCTAssertEqual(shinGroup.badges, ["真極", "真神", "真舞舞"])

        let maiGroup = try XCTUnwrap(plateCatalog.groups.first { $0.id == "舞" })
        XCTAssertEqual(maiGroup.basicToMasterSongIds.count, 516)
        XCTAssertEqual(maiGroup.remasterSongIds.count, 59)
    }

    func testLegacyAccountMigrationAndOAuthCallbackValidation() throws {
        let legacyAccounts = [
            SavedAccount(username: "alice", password: "pw1"),
            SavedAccount(username: "bob", password: "pw2")
        ]
        let migrated = AccountCredentialStore.buildMigratedProfiles(
            legacyAccounts: legacyAccounts,
            currentUsername: "bob",
            currentPassword: "pw2",
            currentCookie: "jwt_token=xyz",
            currentNickname: "ＢＯＢ"
        )
        XCTAssertEqual(migrated.entries.count, 2)
        let activeId = try XCTUnwrap(migrated.activeProfileId)
        let activeEntry = try XCTUnwrap(migrated.entries.first { $0.profile.id == activeId })
        XCTAssertEqual(activeEntry.profile.provider, .divingFish)
        XCTAssertEqual(activeEntry.profile.authMethod, .divingFishPassword)
        XCTAssertEqual(activeEntry.profile.username, "bob")
        XCTAssertEqual(activeEntry.profile.nickname, "ＢＯＢ")
        XCTAssertEqual(activeEntry.profile.displayTitle, "ＢＯＢ")
        XCTAssertEqual(activeEntry.secret.cookie, "jwt_token=xyz")

        // RFC 7636 Appendix B S256 test vector
        let rfcVerifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        XCTAssertEqual(
            PKCEHelper.codeChallengeS256(for: rfcVerifier),
            "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
        )

        let validCallback = URL(string: "maimaidata://oauth/lxns?code=AUTH123&state=STATE_OK")!
        XCTAssertEqual(try MaimaiAPI.parseOAuthCallback(url: validCallback, expectedState: "STATE_OK"), "AUTH123")
        XCTAssertThrowsError(try MaimaiAPI.parseOAuthCallback(url: validCallback, expectedState: "WRONG_STATE"))
    }

    @MainActor
    func testToastStateCancelsPreviousDismissTimerOnRapidSwitches() async throws {
        let toast = ToastState()
        toast.show("已切换至 A", duration: 0.12)
        XCTAssertEqual(toast.text, "已切换至 A")

        try await Task.sleep(for: .milliseconds(60))
        toast.show("已切换至 B", duration: 0.28)
        XCTAssertEqual(toast.text, "已切换至 B")

        // At 60 + 100 = 160ms, the first 120ms timer would have fired if not cancelled.
        try await Task.sleep(for: .milliseconds(100))
        XCTAssertEqual(toast.text, "已切换至 B")

        // After the second timer completes, toast clears cleanly.
        try await Task.sleep(for: .milliseconds(230))
        XCTAssertNil(toast.text)
    }

    func testOverseasPlateScopesCoverJPAndINTLWithExclusionsAndDynamicFutureKanji() throws {
        let plateSeedURL = try XCTUnwrap(Bundle.main.url(forResource: "plate_rules_seed", withExtension: "json"))
        let plateCatalog = try JSONDecoder().decode(CNPlateCatalog.self, from: Data(contentsOf: plateSeedURL))

        func makeOverseasSong(id: String, title: String, from: String, type: String = "SD", chartCount: Int = 4) -> SongData {
            SongData(
                basicInfo: .init(
                    artist: "Artist",
                    bpm: 150,
                    from: from,
                    genre: "舞萌",
                    isNew: false,
                    title: title,
                    version: from
                ),
                charts: (0..<chartCount).map { _ in .init(charter: "-", notes: type == "DX" ? [100, 10, 10, 10, 5] : [100, 10, 10, 5]) },
                ds: Array(repeating: 13.0, count: chartCount),
                oldDs: nil,
                id: id,
                level: Array(repeating: "13", count: chartCount),
                title: title,
                type: type,
                alias: nil
            )
        }

        let jingleBells = makeOverseasSong(id: "70", title: "ジングルベル", from: "maimai")
        let trueLove = makeOverseasSong(id: "8", title: "True Love Song", from: "maimai")
        let zenzenzense = makeOverseasSong(id: "853", title: "前前前世", from: "MURASAKi PLUS")
        let hibana = makeOverseasSong(id: "792", title: "ヒバナ", from: "MiLK", chartCount: 5)
        let dxEraRemaster = makeOverseasSong(id: "500", title: "JACKY [Remix]", from: "PiNK", chartCount: 5)
        let circlePlusSong = makeOverseasSong(id: "11800", title: "CiRCLE+ Song", from: "CiRCLE PLUS", type: "DX")
        let magicalSong = makeOverseasSong(id: "11900", title: "MAGiCAL Song", from: "MAGiCAL", type: "DX")

        let jpSongs = [jingleBells, trueLove, zenzenzense, hibana, dxEraRemaster, circlePlusSong, magicalSong]
        let jpScopes = LXNSCatalogConverter.buildPlateScopes(
            for: .jp,
            cnPlateGroups: plateCatalog.groups,
            activeSongs: jpSongs
        )

        let jpShin = try XCTUnwrap(jpScopes.first { $0.id == "真" })
        XCTAssertTrue(jpShin.contains(trueLove, difficulty: .master))
        XCTAssertFalse(jpShin.contains(jingleBells, difficulty: .master))

        let jpMai = try XCTUnwrap(jpScopes.first { $0.id == "舞" })
        // ジングルベル still counts toward 舞 / 覇者, while 前前前世 is excluded
        XCTAssertTrue(jpMai.contains(jingleBells, difficulty: .master))
        XCTAssertFalse(jpMai.contains(zenzenzense, difficulty: .master))
        // ヒバナ (792, or synthetic `zeta_std_hibana` offline) is an SD-era Re:MASTER, whereas 500 is a DX-era added Re:MASTER
        let offlineHibana = makeOverseasSong(id: "zeta_std_hibana", title: "ヒバナ", from: "MiLK", chartCount: 5)
        XCTAssertTrue(jpMai.contains(hibana, difficulty: .remaster))
        XCTAssertTrue(jpMai.contains(offlineHibana, difficulty: .remaster))
        XCTAssertFalse(jpMai.contains(dxEraRemaster, difficulty: .remaster))

        XCTAssertTrue(jpScopes.contains { $0.title == "彩 · PRiSM PLUS" })
        XCTAssertTrue(jpScopes.contains { $0.title == "丸 · CiRCLE" })
        XCTAssertTrue(jpScopes.contains { $0.title == "廻 · CiRCLE PLUS" })
        XCTAssertTrue(jpScopes.contains { $0.title == "MAGiCAL" })

        // On INTL before MAGiCAL launches, CiRCLE PLUS has no plate kanji yet
        let intlSongsBeforeMagical = [trueLove, circlePlusSong]
        let intlScopes = LXNSCatalogConverter.buildPlateScopes(
            for: .intl,
            cnPlateGroups: plateCatalog.groups,
            activeSongs: intlSongsBeforeMagical
        )
        XCTAssertEqual(intlScopes.last?.title, "CiRCLE PLUS")

        // Once INTL receives MAGiCAL (and Zetaraku assigns a future plate kanji in `abbr`), both promote automatically
        let futureScopes = LXNSCatalogConverter.buildPlateScopes(
            for: .intl,
            cnPlateGroups: plateCatalog.groups,
            activeSongs: jpSongs,
            zetarakuVersions: [
                ZetarakuVersionEntry(version: "MAGiCAL", abbr: "MAGiCAL (魔)"),
                ZetarakuVersionEntry(version: "MAGiCAL PLUS", abbr: "MAGiCAL+")
            ]
        )
        XCTAssertTrue(futureScopes.contains { $0.title == "廻 · CiRCLE PLUS" })
        XCTAssertTrue(futureScopes.contains { $0.title == "魔 · MAGiCAL" })
    }
}
