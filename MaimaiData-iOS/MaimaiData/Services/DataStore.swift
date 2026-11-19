import Foundation
import Observation

@MainActor
@Observable
final class DataStore {
    // MARK: - Domain Data
    private(set) var songs: [SongData] = []
    private(set) var songsById: [Int: SongData] = [:]
    private(set) var catalogRevision = 0
    private var cnSongs: [SongData] = []
    private var cnSongsById: [Int: SongData] = [:]
    private(set) var plateCatalog: CNPlateCatalog?
    private var zetarakuCatalog: ZetarakuCatalog?
    private var dxDataIdMap: DXDataIdMap?
    private var regionEpoch = 0
    private(set) var chartStats: [Int: [ChartStatEntry]] = [:]
    private(set) var records: [ProberRecord] = [] {
        didSet {
            var map: [String: ProberRecord] = [:]
            map.reserveCapacity(records.count)
            for rec in records {
                map["\(rec.songId)_\(rec.levelIndex)"] = rec
            }
            recordsByKey = map
        }
    }
    private var recordsByKey: [String: ProberRecord] = [:]
    /// Monotonic token distinguishing network-fetched records from a slower cache backfill.
    private var recordsEpoch = 0
    private(set) var maxNotesStats: MaxNotesStats = .fallback

    // MARK: - Loading & Status
    private var isFetchingCNSongs = false
    private var isFetchingZetaraku = false
    var isSyncingSongs: Bool { isFetchingCNSongs || isFetchingZetaraku }
    private(set) var isSyncingRecords = false
    private(set) var isSyncingStats = false
    private(set) var isLoadingLocalData = true
    var statusMessage: String?
    var syncNotice: DataSyncNotice?
    var availableAppUpdate: AppReleaseInfo?
    private(set) var isCheckingAppUpdate = false

    // Single-flight guard for rotating LXNS OAuth refresh tokens
    private var inFlightTokenRefreshTasks: [UUID: Task<String, Error>] = [:]
    private var suppressNextProfileRestore = false

    private enum SyncPolicy {
        static let catalogAutoRefresh: TimeInterval = 7 * 24 * 3600
        static let catalogManualCooldown: TimeInterval = 10 * 60
        static let statsAutoRefresh: TimeInterval = 5 * 24 * 3600
        static let statsManualCooldown: TimeInterval = 5 * 60
        static let appUpdateCheckInterval: TimeInterval = 24 * 3600
        static let bootstrapDelaySeconds: Int = 3
        static let oauthExpiryBufferSeconds: TimeInterval = 30
    }

    // MARK: - User Preferences & Multi-Provider Score Profiles
    var favoriteIds: Set<String> {
        didSet { UserDefaults.standard.set(Array(favoriteIds), forKey: Keys.favorites) }
    }
    var searchHistory: [String] {
        didSet { UserDefaults.standard.set(searchHistory, forKey: Keys.searchHistory) }
    }
    var profiles: [ScoreProfile] {
        didSet { AccountCredentialStore.saveProfiles(profiles) }
    }
    var activeProfileId: UUID? {
        didSet {
            guard oldValue != activeProfileId else { return }
            UserDefaults.standard.set(activeProfileId?.uuidString, forKey: Keys.activeProfileId)
            if suppressNextProfileRestore {
                suppressNextProfileRestore = false
            } else {
                restoreCachedRecords()
            }
        }
    }
    var activeProfile: ScoreProfile? {
        guard let activeProfileId else { return nil }
        return profiles.first(where: { $0.id == activeProfileId })
    }

    var dataVersion: String {
        didSet { UserDefaults.standard.set(dataVersion, forKey: Keys.dataVersion) }
    }
    var serverRegion: MaimaiConstants.ServerRegion {
        didSet { UserDefaults.standard.set(serverRegion.rawValue, forKey: Keys.serverRegion) }
    }
    var lastCNCatalogSync: Date? {
        didSet { UserDefaults.standard.set(lastCNCatalogSync?.timeIntervalSince1970, forKey: Keys.cnCatalogSync) }
    }
    var lastZetarakuSync: Date? {
        didSet { UserDefaults.standard.set(lastZetarakuSync?.timeIntervalSince1970, forKey: Keys.zetarakuSync) }
    }
    var lastChartStatsSync: Date? {
        didSet { UserDefaults.standard.set(lastChartStatsSync?.timeIntervalSince1970, forKey: Keys.chartStatsSync) }
    }

    // Settings
    var enableShowAlias: Bool {
        didSet { UserDefaults.standard.set(enableShowAlias, forKey: Keys.showAlias) }
    }
    var enableAliasSearch: Bool {
        didSet { UserDefaults.standard.set(enableAliasSearch, forKey: Keys.aliasSearch) }
    }
    var enableCharterSearch: Bool {
        didSet { UserDefaults.standard.set(enableCharterSearch, forKey: Keys.charterSearch) }
    }
    var useProberNickname: Bool {
        didSet { UserDefaults.standard.set(useProberNickname, forKey: Keys.useDFNickname) }
    }
    var customNickname: String {
        didSet { UserDefaults.standard.set(customNickname, forKey: Keys.customNickname) }
    }
    var lastVersionCheckIndex: Int {
        didSet { UserDefaults.standard.set(lastVersionCheckIndex, forKey: Keys.lastVersionCheck) }
    }
    var lastVersionCheckIntlIndex: Int {
        didSet { UserDefaults.standard.set(lastVersionCheckIntlIndex, forKey: Keys.lastVersionCheckIntl) }
    }
    var lastVersionCheckJpIndex: Int {
        didSet { UserDefaults.standard.set(lastVersionCheckJpIndex, forKey: Keys.lastVersionCheckJp) }
    }
    var lastLevelCheckIndex: Int {
        didSet { UserDefaults.standard.set(lastLevelCheckIndex, forKey: Keys.lastLevelCheck) }
    }

    var activeVersionCheckIndex: Int {
        get {
            switch serverRegion {
            case .cn: return lastVersionCheckIndex
            case .intl: return lastVersionCheckIntlIndex
            case .jp: return lastVersionCheckJpIndex
            }
        }
        set {
            switch serverRegion {
            case .cn: lastVersionCheckIndex = newValue
            case .intl: lastVersionCheckIntlIndex = newValue
            case .jp: lastVersionCheckJpIndex = newValue
            }
        }
    }

    /// Cached official CN plate rule groups + unified plate scopes for the active server region.
    /// Rebuilt whenever songs, CN baseline, plate catalog, or server region changes.
    private(set) var cnPlateCheckGroups: [CNPlateRuleGroup] = []
    private(set) var activePlateScopes: [PlateCheckScope] = []

