import XCTest
@testable import MaimaiData

final class SongFilterTests: XCTestCase {
    private func makeSong(
        id: String, title: String, genre: String = "舞萌", from: String = "舞萌DX",
        ds: [Double], level: [String], alias: [String]? = nil, charter: String = "-"
    ) throws -> SongData {
        let dict: [String: Any] = [
            "basic_info": [
                "artist": "A", "bpm": 150, "from": from, "genre": genre,
                "is_new": false, "title": title, "version": "1"
            ],
            "charts": ds.map { _ in ["charter": charter, "notes": [10, 10, 10, 10]] },
            "ds": ds, "id": id, "level": level, "title": title, "type": "DX"
        ].merging(alias.map { ["alias": $0] } ?? [:]) { a, _ in a }
        let data = try JSONSerialization.data(withJSONObject: dict)
        return try JSONDecoder().decode(SongData.self, from: data)
    }

    private func fixtures() throws -> [SongData] {
        [
            try makeSong(id: "1", title: "Alpha", ds: [3, 5, 8, 12.2], level: ["3", "5", "8", "12"], alias: ["first"]),
            try makeSong(id: "2", title: "Beta", genre: "其他游戏", ds: [4, 6, 9, 13.8], level: ["4", "6", "9", "13+"]),
            try makeSong(id: "3", title: "Gamma", genre: "宴会場", ds: [1, 1], level: ["1", "1"]),
            try makeSong(id: "4", title: "Delta", ds: [3, 5, 8, 14.6], level: ["3", "5", "8", "14+"], charter: "Zed")
        ]
    }

    private func run(_ c: SongFilterCriteria, favorites: Set<String> = [], alias: Bool = true, charter: Bool = false) throws -> [String] {
        SongFilter.apply(c, to: try fixtures(), favoriteIds: favorites, enableAliasSearch: alias, enableCharterSearch: charter).map(\.id)
    }

    func testDefaultExcludesUtageAndSortsByIdDescending() throws {
        XCTAssertFalse(SongFilterCriteria(keyword: "中文输入").isActive)
        XCTAssertEqual(try run(SongFilterCriteria()), ["4", "2", "1"])
    }

    func testGenreFilterIncludesUtageWhenSelected() throws {
        var c = SongFilterCriteria()
        c.selectedGenres = ["宴会場"]
        XCTAssertEqual(try run(c), ["3"])
    }

    func testKeywordMatchesTitleAliasAndId() throws {
        var c = SongFilterCriteria()
        c.keyword = "alpha"
        XCTAssertEqual(try run(c), ["1"])
        c.keyword = "ＡＬＰＨＡ"
        XCTAssertEqual(try run(c), ["1"])
        c.keyword = "FIRST"
        XCTAssertEqual(try run(c), ["1"])
        XCTAssertEqual(try run(c, alias: false), [])
        c.keyword = "2"
        XCTAssertEqual(try run(c), ["2"])
        c.keyword = "0002"
        XCTAssertEqual(try run(c), ["2"])
    }

    func testCharterSearchOnlyWhenEnabled() throws {
        var c = SongFilterCriteria()
        c.keyword = "zed"
        XCTAssertEqual(try run(c), [])
        XCTAssertEqual(try run(c, charter: true), ["4"])
    }

    func testFavoritesOnly() throws {
        var c = SongFilterCriteria()
        c.onlyFavorites = true
        XCTAssertEqual(try run(c, favorites: ["2"]), ["2"])
    }

    func testLevelAndExactDSFilters() throws {
        var c = SongFilterCriteria()
        c.selectedLevel = "13+"
        XCTAssertEqual(try run(c), ["2"])
        c = SongFilterCriteria()
        c.useExactDS = true
        c.exactDS = 12.2
        XCTAssertEqual(try run(c), ["1"])
    }

    func testMasterSorting() throws {
        var c = SongFilterCriteria()
        c.sortOption = .masterDesc
        XCTAssertEqual(try run(c), ["4", "2", "1"])
        c.sortOption = .masterAsc
        XCTAssertEqual(try run(c), ["1", "2", "4"])
    }

    func testVersionFilterExpansion() {
        XCTAssertEqual(MaimaiConstants.expandVersionFilter(["maimai"]), ["maimai", "maimai PLUS"])
        XCTAssertEqual(MaimaiConstants.expandVersionFilter(["GreeN"]), ["maimai GreeN", "maimai GreeN PLUS"])
        XCTAssertEqual(MaimaiConstants.expandVersionFilter(["舞萌DX 2021"]), ["舞萌DX 2021"])

        var criteria = SongFilterCriteria()
        criteria.selectedVersions = ["maimai", "舞萌DX 2025", "PRiSM"]
        let cnPruned = SongFilter.prunedCriteria(criteria, for: .cn)
        XCTAssertEqual(cnPruned.selectedVersions, ["maimai", "舞萌DX 2025"])
        let jpPruned = SongFilter.prunedCriteria(criteria, for: .jp)
        XCTAssertEqual(jpPruned.selectedVersions, ["maimai", "PRiSM"])
    }

    func testCounterpartAliasSyncSharesSongNicknamesWhileKeepingTypeSpecificOnes() throws {
        let dxDict: [String: Any] = [
            "basic_info": [
                "artist": "A", "bpm": 180, "from": "舞萌DX 2025", "genre": "舞萌",
                "is_new": true, "title": "Believe the Rainbow", "version": "25000"
            ],
            "charts": [[10, 10, 10, 10, 10], [10, 10, 10, 10, 10]].map { ["charter": "-", "notes": $0] },
            "ds": [4.0, 13.5], "id": "10835", "level": ["4", "13+"],
            "title": "Believe the Rainbow", "type": "DX",
            "alias": ["believe the rainbow", "dx彩虹"]
        ]
        let sdDict: [String: Any] = [
            "basic_info": [
                "artist": "A", "bpm": 180, "from": "maimai FiNALE", "genre": "舞萌",
                "is_new": false, "title": "Believe the Rainbow", "version": "19900"
            ],
            "charts": [[10, 10, 10, 10], [10, 10, 10, 10]].map { ["charter": "-", "notes": $0] },
            "ds": [3.0, 13.0], "id": "835", "level": ["3", "13"],
            "title": "Believe the Rainbow", "type": "SD",
            "alias": ["believe the rainbow", "相信彩虹", "btr", "标彩虹"]
        ]
        let sdFixed = try JSONDecoder().decode(SongData.self, from: JSONSerialization.data(withJSONObject: sdDict))
        let dx = try JSONDecoder().decode(SongData.self, from: JSONSerialization.data(withJSONObject: dxDict))

        let merged = SongData.mergingCounterpartAliases([sdFixed, dx])
        let mergedSD = try XCTUnwrap(merged.first { $0.id == "835" })
        let mergedDX = try XCTUnwrap(merged.first { $0.id == "10835" })

        XCTAssertTrue(mergedDX.alias?.contains("相信彩虹") == true)
        XCTAssertTrue(mergedDX.alias?.contains("btr") == true)
        XCTAssertFalse(mergedDX.alias?.contains("标彩虹") == true)
        XCTAssertFalse(mergedSD.alias?.contains("dx彩虹") == true)

        var criteria = SongFilterCriteria()
        criteria.keyword = "相信彩虹"
        let matchedIds = SongFilter.apply(
            criteria,
            to: merged,
            favoriteIds: [],
            enableAliasSearch: true,
            enableCharterSearch: false
        ).map(\.id)
        XCTAssertEqual(matchedIds, ["10835", "835"])
    }
}
