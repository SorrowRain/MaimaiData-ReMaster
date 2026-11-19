import XCTest
import UIKit
@testable import MaimaiData

final class MultiRegionTests: XCTestCase {
    func testAPPlusOneSingleSongRatingTierRowAndRecordProjection() throws {
        // Base 14.8 @ 100.5000% = 333
        XCTAssertEqual(
            RatingCalculator.singleSongRating(ds: 14.8, achievement: 100.5, isAP: true, apBonusEnabled: false),
            333
        )
        XCTAssertEqual(
            RatingCalculator.singleSongRating(ds: 14.8, achievement: 100.5, isAP: true, apBonusEnabled: true),
            334
        )
        XCTAssertEqual(
            RatingCalculator.singleSongRating(ds: 14.8, achievement: 100.5, isAP: false, apBonusEnabled: true),
            333
        )

        // Top AP row in JP/INTL mode sits above SSS+ with +1 Ra and distinct Identifiable id
        let nonAPRows = RatingCalculator.buildTierRows(
            ds: 14.8,
            currentAchievement: 100.6,
            currentIsAP: false,
            includeAPBonusRow: true
        )
        XCTAssertEqual(nonAPRows[0].rate, "ap")
        XCTAssertEqual(nonAPRows[0].singleRating, 334)
        XCTAssertEqual(nonAPRows[0].isCurrentTier, false)
        XCTAssertEqual(nonAPRows[1].rate, "sssp")
        XCTAssertEqual(nonAPRows[1].singleRating, 333)
        XCTAssertEqual(nonAPRows[1].isCurrentTier, true)
        XCTAssertNotEqual(nonAPRows[0].id, nonAPRows[1].id)

        let apRows = RatingCalculator.buildTierRows(
            ds: 14.8,
            currentAchievement: 100.6,
            currentIsAP: true,
            includeAPBonusRow: true
        )
        XCTAssertEqual(apRows[0].isCurrentTier, true)
        XCTAssertEqual(apRows[1].isPassed, true)

        let recordJSON = """
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
        """.data(using: .utf8)!
        let cnRecord = try JSONDecoder().decode(ProberRecord.self, from: recordJSON)
        XCTAssertEqual(cnRecord.computedRating, 330)

        let jpSong = SongData(
            basicInfo: .init(
                artist: "Artist",
                bpm: 180,
                from: "MAGiCAL",
                genre: "舞萌",
                isNew: true,
                title: "Test Song",
                version: "MAGiCAL"
            ),
            charts: (0..<4).map { _ in .init(charter: "-", notes: [100, 10, 10, 10, 10]) },
            ds: [4.0, 8.0, 11.5, 14.8],
            oldDs: [4.0, 8.0, 11.5, 14.7],
            id: "11663",
            level: ["4", "8", "11+", "14+"],
            title: "Test Song",
            type: "DX",
            alias: nil
        )

        let cnProjected = cnRecord.projected(onto: jpSong, region: .cn)
        XCTAssertEqual(cnProjected.ds, 14.7)
        XCTAssertEqual(cnProjected.computedRating, 330)

        let jpProjected = cnRecord.projected(onto: jpSong, region: .jp)
        XCTAssertEqual(jpProjected.ds, 14.8)
        XCTAssertEqual(jpProjected.computedRating, 334)
    }