    private func rebuildPlateScopeCache() {
        var list = plateCatalog?.groups ?? []
        let sourceSongs = cnSongs.isEmpty ? songs : cnSongs
        let newSongs = sourceSongs.filter { !$0.isUtage && $0.isDisabled != true && $0.basicInfo.isNew && $0.numericId > 0 }
        if !newSongs.isEmpty {
            let currentTitle = newSongs.first?.basicInfo.from ?? MaimaiConstants.latestCNVersion.name
            let alreadyCovered = list.contains { $0.title.contains(currentTitle) }
            if !alreadyCovered {
                let ids = newSongs.map(\.numericId)
                list.append(
                    CNPlateRuleGroup(
                        id: "current_\(currentTitle)",
                        title: currentTitle,
                        versionAsset: MaimaiConstants.cnVersionAsset(for: currentTitle) ?? MaimaiConstants.latestCNVersion.asset,
                        badges: [],
                        basicToMasterSongIds: ids,
                        remasterSongIds: []
                    )
                )
            }
        }
        cnPlateCheckGroups = list
        activePlateScopes = LXNSCatalogConverter.buildPlateScopes(
            for: serverRegion,
            cnPlateGroups: list,
            activeSongs: songs,
            zetarakuVersions: zetarakuCatalog?.versions
        )
    }

    var lastCatalogSyncText: String {
        let latest: Date? = {
            switch (lastCNCatalogSync, lastZetarakuSync) {
            case let (cn?, z?): return max(cn, z)
            case let (cn?, nil): return cn
            case let (nil, z?): return z
            case (nil, nil): return nil
            }
        }()
        return latest?.formatted(date: .abbreviated, time: .shortened) ?? "内置离线数据"
    }

    private typealias Keys = PreferenceKeys

    private static let seedDataVersion = "2026-11-18"
    private var didBootstrap = false

    private var appSupportDir: URL {
        Self.resolveAppSupportDir()
    }

    private static func resolveAppSupportDir() -> URL {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("MaimaiData", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private var cachedSongsURL: URL { appSupportDir.appendingPathComponent("songdata.json") }
    private var cachedPlatesURL: URL { appSupportDir.appendingPathComponent("plate_rules.json") }
    private var cachedZetarakuURL: URL { appSupportDir.appendingPathComponent("zetaraku_data.json") }
    private var cachedDXDataIdMapURL: URL { appSupportDir.appendingPathComponent("dxdata_id_map.json") }
    private var cachedStatsURL: URL { appSupportDir.appendingPathComponent("chart_stats.json") }
    private var cachedRecordsURL: URL? {
        guard let activeProfileId else { return nil }
        return AccountCredentialStore.recordsFileURL(for: activeProfileId, in: appSupportDir)
    }

    init() {
        let ud = UserDefaults.standard
        PreferenceKeys.registerDefaults(in: ud)
        let supportDir = Self.resolveAppSupportDir()

        self.favoriteIds = Set(ud.stringArray(forKey: Keys.favorites) ?? [])
        self.searchHistory = ud.stringArray(forKey: Keys.searchHistory) ?? []

        let loadedProfiles = AccountCredentialStore.loadOrMigrateProfiles(
            defaults: ud,
            appSupportDir: supportDir
        )
        self.profiles = loadedProfiles.profiles
        self.activeProfileId = loadedProfiles.activeProfileId

        self.dataVersion = ud.string(forKey: Keys.dataVersion) ?? Self.seedDataVersion
        self.serverRegion = ud.string(forKey: Keys.serverRegion)
            .flatMap(MaimaiConstants.ServerRegion.init(rawValue:)) ?? .cn

        if let cnTs = ud.object(forKey: Keys.cnCatalogSync) as? TimeInterval {
            self.lastCNCatalogSync = Date(timeIntervalSince1970: cnTs)
        } else {
            self.lastCNCatalogSync = nil
        }
        if let zTs = ud.object(forKey: Keys.zetarakuSync) as? TimeInterval {
            self.lastZetarakuSync = Date(timeIntervalSince1970: zTs)
        } else {
            self.lastZetarakuSync = nil
        }
        if let ts = ud.object(forKey: Keys.chartStatsSync) as? TimeInterval {
            self.lastChartStatsSync = Date(timeIntervalSince1970: ts)
        } else {
            self.lastChartStatsSync = nil
        }

        self.enableShowAlias = ud.bool(forKey: Keys.showAlias)
        self.enableAliasSearch = ud.bool(forKey: Keys.aliasSearch)
        self.enableCharterSearch = ud.bool(forKey: Keys.charterSearch)
        self.useProberNickname = ud.bool(forKey: Keys.useDFNickname)
        self.customNickname = ud.string(forKey: Keys.customNickname) ?? ""
        self.lastVersionCheckIndex = ud.integer(forKey: Keys.lastVersionCheck)
        self.lastVersionCheckIntlIndex = ud.integer(forKey: Keys.lastVersionCheckIntl)
        self.lastVersionCheckJpIndex = ud.integer(forKey: Keys.lastVersionCheckJp)
        self.lastLevelCheckIndex = ud.integer(forKey: Keys.lastLevelCheck)
    }

    // MARK: - Bootstrap & Local Caching

    private func loadLocalCaches() async {
        let songsURL = cachedSongsURL
        let platesURL = cachedPlatesURL
        let zetarakuURL = cachedZetarakuURL
        let dxIdMapURL = cachedDXDataIdMapURL
        let statsURL = cachedStatsURL
        let recordsURL = cachedRecordsURL

        let caches = await Task.detached(priority: .userInitiated) { () -> LocalCaches in
            var cache = LocalCaches()
            if let data = try? Data(contentsOf: songsURL) {
                cache.songs = try? JSONDecoder().decode([SongData].self, from: data)
            }
            if cache.songs == nil,
               let seedURL = Bundle.main.url(forResource: "songdata_seed", withExtension: "json"),
               let data = try? Data(contentsOf: seedURL),
               let decoded = try? JSONDecoder().decode([SongData].self, from: data) {
                try? data.write(to: songsURL, options: .atomic)
                cache.songs = decoded
                cache.usedSeed = true
            }

            if let pData = try? Data(contentsOf: platesURL) {
                cache.plates = try? JSONDecoder().decode(CNPlateCatalog.self, from: pData)
            }
            if cache.plates == nil,
               let pSeedURL = Bundle.main.url(forResource: "plate_rules_seed", withExtension: "json"),
               let pData = try? Data(contentsOf: pSeedURL),
               let pDecoded = try? JSONDecoder().decode(CNPlateCatalog.self, from: pData) {
                try? pData.write(to: platesURL, options: .atomic)
                cache.plates = pDecoded
            }

            if let zData = try? Data(contentsOf: zetarakuURL) {
                cache.zetaraku = try? JSONDecoder().decode(ZetarakuCatalog.self, from: zData)
            }
            if let idData = try? Data(contentsOf: dxIdMapURL) {
                cache.dxIdMap = try? JSONDecoder().decode(DXDataIdMap.self, from: idData)
            }
            if let data = try? Data(contentsOf: statsURL) {
                cache.stats = try? JSONDecoder().decode(ChartStatsResponse.self, from: data)
            }
            if let recordsURL, let data = try? Data(contentsOf: recordsURL) {
                cache.records = try? JSONDecoder().decode([ProberRecord].self, from: data)
            }
            return cache
        }.value

        let currentProfileId = activeProfileId
        plateCatalog = caches.plates
        zetarakuCatalog = caches.zetaraku
        dxDataIdMap = caches.dxIdMap
        var cnSnapshot: SongSnapshot?
        if let loadedCNSongs = caches.songs {
            cnSnapshot = await storeCNBaseline(loadedCNSongs)
            if caches.usedSeed { dataVersion = Self.seedDataVersion }
        }

        let epoch = regionEpoch
        switch serverRegion {
        case .cn:
            if let cnSnapshot {
                applySnapshot(cnSnapshot, epoch: epoch)
            }
        case .intl, .jp:
            if let catalog = zetarakuCatalog {
                await applyZetarakuCatalog(catalog, for: serverRegion, epoch: epoch)
            }
        }
        rebuildPlateScopeCache()

        if let stats = caches.stats { applyChartStats(stats) }
        if activeProfileId == currentProfileId && recordsEpoch == 0 {
            records = caches.records ?? []
        }
        isLoadingLocalData = false
        prefetchBest50Covers()
    }

    func bootstrap() async {
        guard !didBootstrap else { return }
        didBootstrap = true
        await loadLocalCaches()

        // If the active region is JP/INTL and has never cached Zetaraku yet, pull it once so the song list isn't empty.
        if serverRegion != .cn && zetarakuCatalog == nil {
            await syncZetarakuCatalog(force: false)
        }

        // Defer low-frequency periodic checks so app launch never bursts upstream APIs.
        try? await Task.sleep(for: .seconds(SyncPolicy.bootstrapDelaySeconds))
        async let periodicCatalogCheck: Void = syncAllCatalogsAndRules(force: false)
        async let periodicStatsCheck: Void = refreshChartStatsIfStale(force: false)
        async let periodicAppUpdateCheck: Void = checkAppUpdateIfNeeded(manual: false)
        _ = await (periodicCatalogCheck, periodicStatsCheck, periodicAppUpdateCheck)
    }

    // MARK: - App Version Update Check (24h Silent Auto-Check for Sideload + Manual Check in About)

    func checkAppUpdateIfNeeded(manual: Bool) async {
        guard !isCheckingAppUpdate else { return }
        let channel = IOSInstallChannel.current
        if !manual {
            // App Store and TestFlight have native Apple update notifications; only auto-prompt sideload/dev builds.
            guard channel == .sideloadOrDev else { return }
            let ud = UserDefaults.standard
            if let lastTs = ud.object(forKey: Keys.appUpdateCheck) as? TimeInterval,
               Date().timeIntervalSince1970 - lastTs < SyncPolicy.appUpdateCheckInterval {
                return
            }
        }

        isCheckingAppUpdate = true
        defer { isCheckingAppUpdate = false }

        do {
            let latest = try await MaimaiAPI.fetchLatestGitHubRelease(timeout: manual ? 12 : 6)
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Keys.appUpdateCheck)
            let currentShort = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"

            if let latest, latest.isNewer(than: currentShort) {
                let ignored = UserDefaults.standard.string(forKey: Keys.ignoredAppUpdateVersion)
                if manual || ignored != latest.version {
                    availableAppUpdate = latest
                }
            } else if manual {
                statusMessage = "当前已是最新版本（v\(currentShort)）"
            }
        } catch {
            if manual {
                statusMessage = "检查更新失败，请检查网络连接后重试"
            }
        }
    }

    func ignoreAppUpdate(version: String) {
        UserDefaults.standard.set(version, forKey: Keys.ignoredAppUpdateVersion)
        availableAppUpdate = nil
    }

    @discardableResult
    private func storeCNBaseline(_ rawList: [SongData]) async -> SongSnapshot {
        let zCatalog = zetarakuCatalog
        let snapshot = await Task.detached(priority: .userInitiated) {
            let withUtageMeta = ZetarakuCatalogConverter.enrichCNSongsWithUtageMetadata(rawList, from: zCatalog)
            return Self.buildSnapshotSync(from: withUtageMeta)
        }.value
        cnSongs = snapshot.songs
        cnSongsById = snapshot.songsById
        return snapshot
    }

    private func applySnapshot(_ snapshot: SongSnapshot, epoch: Int? = nil) {
        if let epoch, regionEpoch != epoch { return }
        songs = snapshot.songs
        songsById = snapshot.songsById
        maxNotesStats = snapshot.maxNotes
        catalogRevision &+= 1
        rebuildPlateScopeCache()
    }

    private func applySongs(_ list: [SongData], epoch: Int? = nil) async {
        let snapshot = await Self.buildSnapshot(from: list)
        applySnapshot(snapshot, epoch: epoch)
    }

    private func applyZetarakuCatalog(
        _ catalog: ZetarakuCatalog,
        for region: MaimaiConstants.ServerRegion,
        epoch: Int? = nil
    ) async {
        let cnRef = cnSongs
        let idMap = dxDataIdMap
        let (snapshot, migrations) = await Task.detached(priority: .userInitiated) { () -> (SongSnapshot, [(legacyId: String, resolvedId: String)]) in
            let output = ZetarakuCatalogConverter.convertWithMigrations(
                catalog,
                region: region,
                cnReference: cnRef,
                dxIdMap: idMap
            )
            return (Self.buildSnapshotSync(from: output.songs), output.migratedIdPairs)
        }.value
        if let epoch, regionEpoch != epoch { return }
        migrateFavoriteIdsIfNeeded(migrations)
        applySnapshot(snapshot, epoch: epoch)
    }

    /// Smoothly upgrades any favorited temporary key (`zeta_dx_*`) to its official numeric ID once online `dxdata` provides it.
    private func migrateFavoriteIdsIfNeeded(_ pairs: [(legacyId: String, resolvedId: String)]) {
        guard !favoriteIds.isEmpty, !pairs.isEmpty else { return }
        var updated = favoriteIds
        var changed = false
        for pair in pairs where updated.contains(pair.legacyId) {
            updated.remove(pair.legacyId)
            updated.insert(pair.resolvedId)
            changed = true
        }
        if changed {
            favoriteIds = updated
        }
    }

    private static func buildSnapshot(from list: [SongData]) async -> SongSnapshot {
        await Task.detached(priority: .userInitiated) {
            buildSnapshotSync(from: list)
        }.value
    }

    nonisolated private static func buildSnapshotSync(from list: [SongData]) -> SongSnapshot {
        let enriched = SongData.mergingCounterpartAliases(list)
        let ordered = enriched.sorted {
            if $0.defaultSortKey != $1.defaultSortKey { return $0.defaultSortKey > $1.defaultSortKey }
            return $0.id > $1.id
        }
        var map: [Int: SongData] = [:]
        map.reserveCapacity(ordered.count)
        for song in ordered where song.numericId > 0 {
            map[song.numericId] = song
        }
        return SongSnapshot(songs: ordered, songsById: map, maxNotes: MaxNotesStats.compute(from: ordered))
    }

    private func applyChartStats(_ response: ChartStatsResponse) {
        var map: [Int: [ChartStatEntry]] = [:]
        map.reserveCapacity(response.charts.count)
        for (key, value) in response.charts {
            if let id = Int(key) {
                map[id] = value
            }
        }
        self.chartStats = map
    }

    // MARK: - Server Region Switching & Remote Catalog Sync

    func switchServerRegion(_ newRegion: MaimaiConstants.ServerRegion) async {
        guard newRegion != serverRegion else { return }
        regionEpoch += 1
        let epoch = regionEpoch
        serverRegion = newRegion
        syncNotice = nil

        switch newRegion {
        case .cn:
            await applySongs(cnSongs, epoch: epoch)
        case .intl, .jp:
            if let catalog = zetarakuCatalog {
                await applyZetarakuCatalog(catalog, for: newRegion, epoch: epoch)
                await refreshZetarakuIfStale()
            } else {
                songs = []
                songsById = [:]
                catalogRevision &+= 1
                rebuildPlateScopeCache()
                await loadOrFetchZetaraku(for: newRegion, epoch: epoch)
            }
        }
        guard regionEpoch == epoch else { return }
        prefetchBest50Covers()
    }

    private func loadOrFetchZetaraku(for region: MaimaiConstants.ServerRegion, epoch: Int) async {
        let zURL = cachedZetarakuURL
        let idURL = cachedDXDataIdMapURL
        let (diskCatalog, diskIdMap) = await Task.detached(priority: .userInitiated, operation: { () -> (ZetarakuCatalog?, DXDataIdMap?) in
            let z = (try? Data(contentsOf: zURL)).flatMap { try? JSONDecoder().decode(ZetarakuCatalog.self, from: $0) }
            let m = (try? Data(contentsOf: idURL)).flatMap { try? JSONDecoder().decode(DXDataIdMap.self, from: $0) }
            return (z, m)
        }).value
        if let diskIdMap, dxDataIdMap == nil {
            dxDataIdMap = diskIdMap
        }
        if let diskCatalog {
            guard regionEpoch == epoch else { return }
            zetarakuCatalog = diskCatalog
            await applyZetarakuCatalog(diskCatalog, for: region, epoch: epoch)
            await refreshZetarakuIfStale()
            return
        }
        await syncZetarakuCatalog(force: false)
    }

    /// Unified full catalog & rules sync (LXNS CN songs + aliases + plates, plus Zetaraku JP/INTL catalog + DXData IDs)
    /// with strict multi-layer throttling:
    /// - Cold install uses bundled seeds without hitting upstream servers;
    /// - Automatic periodic sync runs at most once every 7 days;
    /// - Manual "检查更新" enforces a 10-minute cooldown when all local caches are already fresh.
    func syncAllCatalogsAndRules(force: Bool = false) async {
        guard !isSyncingSongs else { return }

        if force {
            if let lastCN = lastCNCatalogSync,
               let lastZ = lastZetarakuSync,
               Date().timeIntervalSince(min(lastCN, lastZ)) < SyncPolicy.catalogManualCooldown,
               !cnSongs.isEmpty,
               plateCatalog != nil,
               zetarakuCatalog != nil {
                statusMessage = "当前已是最新数据"
                return
            }
        } else {
            if lastCNCatalogSync == nil, !cnSongs.isEmpty, plateCatalog != nil {
                // Record seed baseline date on first launch; do not burst APIs on cold install.
                lastCNCatalogSync = Date()
                return
            }
            let cnFresh = lastCNCatalogSync.map { Date().timeIntervalSince($0) < SyncPolicy.catalogAutoRefresh } ?? false
            let zFresh = (serverRegion == .cn && zetarakuCatalog == nil)
                || (lastZetarakuSync.map { Date().timeIntervalSince($0) < SyncPolicy.catalogAutoRefresh } ?? false)
            if cnFresh && zFresh && !cnSongs.isEmpty && plateCatalog != nil {
                return
            }
        }

        async let cnOutcome: (songs: [SongData]?, error: Error?) = performLXNSCatalogSync()
        async let zOutcome: Error? = performZetarakuSync()
        let (cnResult, zErr) = await (cnOutcome, zOutcome)
        let cnErr = cnResult.error

        // Build CN baseline once after both LXNS and Zetaraku/DXData have settled, then apply active region once.
        let cnSource = cnResult.songs ?? cnSongs
        let cnSnapshot: SongSnapshot? = cnSource.isEmpty ? nil : await storeCNBaseline(cnSource)
        switch serverRegion {
        case .cn:
            if let cnSnapshot {
                applySnapshot(cnSnapshot, epoch: regionEpoch)
            } else {
                rebuildPlateScopeCache()
            }
        case .intl, .jp:
            if let catalog = zetarakuCatalog {
                await applyZetarakuCatalog(catalog, for: serverRegion, epoch: regionEpoch)
            } else {
                rebuildPlateScopeCache()
            }
        }

        if force {
            if cnErr == nil && zErr == nil {
                statusMessage = "已更新至最新数据"
                syncNotice = nil
            } else if let err = cnErr ?? zErr {
                statusMessage = "部分数据同步失败：\(err.localizedDescription)"
                syncNotice = DataSyncNotice(
                    kind: .songs,
                    message: "曲库与规则同步未完全成功，正在使用本地缓存数据。\n\(err.localizedDescription)"
                )
            }
        }
    }

    private func refreshZetarakuIfStale() async {
        if let last = lastZetarakuSync,
           Date().timeIntervalSince(last) < SyncPolicy.catalogAutoRefresh,
           zetarakuCatalog != nil,
           dxDataIdMap != nil {
            return
        }
        await syncZetarakuCatalog(force: false)
    }

    func syncZetarakuCatalog(force: Bool = false) async {
        let err = await performZetarakuSync()
        if let catalog = zetarakuCatalog, serverRegion != .cn {
            await applyZetarakuCatalog(catalog, for: serverRegion, epoch: regionEpoch)
        }
        if let err, serverRegion != .cn {
            statusMessage = force ? "曲库同步失败：\(err.localizedDescription)" : nil
            syncNotice = DataSyncNotice(
                kind: .songs,
                message: "\(serverRegion.shortName)曲库获取失败，正在使用本地缓存。\n\(err.localizedDescription)"
            )
        }
    }

    private func performZetarakuSync() async -> Error? {
        guard !isFetchingZetaraku else { return nil }
        isFetchingZetaraku = true
        defer { isFetchingZetaraku = false }

        async let zetarakuTask: Result<(raw: Data, catalog: ZetarakuCatalog), Error> = {
            do {
                return .success(try await MaimaiAPI.fetchZetarakuCatalog())
            } catch {
                return .failure(error)
            }
        }()
        async let dxIdTask: DXDataIdMap? = try? await MaimaiAPI.fetchDXDataIdMap()

        let (zResult, fetchedIdMap) = await (zetarakuTask, dxIdTask)

        if let fetchedIdMap {
            dxDataIdMap = fetchedIdMap
            if let encoded = try? JSONEncoder().encode(fetchedIdMap) {
                try? encoded.write(to: cachedDXDataIdMapURL, options: .atomic)
            }
        }

        switch zResult {
        case .success(let (raw, decoded)):
            zetarakuCatalog = decoded
            if (try? raw.write(to: cachedZetarakuURL, options: .atomic)) != nil {
                lastZetarakuSync = Date()
            }
            return nil
        case .failure(let error):
            return error
        }
    }

    private func performLXNSCatalogSync() async -> (songs: [SongData]?, error: Error?) {
        guard !isFetchingCNSongs else { return (nil, nil) }
        isFetchingCNSongs = true
        defer { isFetchingCNSongs = false }
        do {
            // Sequential requests stay well under LXNS's 5 req/s rate limit and avoid Cloudflare burst throttling.
            let songResp = try await MaimaiAPI.fetchLXNSSongCatalog()
            let aliasResp = try? await MaimaiAPI.fetchLXNSAliases()
            let plateResp = try? await MaimaiAPI.fetchLXNSPlates()

            let baselineSnapshot = cnSongs
            let (convertedSongs, convertedPlates, stamp) = await Task.detached(priority: .userInitiated) { () -> ([SongData], CNPlateCatalog?, String) in
                let catalogResult = LXNSCatalogConverter.convertCatalog(
                    songResponse: songResp,
                    aliasResponse: aliasResp,
                    existingBaseline: baselineSnapshot
                )
                let platesResult = plateResp.map { LXNSCatalogConverter.extractPlateCatalog(from: $0) }
                let formatter = DateFormatter()
                formatter.calendar = Calendar(identifier: .gregorian)
                formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.dateFormat = "yyyy-MM-dd"
                return (catalogResult.songs, platesResult, formatter.string(from: Date()))
            }.value

            let encodedSongs = try JSONEncoder().encode(convertedSongs)
            try encodedSongs.write(to: cachedSongsURL, options: .atomic)
            if let convertedPlates, let encodedPlates = try? JSONEncoder().encode(convertedPlates) {
                try? encodedPlates.write(to: cachedPlatesURL, options: .atomic)
                plateCatalog = convertedPlates
            }

            dataVersion = stamp
            lastCNCatalogSync = Date()
            return (convertedSongs, nil)
        } catch {
            return (nil, error)
        }
    }

    func refreshChartStatsIfStale(force: Bool = false) async {
        guard !isSyncingStats else { return }
        if force {
            if let last = lastChartStatsSync, Date().timeIntervalSince(last) < SyncPolicy.statsManualCooldown, !chartStats.isEmpty {
                statusMessage = "当前已是最新数据"
                return
            }
        } else {
            if let last = lastChartStatsSync, Date().timeIntervalSince(last) < SyncPolicy.statsAutoRefresh, !chartStats.isEmpty {
                return
            }
        }
        isSyncingStats = true
        defer { isSyncingStats = false }
        do {
            let (raw, decoded) = try await MaimaiAPI.fetchChartStats()
            try raw.write(to: cachedStatsURL, options: .atomic)
            lastChartStatsSync = Date()
            applyChartStats(decoded)
            if force {
                statusMessage = "拟合定数已刷新"
                syncNotice = nil
            }
        } catch {
            statusMessage = force ? "拟合定数获取失败：\(error.localizedDescription)" : nil
            syncNotice = DataSyncNotice(
                kind: .stats,
                message: "拟合定数获取失败，正在使用本地数据。可前往「设置与数据」重试。"
            )
        }
    }

    // MARK: - Multi-Provider Score Profile Connection & Sync

    func switchProfile(to profileId: UUID) {
        guard profiles.contains(where: { $0.id == profileId }), activeProfileId != profileId else { return }
        activeProfileId = profileId
    }

    func removeProfile(id profileId: UUID) {
        let wasActive = (activeProfileId == profileId)
        inFlightTokenRefreshTasks.removeValue(forKey: profileId)?.cancel()
        AccountCredentialStore.deleteSecret(for: profileId)
        let recordFile = AccountCredentialStore.recordsFileURL(for: profileId, in: appSupportDir)
        try? FileManager.default.removeItem(at: recordFile)
        profiles.removeAll { $0.id == profileId }
        if wasActive {
            activeProfileId = profiles.first?.id
            if activeProfileId == nil {
                recordsEpoch += 1
                records = []
            }
        }
    }

    /// 1. Diving-Fish Username + Password
    func connectDivingFishPassword(username: String, password: String) async throws {
        guard !isSyncingRecords else { throw MaimaiAPIError.syncInProgress }
        isSyncingRecords = true
        defer { isSyncingRecords = false }

        let trimmedUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
        let cookie = try await MaimaiAPI.login(username: trimmedUser, password: password)

        let profile = upsertProfile(
            provider: .divingFish,
            authMethod: .divingFishPassword,
            accountKey: trimmedUser,
            username: trimmedUser,
            nickname: nil
        )
        AccountCredentialStore.saveSecret(
            ScoreProfileSecret(password: password, cookie: cookie),
            for: profile.id
        )
        activeProfileId = profile.id

        do {
            try await performSyncRecords(for: profile.id)
        } catch {
            throw MaimaiAPIError.recordSyncFailedAfterLogin(error.localizedDescription)
        }
    }

    /// 2. Diving-Fish Import-Token (`Import-Token` header)
    func connectDivingFishImportToken(_ rawToken: String, remarkName: String = "") async throws {
        guard !isSyncingRecords else { throw MaimaiAPIError.syncInProgress }
        isSyncingRecords = true
        defer { isSyncingRecords = false }

        let token = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let resp = try await Task {
            try await MaimaiAPI.fetchPlayerRecords(importToken: token)
        }.value

        let resolvedUsername = (resp.username?.isEmpty == false)
            ? resp.username!
            : remarkName.trimmingCharacters(in: .whitespacesAndNewlines)
        let accountKey = (resp.username?.isEmpty == false)
            ? resp.username!
            : "df_token_\(token.sha256Hex.prefix(16))"
        let resolvedNickname = (resp.nickname?.isEmpty == false)
            ? resp.nickname!
            : remarkName.trimmingCharacters(in: .whitespacesAndNewlines)

        let profile = upsertProfile(
            provider: .divingFish,
            authMethod: .divingFishImportToken,
            accountKey: accountKey,
            username: resolvedUsername,
            nickname: resolvedNickname,
            syncedAt: Date()
        )
        AccountCredentialStore.saveSecret(
            ScoreProfileSecret(importToken: token),
            for: profile.id
        )
        installFetchedRecords(resp.records ?? [], for: profile.id)
    }

    /// 3. LXNS OAuth 2.0 + PKCE (`ASWebAuthenticationSession`)
    func connectLXNSOAuth(using webAuthRunner: WebAuthSessionRunner) async throws {
        guard !isSyncingRecords else { throw MaimaiAPIError.syncInProgress }

        let verifier = PKCEHelper.generateCodeVerifier()
        let challenge = PKCEHelper.codeChallengeS256(for: verifier)
        let state = PKCEHelper.randomURLSafeString(byteCount: 16)
        let nonce = PKCEHelper.randomURLSafeString(byteCount: 16)
        guard let authorizeURL = MaimaiAPI.buildLXNSAuthorizeURL(
            state: state,
            nonce: nonce,
            codeChallenge: challenge
        ) else {
            throw MaimaiAPIError.invalidURL
        }

        // Present system Safari authorization sheet before marking `isSyncingRecords = true`
        let callbackURL = try await webAuthRunner.authenticate(
            url: authorizeURL,
            callbackScheme: LXNSConfiguration.callbackScheme
        )
        let code = try MaimaiAPI.parseOAuthCallback(url: callbackURL, expectedState: state)

        isSyncingRecords = true
        defer { isSyncingRecords = false }

        let tokenResp = try await MaimaiAPI.exchangeLXNSCode(code: code, codeVerifier: verifier)
        let expiresAt = Date().timeIntervalSince1970 + Double(tokenResp.expiresIn)
        let userInfo = try? await MaimaiAPI.fetchLXNSUserInfo(accessToken: tokenResp.accessToken)

        let accountKey: String = {
            if let sub = userInfo?.sub, !sub.isEmpty {
                return "oidc_\(sub)"
            }
            return "lxns_oauth_\(UUID().uuidString)"
        }()
        let lxUsername = userInfo?.displayUsername ?? ""

        // Persist OAuth tokens immediately to Keychain before fetching large score payload
        let initialProfile = upsertProfile(
            provider: .lxns,
            authMethod: .lxnsOAuth,
            accountKey: accountKey,
            username: lxUsername,
            nickname: nil
        )
        AccountCredentialStore.saveSecret(
            ScoreProfileSecret(
                accessToken: tokenResp.accessToken,
                refreshToken: tokenResp.refreshToken ?? "",
                accessTokenExpiresAt: expiresAt
            ),
            for: initialProfile.id
        )
        activeProfileId = initialProfile.id

        do {
            let (player, lxScores) = try await Task {
                try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: tokenResp.accessToken)
            }.value
            let lookup = mergedSongLookupForScoreImport()
            let converted = await Task.detached(priority: .userInitiated) {
                LXNSCatalogConverter.convertScores(lxScores, songsById: lookup)
            }.value
            updateProfileMetadata(
                id: initialProfile.id,
                username: lxUsername,
                nickname: player?.name ?? lxUsername,
                syncedAt: Date()
            )
            installFetchedRecords(converted, for: initialProfile.id)
        } catch {
            throw MaimaiAPIError.recordSyncFailedAfterLogin(error.localizedDescription)
        }
    }