    func testZetarakuConverterDXDataIdJoinUtageBPMCommentAndMigration() throws {
        let cnLink = SongData(
            basicInfo: .init(
                artist: "Clean Tears",
                bpm: 132,
                from: "maimai ORANGE",
                genre: "niconico & VOCALOID",
                isNew: false,
                title: "Link(CoF)",
                version: "14000"
            ),
            charts: (0..<4).map { _ in .init(charter: "-", notes: [100, 20, 20, 10]) },
            ds: [3.0, 6.0, 9.0, 12.4],
            oldDs: nil,
            id: "383",
            level: ["3", "6", "9", "12"],
            title: "Link(CoF)",
            type: "SD",
            alias: ["cof", "link cof"]
        )

        let rawDXDataJSON = """
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
        """.data(using: .utf8)!
        let dxMap = DXDataIdMap.extract(from: try JSONDecoder().decode(DXDataRawCatalog.self, from: rawDXDataJSON))
        XCTAssertEqual(dxMap.idsByKey["魔法仕掛けのファンタジア|舞萌|DX"], 12025)

        let rawCatalogJSON = """
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
        """.data(using: .utf8)!

        let catalog = try JSONDecoder().decode(ZetarakuCatalog.self, from: rawCatalogJSON)
        let conv = ZetarakuCatalogConverter.convertWithMigrations(
            catalog,
            region: .jp,
            cnReference: [cnLink],
            dxIdMap: dxMap
        )

        // JP-exclusive song got real online ID 12025 from dxdata + migration pair + CloudFront primary cover
        let jpFantasia = try XCTUnwrap(conv.songs.first { $0.title == "魔法仕掛けのファンタジア" })
        XCTAssertEqual(jpFantasia.id, "12025")
        XCTAssertEqual(jpFantasia.numericId, 12025)
        XCTAssertEqual(
            jpFantasia.coverCandidateURLs.first?.absoluteString,
            "https://dp4p6x0xfi5o9.cloudfront.net/maimai/img/cover/fantasia.png"
        )
        XCTAssertTrue(conv.migratedIdPairs.contains {
            $0.legacyId == "zeta_dx_魔法仕掛けのファンタジア" && $0.resolvedId == "12025"
        })

        // JP-only Utage derived ID 100000 + 12025 = 112025, recovered BPM 280 from base song, and preserved comment & kanji!
        let jpUtage = try XCTUnwrap(conv.songs.first { $0.isUtage })
        XCTAssertEqual(jpUtage.id, "112025")
        XCTAssertEqual(jpUtage.basicInfo.bpm, 280)
        XCTAssertEqual(jpUtage.comment, "魔法の力でノンストップ！")
        XCTAssertEqual(jpUtage.resolvedCharts[0].kanji, "【魔】")

        // CN Utage enrichment backfills comment and kanji when cached Zetaraku catalog is present
        let rawCNUtage = SongData(
            basicInfo: .init(
                artist: "igel",
                bpm: 280,
                from: "舞萌DX 2026",
                genre: "宴会場",
                isNew: false,
                title: "[魔]魔法仕掛けのファンタジア",
                version: "26000"
            ),
            charts: [.init(charter: "-", notes: [100, 10, 10, 10, 10])],
            ds: [14.8],
            oldDs: nil,
            id: "112025",
            level: ["14+?"],
            title: "[魔]魔法仕掛けのファンタジア",
            type: "DX",
            alias: nil
        )
        let enrichedCN = ZetarakuCatalogConverter.enrichCNSongsWithUtageMetadata([rawCNUtage], from: catalog)
        XCTAssertEqual(enrichedCN[0].comment, "魔法の力でノンストップ！")
        XCTAssertEqual(enrichedCN[0].resolvedCharts[0].kanji, "【魔】")
    }

    func testDynamicB15VersionWindowForINTLAndJP() throws {
        let json = """
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
        """.data(using: .utf8)!
        let catalog = try JSONDecoder().decode(ZetarakuCatalog.self, from: json)

        // INTL has CiRCLE, CiRCLE PLUS, MAGiCAL active -> B15 window is the latest 2 (CiRCLE PLUS + MAGiCAL), NOT CiRCLE
        let intlSongs = ZetarakuCatalogConverter.convertWithMigrations(
            catalog,
            region: .intl,
            cnReference: [],
            dxIdMap: nil
        ).songs
        let intlByTitle = Dictionary(uniqueKeysWithValues: intlSongs.map { ($0.title, $0) })
        XCTAssertEqual(intlByTitle["Circle Song"]?.basicInfo.isNew, false)
        XCTAssertEqual(intlByTitle["Circle Plus Song"]?.basicInfo.isNew, true)
        XCTAssertEqual(intlByTitle["Magical Song"]?.basicInfo.isNew, true)

        // JP has MAGiCAL PLUS active -> B15 window advances to the latest 1 (MAGiCAL PLUS)
        let jpSongs = ZetarakuCatalogConverter.convertWithMigrations(
            catalog,
            region: .jp,
            cnReference: [],
            dxIdMap: nil
        ).songs
        let jpByTitle = Dictionary(uniqueKeysWithValues: jpSongs.map { ($0.title, $0) })
        XCTAssertEqual(jpByTitle["Magical Song"]?.basicInfo.isNew, false)
        XCTAssertEqual(jpByTitle["Magical Plus Song"]?.basicInfo.isNew, true)
    }

    func testShortGenreAndAllVersionAndRankAssetsExist() {
        XCTAssertEqual(MaimaiConstants.shortGenreDisplayName(for: "niconico & VOCALOID"), "VOCALOID")
        XCTAssertEqual(MaimaiConstants.shortGenreDisplayName(for: "流行&动漫"), "流行&动漫")

        for ver in MaimaiConstants.cnVersions {
            XCTAssertNotNil(UIImage(named: ver.asset), "Missing CN version asset: \(ver.asset)")
        }
        for ver in MaimaiConstants.jpVersions {
            XCTAssertNotNil(UIImage(named: ver.asset), "Missing JP version asset: \(ver.asset)")
        }

        for rate in MaimaiConstants.validRankKeys {
            let cnAsset = MaimaiConstants.rankAssetName(for: rate, region: .cn)
            let intlAsset = MaimaiConstants.rankAssetName(for: rate, region: .intl)
            let jpAsset = MaimaiConstants.rankAssetName(for: rate, region: .jp)
            XCTAssertTrue(cnAsset.contains("_prism_"))
            XCTAssertTrue(intlAsset.contains("_circle_"))
            XCTAssertEqual(intlAsset, jpAsset)
            XCTAssertNotNil(UIImage(named: cnAsset), "Missing PRiSM rank asset: \(cnAsset)")
            XCTAssertNotNil(UIImage(named: jpAsset), "Missing CiRCLE rank asset: \(jpAsset)")
        }
        XCTAssertNotNil(UIImage(named: MaimaiConstants.rankAssetName(for: "ap", region: .jp)))
    }
}