    /// 4. LXNS Personal API Key (`X-User-Token`)
    func connectLXNSPersonalToken(_ rawToken: String, remarkName: String = "") async throws {
        guard !isSyncingRecords else { throw MaimaiAPIError.syncInProgress }
        isSyncingRecords = true
        defer { isSyncingRecords = false }

        let token = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
        let (player, lxScores) = try await Task {
            try await MaimaiAPI.fetchLXNSPlayerAndScores(personalToken: token)
        }.value

        let trimmedRemark = remarkName.trimmingCharacters(in: .whitespacesAndNewlines)
        let accountKey = player?.friendCode.map { "fc_\($0)" } ?? "lxns_pt_\(token.sha256Hex.prefix(16))"
        let nickname = (player?.name.isEmpty == false) ? player!.name : trimmedRemark

        let lookup = mergedSongLookupForScoreImport()
        let converted = await Task.detached(priority: .userInitiated) {
            LXNSCatalogConverter.convertScores(lxScores, songsById: lookup)
        }.value

        let profile = upsertProfile(
            provider: .lxns,
            authMethod: .lxnsPersonalToken,
            accountKey: accountKey,
            username: trimmedRemark,
            nickname: nickname,
            syncedAt: Date()
        )
        AccountCredentialStore.saveSecret(
            ScoreProfileSecret(personalToken: token),
            for: profile.id
        )
        installFetchedRecords(converted, for: profile.id)
    }

    /// Syncs fresh scores from the active profile's provider (Diving-Fish or LXNS).
    func syncActiveProfileRecords() async throws {
        guard !isSyncingRecords else { return }
        guard let profile = activeProfile else {
            throw MaimaiAPIError.unauthorized("请先添加查分器成绩档案")
        }
        isSyncingRecords = true
        defer { isSyncingRecords = false }

        try await performSyncRecords(for: profile.id)
    }

    private func performSyncRecords(for profileId: UUID) async throws {
        guard let profile = profiles.first(where: { $0.id == profileId }) else { return }
        var secret = AccountCredentialStore.loadSecret(for: profileId)

        switch profile.authMethod {
        case .divingFishPassword:
            let username = profile.username
            let password = secret.password
            var cookie = secret.cookie

            // Unstructured Task avoids SwiftUI `.refreshable` cancelling URLSession mid-flight.
            let (resp, usedCookie) = try await Task { () -> (ProberRecordsResponse, String) in
                if cookie.isEmpty {
                    guard !username.isEmpty, !password.isEmpty else {
                        throw MaimaiAPIError.unauthorized("水鱼登录已过期，请重新登录")
                    }
                    cookie = try await MaimaiAPI.login(username: username, password: password)
                }
                do {
                    let r = try await MaimaiAPI.fetchPlayerRecords(cookie: cookie)
                    return (r, cookie)
                } catch where MaimaiAPI.shouldRetryLogin(after: error) {
                    guard !username.isEmpty, !password.isEmpty else { throw error }
                    let newCookie = try await MaimaiAPI.login(username: username, password: password)
                    let r = try await MaimaiAPI.fetchPlayerRecords(cookie: newCookie)
                    return (r, newCookie)
                }
            }.value

            if usedCookie != secret.cookie {
                secret.cookie = usedCookie
                AccountCredentialStore.saveSecret(secret, for: profileId)
            }
            updateProfileMetadata(
                id: profileId,
                username: resp.username ?? profile.username,
                nickname: resp.nickname,
                syncedAt: Date()
            )
            installFetchedRecords(resp.records ?? [], for: profileId)

        case .divingFishImportToken:
            let token = secret.importToken
            guard !token.isEmpty else {
                throw MaimaiAPIError.unauthorized("未找到保存的水鱼导入 Token，请重新添加档案")
            }
            let resp = try await Task {
                try await MaimaiAPI.fetchPlayerRecords(importToken: token)
            }.value
            updateProfileMetadata(
                id: profileId,
                username: resp.username ?? profile.username,
                nickname: resp.nickname,
                syncedAt: Date()
            )
            installFetchedRecords(resp.records ?? [], for: profileId)

        case .lxnsOAuth:
            let initialAccessToken = try await validLXNSAccessToken(for: profileId, forceRefresh: false)
            let (player, lxScores): (LXNSPlayer?, [LXNSScore])
            do {
                (player, lxScores) = try await Task {
                    try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: initialAccessToken)
                }.value
            } catch MaimaiAPIError.unauthorized {
                let refreshedAccessToken = try await validLXNSAccessToken(
                    for: profileId,
                    forceRefresh: true,
                    failedAccessToken: initialAccessToken
                )
                (player, lxScores) = try await Task {
                    try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: refreshedAccessToken)
                }.value
            }

            let lookup = mergedSongLookupForScoreImport()
            let converted = await Task.detached(priority: .userInitiated) {
                LXNSCatalogConverter.convertScores(lxScores, songsById: lookup)
            }.value
            updateProfileMetadata(
                id: profileId,
                username: profile.username,
                nickname: player?.name,
                syncedAt: Date()
            )
            installFetchedRecords(converted, for: profileId)

        case .lxnsPersonalToken:
            let token = secret.personalToken
            guard !token.isEmpty else {
                throw MaimaiAPIError.unauthorized("未找到保存的落雪个人密钥，请重新添加档案")
            }
            let (player, lxScores) = try await Task {
                try await MaimaiAPI.fetchLXNSPlayerAndScores(personalToken: token)
            }.value
            let lookup = mergedSongLookupForScoreImport()
            let converted = await Task.detached(priority: .userInitiated) {
                LXNSCatalogConverter.convertScores(lxScores, songsById: lookup)
            }.value
            updateProfileMetadata(
                id: profileId,
                username: profile.username,
                nickname: player?.name,
                syncedAt: Date()
            )
            installFetchedRecords(converted, for: profileId)
        }
    }

    /// Serializes LXNS refresh-token exchange per profile and persists the rotated `refresh_token` immediately.
    private func validLXNSAccessToken(
        for profileId: UUID,
        forceRefresh: Bool,
        failedAccessToken: String? = nil
    ) async throws -> String {
        if let existingTask = inFlightTokenRefreshTasks[profileId] {
            return try await existingTask.value
        }

        let currentSecret = AccountCredentialStore.loadSecret(for: profileId)
        let alreadyRotatedSinceFailure = failedAccessToken != nil
            && !currentSecret.accessToken.isEmpty
            && currentSecret.accessToken != failedAccessToken
        if (!forceRefresh || alreadyRotatedSinceFailure),
           !currentSecret.accessToken.isEmpty,
           let expiresAt = currentSecret.accessTokenExpiresAt,
           Date().timeIntervalSince1970 < expiresAt - SyncPolicy.oauthExpiryBufferSeconds {
            return currentSecret.accessToken
        }

        guard !currentSecret.refreshToken.isEmpty else {
            throw MaimaiAPIError.unauthorized("落雪授权已过期，请重新授权登录")
        }

        let refreshTokenToUse = currentSecret.refreshToken
        let task = Task<String, Error> { @MainActor in
            defer { inFlightTokenRefreshTasks[profileId] = nil }
            let refreshed = try await MaimaiAPI.refreshLXNSToken(refreshToken: refreshTokenToUse)
            guard profiles.contains(where: { $0.id == profileId }) else {
                throw MaimaiAPIError.unauthorized("该成绩档案已被移除")
            }
            var updated = AccountCredentialStore.loadSecret(for: profileId)
            updated.accessToken = refreshed.accessToken
            if let newRefresh = refreshed.refreshToken, !newRefresh.isEmpty {
                updated.refreshToken = newRefresh
            }
            updated.accessTokenExpiresAt = Date().timeIntervalSince1970 + Double(refreshed.expiresIn)
            // Persist rotated refresh token synchronously before returning; retry once on transient Keychain error.
            let persisted = AccountCredentialStore.saveSecret(updated, for: profileId)
                || AccountCredentialStore.saveSecret(updated, for: profileId)
            guard persisted else {
                throw MaimaiAPIError.unauthorized("落雪授权令牌保存失败，请重新授权登录")
            }
            return refreshed.accessToken
        }
        inFlightTokenRefreshTasks[profileId] = task
        return try await task.value
    }

    private func mergedSongLookupForScoreImport() -> [Int: SongData] {
        if cnSongsById.isEmpty { return songsById }
        return songsById.merging(cnSongsById) { _, cn in cn }
    }

    private func upsertProfile(
        provider: ScoreProvider,
        authMethod: ScoreAuthMethod,
        accountKey: String,
        username: String,
        nickname: String?,
        syncedAt: Date? = nil
    ) -> ScoreProfile {
        var list = profiles
        if let idx = list.firstIndex(where: { $0.provider == provider && $0.accountKey == accountKey }) {
            list[idx].authMethod = authMethod
            if !username.isEmpty { list[idx].username = username }
            if let nickname, !nickname.isEmpty { list[idx].nickname = nickname }
            if let syncedAt { list[idx].lastSyncedAt = syncedAt }
            profiles = list
            return list[idx]
        } else {
            let created = ScoreProfile(
                id: UUID(),
                provider: provider,
                authMethod: authMethod,
                accountKey: accountKey,
                username: username,
                nickname: nickname ?? "",
                lastSyncedAt: syncedAt
            )
            list.append(created)
            profiles = list
            return created
        }
    }

    private func updateProfileMetadata(
        id: UUID,
        username: String?,
        nickname: String?,
        syncedAt: Date
    ) {
        guard let idx = profiles.firstIndex(where: { $0.id == id }) else { return }
        var list = profiles
        if let username, !username.isEmpty {
            list[idx].username = username
        }
        if let nickname, !nickname.isEmpty {
            list[idx].nickname = nickname
        }
        list[idx].lastSyncedAt = syncedAt
        profiles = list
    }

    private func installFetchedRecords(_ fetched: [ProberRecord], for profileId: UUID) {
        let fileURL = AccountCredentialStore.recordsFileURL(for: profileId, in: appSupportDir)
        if let encoded = try? JSONEncoder().encode(fetched) {
            try? encoded.write(to: fileURL, options: .atomic)
        }
        if activeProfileId != profileId {
            suppressNextProfileRestore = true
            activeProfileId = profileId
        }
        recordsEpoch += 1
        self.records = fetched
        prefetchBest50Covers()
    }

    private func prefetchBest50Covers() {
        let split = best35AndBest15()
        let b50Songs = (split.b35 + split.b15).compactMap { songsById[$0.songId] }
        guard !b50Songs.isEmpty else { return }
        Task(priority: .utility) {
            for song in b50Songs {
                _ = await CoverImageCache.shared.loadCover(song: song)
            }
        }
    }

    private func restoreCachedRecords() {
        let targetProfileId = activeProfileId
        recordsEpoch += 1
        let epoch = recordsEpoch
        records = []
        guard let targetProfileId else { return }
        let url = AccountCredentialStore.recordsFileURL(for: targetProfileId, in: appSupportDir)
        Task(priority: .userInitiated) {
            let cached = await Task.detached(priority: .utility) { () -> [ProberRecord]? in
                guard let data = try? Data(contentsOf: url) else { return nil }
                return try? JSONDecoder().decode([ProberRecord].self, from: data)
            }.value
            guard activeProfileId == targetProfileId,
                  recordsEpoch == epoch else { return }
            records = cached ?? []
            prefetchBest50Covers()
        }
    }

    // MARK: - Search, Filter & Favorites

    func isFavorite(_ song: SongData) -> Bool {
        favoriteIds.contains(song.id)
    }

    func toggleFavorite(_ song: SongData) {
        if favoriteIds.contains(song.id) {
            favoriteIds.remove(song.id)
        } else {
            favoriteIds.insert(song.id)
        }
    }

    func addSearchHistory(_ term: String) {
        let trimmed = term.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        var list = searchHistory.filter { $0 != trimmed }
        list.insert(trimmed, at: 0)
        if list.count > 20 { list = Array(list.prefix(20)) }
        searchHistory = list
    }

    func clearSearchHistory() {
        searchHistory = []
    }

    func filteredSongs(criteria: SongFilterCriteria) -> [SongData] {
        SongFilter.apply(
            criteria,
            to: songs,
            favoriteIds: favoriteIds,
            enableAliasSearch: enableAliasSearch,
            enableCharterSearch: enableCharterSearch,
            region: serverRegion
        )
    }

    // MARK: - Records & Best50 Helpers

    var isLoggedIn: Bool {
        activeProfile != nil
    }

    /// Finds the SD <-> DX counterpart of the same song (`id ± 10000` or same title+genre in JP/INTL), if present.
    func counterpartSong(for song: SongData) -> SongData? {
        guard !song.isUtage else { return nil }
        if song.numericId > 0 {
            let targetId = song.numericId < 10_000 ? song.numericId + 10_000 : song.numericId - 10_000
            if let candidate = songsById[targetId],
               !candidate.isUtage,
               candidate.title == song.title,
               candidate.isDX != song.isDX {
                return candidate
            }
        }
        return songs.first { candidate in
            !candidate.isUtage &&
            candidate.isDX != song.isDX &&
            candidate.title == song.title &&
            candidate.basicInfo.genre == song.basicInfo.genre
        }
    }

    /// Baseline single-song rating a chart must exceed to increase total B50 rating.
    /// Returns `nil` when no score profile is logged in.
    func b50BaselineRating(forSong song: SongData, difficultyIndex: Int, summary: Best50Summary? = nil) -> Int? {
        guard isLoggedIn, !song.isUtage, song.isDisabled != true else { return nil }
        let resolved = summary ?? best35AndBest15()
        let bucket = song.basicInfo.isNew ? resolved.b15 : resolved.b35
        let limit = song.basicInfo.isNew ? 15 : 35
        if song.numericId > 0,
           let existing = bucket.first(where: { $0.songId == song.numericId && $0.levelIndex == difficultyIndex }) {
            return existing.computedRating
        }
        if bucket.count >= limit {
            return bucket.last?.computedRating ?? 0
        }
        return 0
    }

    func record(forSongId songId: Int, difficultyIndex: Int) -> ProberRecord? {
        guard songId > 0 else { return nil }
        return recordsByKey["\(songId)_\(difficultyIndex)"]
    }

    func sortedRecords(isNewVersion: Bool) -> [ProberRecord] {
        let region = serverRegion
        return records.compactMap { rec in
            guard let song = songsById[rec.songId],
                  !song.isUtage,
                  song.isDisabled != true,
                  rec.levelIndex < song.ds.count,
                  song.basicInfo.isNew == isNewVersion else {
                return nil
            }
            return rec.projected(onto: song, region: region)
        }.sorted(by: Self.recordOrder)
    }

    func best35AndBest15() -> Best50Summary {
        let region = serverRegion
        var matching = true
        var oldList: [ProberRecord] = []
        var newList: [ProberRecord] = []
        for rec in records {
            guard let song = songsById[rec.songId] else {
                if region == .cn || cnSongsById[rec.songId] == nil {
                    matching = false
                }
                continue
            }
            guard !song.isUtage, song.isDisabled != true, rec.levelIndex < song.ds.count else { continue }
            let projected = rec.projected(onto: song, region: region)
            if song.basicInfo.isNew {
                newList.append(projected)
            } else {
                oldList.append(projected)
            }
        }
        oldList.sort(by: Self.recordOrder)
        newList.sort(by: Self.recordOrder)
        let b35 = Array(oldList.prefix(35))
        let b15 = Array(newList.prefix(15))
        return Best50Summary(b35: b35, b15: b15, isMatching: matching)
    }

    private static func recordOrder(_ lhs: ProberRecord, _ rhs: ProberRecord) -> Bool {
        if lhs.computedRating != rhs.computedRating {
            return lhs.computedRating > rhs.computedRating
        }
        if lhs.achievements != rhs.achievements {
            return lhs.achievements > rhs.achievements
        }
        return lhs.ds > rhs.ds
    }

    var displayPlayerName: String {
        if !useProberNickname {
            let trimmed = customNickname.trimmingCharacters(in: .whitespacesAndNewlines)
            if !trimmed.isEmpty {
                return trimmed
            }
        }
        if let profile = activeProfile {
            return profile.displayTitle
        }
        return "Player"
    }
}

private struct LocalCaches {
    var songs: [SongData]?
    var plates: CNPlateCatalog?
    var zetaraku: ZetarakuCatalog?
    var dxIdMap: DXDataIdMap?
    var stats: ChartStatsResponse?
    var records: [ProberRecord]?
    var usedSeed = false
}

private struct SongSnapshot {
    let songs: [SongData]
    let songsById: [Int: SongData]
    let maxNotes: MaxNotesStats
}

struct Best50Summary {
    let b35: [ProberRecord]
    let b15: [ProberRecord]
    let isMatching: Bool
    let b35Rating: Int
    let b15Rating: Int

    var totalRating: Int { b35Rating + b15Rating }

    init(b35: [ProberRecord], b15: [ProberRecord], isMatching: Bool) {
        self.b35 = b35
        self.b15 = b15
        self.isMatching = isMatching
        self.b35Rating = b35.reduce(0) { $0 + $1.computedRating }
        self.b15Rating = b15.reduce(0) { $0 + $1.computedRating }
    }
}

struct DataSyncNotice: Identifiable, Equatable {
    enum Kind: Equatable {
        case songs
        case stats
    }

    let id = UUID()
    let kind: Kind
    let message: String
}
