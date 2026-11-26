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
    private(set) var deletedSongsById: [Int: SongData] = [:]
    private var unresolvableDeletedSongIds: Set<Int> = []
    private(set) var playedSongIds: Set<Int> = []
    private(set) var plateCatalog: CNPlateCatalog?
    private var zetarakuCatalog: ZetarakuCatalog?
    private var dxDataIdMap: DXDataIdMap?
    private var regionEpoch = 0
    private(set) var chartStats: [Int: [ChartStatEntry]] = [:]
    private(set) var records: [ProberRecord] = [] {
        didSet {
            var map: [String: ProberRecord] = [:]
            var songIds = Set<Int>()
            map.reserveCapacity(records.count)
            songIds.reserveCapacity(records.count)
            for rec in records {
                map["\(rec.songId)_\(rec.levelIndex)"] = rec
                if rec.songId > 0 {
                    songIds.insert(rec.songId)
                }
            }
            recordsByKey = map
            playedSongIds = songIds
        }
    }
    private var recordsByKey: [String: ProberRecord] = [:]
    private var recordsOwnerIdentityId: String?
    /// Monotonic token distinguishing network-fetched records from a slower cache backfill.
    private var recordsEpoch = 0
    private(set) var maxNotesStats: MaxNotesStats = .fallback

    // MARK: - Loading & Status
    private var isFetchingCNSongs = false
    private var isFetchingZetaraku = false
    var isSyncingSongs: Bool { isFetchingCNSongs || isFetchingZetaraku }
    private(set) var activeSyncTask: ActiveSyncTask?
    var isSyncingRecords: Bool { activeSyncTask != nil }
    private(set) var isSyncingStats = false
    private(set) var isLoadingLocalData = true
    private(set) var isRestoringCachedRecords = false
    var statusMessage: String?
    var syncNotice: DataSyncNotice?
    var availableAppUpdate: AppReleaseInfo?
    private(set) var isCheckingAppUpdate = false

    // Single-flight guard for rotating LXNS OAuth refresh tokens
    private var inFlightTokenRefreshTasks: [String: Task<String, Error>] = [:]
    private var suppressNextIdentityRestore = false
    private var isApplyingIdentityBatch = false
    private(set) var credentialStoreLoadFailed = false
    private(set) var selfHostedConfig = SelfHostedEndpointConfig()
    var isCNDirectAvailable: Bool { selfHostedConfig.isConfigured }

    func isSyncing(identityId: String, operation: SyncOperationKind) -> Bool {
        activeSyncTask?.identityId == identityId.lowercased() && activeSyncTask?.operation == operation
    }
    private let syncHistoryStore: SyncHistoryStore
    private var divingFishMusicTitles: [Int: String] = [:]

    private var lastUnknownSongCatalogRefreshDate: Date? {
        get {
            guard let ts = UserDefaults.standard.object(forKey: Keys.unknownSongCatalogRefresh) as? TimeInterval else { return nil }
            return Date(timeIntervalSince1970: ts)
        }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue.timeIntervalSince1970, forKey: Keys.unknownSongCatalogRefresh)
            } else {
                UserDefaults.standard.removeObject(forKey: Keys.unknownSongCatalogRefresh)
            }
        }
    }

    private var lastDeletedSongsResolveAttemptDate: Date? {
        get {
            guard let ts = UserDefaults.standard.object(forKey: Keys.deletedSongsResolveAttempt) as? TimeInterval else { return nil }
            return Date(timeIntervalSince1970: ts)
        }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue.timeIntervalSince1970, forKey: Keys.deletedSongsResolveAttempt)
            } else {
                UserDefaults.standard.removeObject(forKey: Keys.deletedSongsResolveAttempt)
            }
        }
    }

    private enum SyncPolicy {
        static let catalogAutoRefresh: TimeInterval = 7 * 24 * 3600
        static let catalogManualCooldown: TimeInterval = 10 * 60
        static let unknownSongCatalogCooldown: TimeInterval = 12 * 3600
        static let deletedSongsResolveCooldown: TimeInterval = 12 * 3600
        static let statsAutoRefresh: TimeInterval = 5 * 24 * 3600
        static let statsManualCooldown: TimeInterval = 5 * 60
        static let appUpdateCheckInterval: TimeInterval = 2 * 3600
        static let bootstrapDelaySeconds: Int = 3
        static let oauthExpiryBufferSeconds: TimeInterval = 30
    }

    // MARK: - User Preferences & Multi-Channel Player Identities
    var favoriteIds: Set<String> {
        didSet { UserDefaults.standard.set(Array(favoriteIds), forKey: Keys.favorites) }
    }
    var searchHistory: [String] {
        didSet { UserDefaults.standard.set(searchHistory, forKey: Keys.searchHistory) }
    }
    var identities: [PlayerIdentity] {
        didSet {
            guard !credentialStoreLoadFailed, !isApplyingIdentityBatch else { return }
            _ = AccountCredentialStore.saveIdentities(identities)
        }
    }
    var activeIdentityId: String? {
        didSet {
            guard oldValue != activeIdentityId else { return }
            UserDefaults.standard.set(activeIdentityId, forKey: Keys.activeIdentityId)
            if suppressNextIdentityRestore {
                suppressNextIdentityRestore = false
            } else {
                restoreCachedRecords()
            }
        }
    }
    var activeIdentity: PlayerIdentity? {
        guard let activeIdentityId else { return nil }
        return identities.first(where: { $0.id == activeIdentityId })
    }

    private func setRecordsSnapshot(_ list: [ProberRecord], ownerIdentityId: String?) {
        recordsOwnerIdentityId = ownerIdentityId?.lowercased()
        records = list
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
    private var cachedDeletedCNSongsURL: URL { appSupportDir.appendingPathComponent("deleted_cn_songs.json") }
    private var cachedStatsURL: URL { appSupportDir.appendingPathComponent("chart_stats.json") }
    private var cachedRecordsURL: URL? {
        guard let activeIdentityId else { return nil }
        return AccountCredentialStore.recordsFileURL(for: activeIdentityId, in: appSupportDir)
    }

    init() {
        let ud = UserDefaults.standard
        PreferenceKeys.registerDefaults(in: ud)
        let supportDir = Self.resolveAppSupportDir()
        SyncDiagnostics.shared.configure(directoryURL: supportDir)

        self.syncHistoryStore = SyncHistoryStore(directoryURL: supportDir)
        self.favoriteIds = Set(ud.stringArray(forKey: Keys.favorites) ?? [])
        self.searchHistory = ud.stringArray(forKey: Keys.searchHistory) ?? []

        let loaded = AccountCredentialStore.loadOrMigrateIdentities(
            defaults: ud,
            appSupportDir: supportDir
        )
        var initialIdentities = loaded.identities
        if !loaded.loadFailed {
            let legacyUseDF = ud.bool(forKey: Keys.useDFNickname)
            let legacyCustom = ud.string(forKey: Keys.customNickname) ?? ""
            let migrated = ScoreSyncEngine.migrateLegacyB50Nickname(
                identities: initialIdentities,
                legacyUseProberNickname: legacyUseDF,
                legacyCustomNickname: legacyCustom
            )
            var savedMigrated = true
            if migrated.didChange {
                initialIdentities = migrated.identities
                savedMigrated = AccountCredentialStore.saveIdentities(initialIdentities)
            }
            if !migrated.didChange || savedMigrated {
                ud.removeObject(forKey: Keys.useDFNickname)
                ud.removeObject(forKey: Keys.customNickname)
            }
        }
        self.isApplyingIdentityBatch = true
        self.credentialStoreLoadFailed = loaded.loadFailed
        self.identities = initialIdentities
        self.activeIdentityId = loaded.activeIdentityId
        self.isApplyingIdentityBatch = false
        AccountCredentialStore.deleteSelfHostedEndpointConfig()
        self.selfHostedConfig = SyncServiceConfig.builtIn()

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
        let deletedURL = cachedDeletedCNSongsURL
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
            if let delData = try? Data(contentsOf: deletedURL) {
                cache.deletedCache = try? JSONDecoder().decode(DeletedCNSongsCache.self, from: delData)
            }
            if let data = try? Data(contentsOf: statsURL) {
                cache.stats = try? JSONDecoder().decode(ChartStatsResponse.self, from: data)
            }
            if let recordsURL,
               let data = try? Data(contentsOf: recordsURL),
               let decoded = try? JSONDecoder().decode([ProberRecord].self, from: data) {
                cache.records = ScoreSyncEngine.normalizeCachedRecords(decoded)
            }
            return cache
        }.value

        let currentIdentityId = activeIdentityId
        plateCatalog = caches.plates
        zetarakuCatalog = caches.zetaraku
        dxDataIdMap = caches.dxIdMap
        if let deletedCache = caches.deletedCache {
            var deletedMap: [Int: SongData] = [:]
            deletedMap.reserveCapacity(deletedCache.songs.count)
            for song in deletedCache.songs where song.numericId > 0 {
                deletedMap[song.numericId] = song
            }
            deletedSongsById = deletedMap
            unresolvableDeletedSongIds = Set(deletedCache.unresolvableIds)
        }
        var cnSnapshot: SongSnapshot?
        if let loadedCNSongs = caches.songs {
            cnSnapshot = await storeCNBaseline(loadedCNSongs)
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
        if activeIdentityId == currentIdentityId && recordsEpoch == 0 {
            setRecordsSnapshot(caches.records ?? [], ownerIdentityId: currentIdentityId)
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
            await syncZetarakuCatalog()
        }

        // Defer low-frequency periodic checks so app launch never bursts upstream APIs.
        try? await Task.sleep(for: .seconds(SyncPolicy.bootstrapDelaySeconds))
        async let periodicCatalogCheck: Void = syncAllCatalogsAndRules(force: false)
        async let periodicStatsCheck: Void = refreshChartStatsIfStale(force: false)
        async let periodicAppUpdateCheck: Void = checkAppUpdateIfNeeded(manual: false, ignoreInterval: true)
        _ = await (periodicCatalogCheck, periodicStatsCheck, periodicAppUpdateCheck)
    }

    // MARK: - App Version Update Check (Cold Launch + 2h Foreground Throttled for TestFlight & Sideload + Manual Check in About)

    func checkAppUpdateIfNeeded(manual: Bool, ignoreInterval: Bool = false) async {
        guard !isCheckingAppUpdate else { return }
        let channel = IOSInstallChannel.current
        if !manual {
            // App Store builds update via the App Store; auto-check TestFlight and sideload/dev builds.
            guard channel != .appStore else { return }
            if !ignoreInterval {
                let ud = UserDefaults.standard
                if let lastTs = ud.object(forKey: Keys.appUpdateCheck) as? TimeInterval,
                   Date().timeIntervalSince1970 - lastTs < SyncPolicy.appUpdateCheckInterval {
                    return
                }
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
        if !deletedSongsById.isEmpty {
            var remainingDeleted = deletedSongsById
            for id in snapshot.songsById.keys {
                remainingDeleted.removeValue(forKey: id)
            }
            deletedSongsById = remainingDeleted
        }
        unresolvableDeletedSongIds.subtract(snapshot.songsById.keys)
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
        let sorted = enriched.sorted {
            if $0.defaultSortKey != $1.defaultSortKey { return $0.defaultSortKey > $1.defaultSortKey }
            return $0.id > $1.id
        }
        var seenIds = Set<String>()
        seenIds.reserveCapacity(sorted.count)
        var ordered: [SongData] = []
        ordered.reserveCapacity(sorted.count)
        var map: [Int: SongData] = [:]
        map.reserveCapacity(sorted.count)
        for song in sorted where seenIds.insert(song.id).inserted {
            ordered.append(song)
            if song.numericId > 0 {
                map[song.numericId] = song
            }
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
        await syncZetarakuCatalog()
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
        await syncZetarakuCatalog()
    }

    private func syncZetarakuCatalog() async {
        let err = await performZetarakuSync()
        if let catalog = zetarakuCatalog, serverRegion != .cn {
            await applyZetarakuCatalog(catalog, for: serverRegion, epoch: regionEpoch)
        }
        if let err, serverRegion != .cn {
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
            if !unresolvableDeletedSongIds.isEmpty {
                unresolvableDeletedSongIds.removeAll()
                let cachePayload = DeletedCNSongsCache(
                    songs: Array(deletedSongsById.values).sorted { $0.numericId < $1.numericId },
                    unresolvableIds: []
                )
                let cacheURL = cachedDeletedCNSongsURL
                Task.detached(priority: .utility) {
                    if let encoded = try? JSONEncoder().encode(cachePayload) {
                        try? encoded.write(to: cacheURL, options: .atomic)
                    }
                }
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
            let (convertedSongs, convertedPlates) = await Task.detached(priority: .userInitiated) { () -> ([SongData], CNPlateCatalog?) in
                let catalogResult = LXNSCatalogConverter.convertCatalog(
                    songResponse: songResp,
                    aliasResponse: aliasResp,
                    existingBaseline: baselineSnapshot
                )
                let platesResult = plateResp.map { LXNSCatalogConverter.extractPlateCatalog(from: $0) }
                return (catalogResult.songs, platesResult)
            }.value

            let encodedSongs = try JSONEncoder().encode(convertedSongs)
            try encodedSongs.write(to: cachedSongsURL, options: .atomic)
            if let convertedPlates, let encodedPlates = try? JSONEncoder().encode(convertedPlates) {
                try? encodedPlates.write(to: cachedPlatesURL, options: .atomic)
                plateCatalog = convertedPlates
            }

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

    // MARK: - Multi-Channel Identity Connection, Sync & Writeback

    private func runRecordSync<T>(
        identityId: String?,
        operation: SyncOperationKind,
        _ work: () async throws -> T
    ) async throws -> T {
        try ensureCredentialStoreWritable()
        guard activeSyncTask == nil else { throw MaimaiAPIError.syncInProgress }
        activeSyncTask = ActiveSyncTask(identityId: identityId?.lowercased(), operation: operation)
        defer { activeSyncTask = nil }
        do {
            return try await work()
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            if operation != .pullMerged && operation != .writeback {
                recordDiagnosticFailure(operation: String(describing: operation), error: error)
            }
            throw error
        }
    }

    func loadHistory(for identityId: String) -> [SyncHistoryEntry] {
        syncHistoryStore.loadHistory(for: identityId)
    }

    func switchIdentity(to identityId: String) {
        let lower = identityId.lowercased()
        guard identities.contains(where: { $0.id == lower }), activeIdentityId != lower else { return }
        activeIdentityId = lower
    }

    func updateCustomNickname(_ raw: String, for identityId: String) {
        guard !credentialStoreLoadFailed else { return }
        let lower = identityId.lowercased()
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        var list = identities
        guard let idx = list.firstIndex(where: { $0.id == lower }),
              list[idx].customNickname != trimmed else {
            return
        }
        list[idx].customNickname = trimmed
        identities = list
    }

    func exportSyncDiagnosticLog() -> String {
        SyncDiagnostics.shared.exportText()
    }

    func removeIdentity(id identityId: String) {
        guard !credentialStoreLoadFailed else { return }
        let lower = identityId.lowercased()
        let wasActive = (activeIdentityId == lower)
        inFlightTokenRefreshTasks.removeValue(forKey: lower)?.cancel()

        let shSecret = AccountCredentialStore.loadSelfHostedSecret(for: lower)
        let endpointCfg = selfHostedConfig
        if !shSecret.syncToken.isEmpty && endpointCfg.isConfigured {
            Task.detached(priority: .utility) {
                try? await MaimaiAPI.syncBackendUnbind(config: endpointCfg, syncToken: shSecret.syncToken)
            }
        }

        AccountCredentialStore.deleteAllChannelSecrets(for: lower)
        AccountCredentialStore.deleteLegacyProfileSecret(for: lower)
        let recordFile = AccountCredentialStore.recordsFileURL(for: lower, in: appSupportDir)
        try? FileManager.default.removeItem(at: recordFile)
        let legacyRecordFile = AccountCredentialStore.legacyProfileRecordsFileURL(for: lower, in: appSupportDir)
        try? FileManager.default.removeItem(at: legacyRecordFile)
        try? syncHistoryStore.deleteHistory(for: lower)

        var updated = identities
        updated.removeAll { $0.id == lower }
        identities = updated

        if wasActive {
            activeIdentityId = updated.first?.id
            if activeIdentityId == nil {
                recordsEpoch += 1
                setRecordsSnapshot([], ownerIdentityId: nil)
            }
        }
    }

    func resetCredentialStoreAfterLoadFailure() {
        guard credentialStoreLoadFailed else { return }
        AccountCredentialStore.resetIdentitiesAfterLoadFailure()
        isApplyingIdentityBatch = true
        credentialStoreLoadFailed = false
        identities = []
        suppressNextIdentityRestore = true
        activeIdentityId = nil
        isApplyingIdentityBatch = false
        recordsEpoch += 1
        setRecordsSnapshot([], ownerIdentityId: nil)
    }

    func unbindChannel(_ channel: SyncChannelKind, from identityId: String) async throws {
        try ensureCredentialStoreWritable()
        let lower = identityId.lowercased()
        guard let idx = identities.firstIndex(where: { $0.id == lower }) else { return }
        var updated = identities

        switch channel {
        case .selfHosted:
            let shSecret = AccountCredentialStore.loadSelfHostedSecret(for: lower)
            let endpointCfg = selfHostedConfig
            if !shSecret.syncToken.isEmpty && endpointCfg.isConfigured {
                Task.detached(priority: .utility) {
                    try? await MaimaiAPI.syncBackendUnbind(config: endpointCfg, syncToken: shSecret.syncToken)
                }
            }
            AccountCredentialStore.deleteSelfHostedSecret(for: lower)
            updated[idx].selfHosted = nil

        case .lxns:
            inFlightTokenRefreshTasks.removeValue(forKey: lower)?.cancel()
            AccountCredentialStore.deleteLXNSSecret(for: lower)
            updated[idx].lxns = nil

        case .divingFish:
            AccountCredentialStore.deleteDivingFishSecret(for: lower)
            updated[idx].divingFish = nil
        }

        updated[idx].hasMixedScoreSources = true
        identities = updated
    }

    /// 0A. CN Direct Bind (paste QR text/URL -> initial quick or full sync)
    @discardableResult
    func bindSelfHosted(
        qrContent: String,
        initialFullSync: Bool,
        targetIdentityId: String? = nil
    ) async throws -> (identity: PlayerIdentity, recordCount: Int) {
        try await runRecordSync(identityId: targetIdentityId, operation: .bind) {
            guard selfHostedConfig.isConfigured else {
                throw MaimaiAPIError.unauthorized("当前版本暂不支持国服直连")
            }

            let endpoint = selfHostedConfig
            let resp = try await Task {
                try await MaimaiAPI.syncBackendBind(config: endpoint, qrContent: qrContent, fullSync: initialFullSync)
            }.value
            guard let syncToken = resp.syncToken, !syncToken.isEmpty else {
                throw MaimaiAPIError.httpError(-1, "服务端未返回同步令牌")
            }

            let explicitLower = targetIdentityId?.lowercased()
            if let explicitLower,
               let target = identities.first(where: { $0.id == explicitLower }),
               let existingUID = target.selfHosted?.externalUserId,
               existingUID != resp.profile.userId {
                Task.detached(priority: .utility) {
                    try? await MaimaiAPI.syncBackendUnbind(config: endpoint, syncToken: syncToken)
                }
                throw MaimaiAPIError.backendError(
                    statusCode: 403,
                    code: "USER_ID_MISMATCH",
                    message: "二维码对应的玩家与该档案已绑定的国服账号不一致，请确认微信账号",
                    retryAfterSec: nil
                )
            }

            let resolvedId: String = {
                if let explicitLower,
                   identities.contains(where: { $0.id == explicitLower }) {
                    return explicitLower
                }
                if let existing = identities.first(where: { $0.selfHosted?.externalUserId == resp.profile.userId }) {
                    return existing.id
                }
                return UUID().uuidString.lowercased()
            }()

            let previousToken = AccountCredentialStore.loadSelfHostedSecret(for: resolvedId).syncToken
            guard AccountCredentialStore.saveSelfHostedSecret(SelfHostedSecret(syncToken: syncToken), for: resolvedId) else {
                throw MaimaiAPIError.httpError(-1, "保存国服直连凭据失败")
            }
            if !previousToken.isEmpty && previousToken != syncToken {
                Task.detached(priority: .utility) {
                    try? await MaimaiAPI.syncBackendUnbind(config: endpoint, syncToken: previousToken)
                }
            }

            let mode: SyncMode = initialFullSync ? .full : .quick
            let nowMs = resp.syncedAtUnixMs > 0
                ? resp.syncedAtUnixMs
                : Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
            let displayName = (resp.profile.userName?.isEmpty == false) ? resp.profile.userName! : "UID \(resp.profile.userId)"
            let binding = SelfHostedBinding(
                externalUserId: resp.profile.userId,
                displayName: displayName,
                playerRating: resp.profile.playerRating,
                boundAtEpochMs: nowMs
            )

            var list = identities
            if let idx = list.firstIndex(where: { $0.id == resolvedId }) {
                list[idx].selfHosted = binding
                list[idx].lastSyncMode = mode
                list[idx].lastSyncedAtEpochMs = nowMs
                if initialFullSync {
                    list[idx].lastFullSyncAtEpochMs = nowMs
                    list[idx].hasMixedScoreSources = false
                }
            } else {
                list.append(
                    PlayerIdentity(
                        id: resolvedId,
                        label: displayName,
                        selfHosted: binding,
                        lastSyncMode: mode,
                        lastSyncedAtEpochMs: nowMs,
                        lastFullSyncAtEpochMs: initialFullSync ? nowMs : nil
                    )
                )
            }
            identities = list

            let lookup = scoreImportSongLookup()
            let incoming = await Task.detached(priority: .userInitiated) {
                ScoreSyncEngine.normalizeBackendRecords(resp.records, songsById: lookup, region: .cn)
            }.value

            let existingRecords = initialFullSync ? [] : await loadRecordsSnapshot(for: resolvedId)
            let merged = initialFullSync
                ? ScoreSyncEngine.applyFullSync(incoming: incoming)
                : ScoreSyncEngine.mergeQuickSync(existing: existingRecords, incoming: incoming)

            let shouldMakeActive = (activeIdentityId == nil) || (targetIdentityId == nil) || (activeIdentityId == resolvedId)
            try await installFetchedRecords(merged, for: resolvedId, syncMode: mode, makeActive: shouldMakeActive)
            await resolveUnknownSongsAfterSync(in: merged, for: resolvedId)
            SyncDiagnostics.shared.record(operation: "bind_cn", message: "绑定国服直连成功（共 \(merged.count) 条）")

            let updatedIdentity = identities.first(where: { $0.id == resolvedId }) ?? list.last!
            return (updatedIdentity, merged.count)
        }
    }

    /// 0B. CN Direct Quick Sync (uses saved syncToken, preserves local FC/FS badges)
    @discardableResult
    func quickSyncSelfHosted(identityId: String? = nil, writeback: Bool = false) async throws -> WritebackSummary? {
        guard let targetId = (identityId ?? activeIdentityId)?.lowercased(),
              let identity = identities.first(where: { $0.id == targetId }),
              identity.selfHosted != nil else {
            throw MaimaiAPIError.unauthorized("当前档案未绑定国服直连")
        }
        return try await runRecordSync(identityId: targetId, operation: .quickSync) {
            guard selfHostedConfig.isConfigured else {
                throw MaimaiAPIError.unauthorized("当前版本暂不支持国服直连")
            }
            let secret = AccountCredentialStore.loadSelfHostedSecret(for: targetId)
            guard !secret.syncToken.isEmpty else {
                throw MaimaiAPIError.unauthorized("国服直连已失效，请重新绑定")
            }

            let endpoint = selfHostedConfig
            let resp = try await Task {
                try await MaimaiAPI.syncBackendQuick(config: endpoint, syncToken: secret.syncToken)
            }.value

            let nowMs = resp.syncedAtUnixMs > 0
                ? resp.syncedAtUnixMs
                : Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
            var list = identities
            if let idx = list.firstIndex(where: { $0.id == targetId }) {
                if let uname = resp.profile.userName, !uname.isEmpty {
                    list[idx].selfHosted?.displayName = uname
                }
                if let pr = resp.profile.playerRating {
                    list[idx].selfHosted?.playerRating = pr
                }
                list[idx].lastSyncMode = .quick
                list[idx].lastSyncedAtEpochMs = nowMs
                identities = list
            }

            let lookup = scoreImportSongLookup()
            let incoming = await Task.detached(priority: .userInitiated) {
                ScoreSyncEngine.normalizeBackendRecords(resp.records, songsById: lookup, region: .cn)
            }.value
            let existing = await loadRecordsSnapshot(for: targetId)
            let merged = ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: incoming)

            try await installFetchedRecords(merged, for: targetId, syncMode: .quick, makeActive: false)
            await resolveUnknownSongsAfterSync(in: merged, for: targetId)
            SyncDiagnostics.shared.record(operation: "quick_sync", message: "快速同步完成（共 \(merged.count) 条）")

            if writeback {
                return try await performWriteback(for: targetId, localRecords: merged)
            }
            return nil
        }
    }

    /// 0C. CN Direct Full Sync (requires pasted QR, authoritative badge overwrite)
    @discardableResult
    func fullSyncSelfHosted(
        identityId: String? = nil,
        qrContent: String,
        writeback: Bool = false
    ) async throws -> WritebackSummary? {
        guard let targetId = (identityId ?? activeIdentityId)?.lowercased(),
              let identity = identities.first(where: { $0.id == targetId }),
              identity.selfHosted != nil else {
            throw MaimaiAPIError.unauthorized("当前档案未绑定国服直连")
        }
        return try await runRecordSync(identityId: targetId, operation: .fullSync) {
            guard selfHostedConfig.isConfigured else {
                throw MaimaiAPIError.unauthorized("当前版本暂不支持国服直连")
            }
            let secret = AccountCredentialStore.loadSelfHostedSecret(for: targetId)
            guard !secret.syncToken.isEmpty else {
                throw MaimaiAPIError.unauthorized("国服直连已失效，请重新绑定")
            }

            let endpoint = selfHostedConfig
            let resp = try await Task {
                try await MaimaiAPI.syncBackendFull(config: endpoint, syncToken: secret.syncToken, qrContent: qrContent)
            }.value

            let nowMs = resp.syncedAtUnixMs > 0
                ? resp.syncedAtUnixMs
                : Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
            var list = identities
            if let idx = list.firstIndex(where: { $0.id == targetId }) {
                if let uname = resp.profile.userName, !uname.isEmpty {
                    list[idx].selfHosted?.displayName = uname
                }
                if let pr = resp.profile.playerRating {
                    list[idx].selfHosted?.playerRating = pr
                }
                list[idx].lastSyncMode = .full
                list[idx].lastSyncedAtEpochMs = nowMs
                list[idx].lastFullSyncAtEpochMs = nowMs
                list[idx].hasMixedScoreSources = false
                identities = list
            }

            let lookup = scoreImportSongLookup()
            let incoming = await Task.detached(priority: .userInitiated) {
                ScoreSyncEngine.normalizeBackendRecords(resp.records, songsById: lookup, region: .cn)
            }.value
            let replaced = ScoreSyncEngine.applyFullSync(incoming: incoming)

            try await installFetchedRecords(replaced, for: targetId, syncMode: .full, makeActive: false)
            await resolveUnknownSongsAfterSync(in: replaced, for: targetId)
            SyncDiagnostics.shared.record(operation: "full_sync", message: "完整同步完成（共 \(replaced.count) 条）")

            if writeback {
                return try await performWriteback(for: targetId, localRecords: replaced)
            }
            return nil
        }
    }

    /// 1. Diving-Fish Username + Password
    func connectDivingFishPassword(
        username: String,
        password: String,
        targetIdentityId: String? = nil
    ) async throws {
        try await runRecordSync(identityId: targetIdentityId, operation: .bind) {
            let trimmedUser = username.trimmingCharacters(in: .whitespacesAndNewlines)
            let cookie = try await MaimaiAPI.login(username: trimmedUser, password: password)

            let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
            let binding = DivingFishBinding(
                authMethod: .divingFishPassword,
                accountKey: trimmedUser,
                username: trimmedUser,
                nickname: "",
                boundAtEpochMs: nowMs
            )
            let targetId = resolveOrCreateIdentity(
                targetIdentityId: targetIdentityId,
                matchingDFKey: trimmedUser,
                defaultLabel: trimmedUser
            ) { identity in
                if identity.selfHosted != nil || identity.lxns != nil {
                    identity.hasMixedScoreSources = true
                }
                identity.divingFish = binding
            }
            guard AccountCredentialStore.saveDivingFishSecret(
                DivingFishSecret(username: trimmedUser, password: password, cookie: cookie, importToken: ""),
                for: targetId
            ) else {
                throw MaimaiAPIError.httpError(-1, "保存水鱼凭据失败")
            }

            let makeActive = (activeIdentityId == nil) || (targetIdentityId == nil) || (activeIdentityId == targetId)
            if identities.first(where: { $0.id == targetId })?.selfHosted != nil {
                if makeActive && activeIdentityId != targetId {
                    activeIdentityId = targetId
                }
                SyncDiagnostics.shared.record(operation: "bind", channel: "divingFish", message: "绑定水鱼账号（保留国服直连成绩）")
                return
            }
            do {
                try await performPullDivingFish(for: targetId, mergeExisting: targetIdentityId != nil, makeActive: makeActive)
            } catch {
                if makeActive && activeIdentityId != targetId {
                    activeIdentityId = targetId
                }
                throw MaimaiAPIError.recordSyncFailedAfterLogin(error.localizedDescription)
            }
        }
    }

    /// 2. Diving-Fish Import-Token (`Import-Token` header)
    func connectDivingFishImportToken(
        _ rawToken: String,
        remarkName: String = "",
        targetIdentityId: String? = nil
    ) async throws {
        try await runRecordSync(identityId: targetIdentityId, operation: .bind) {
            let token = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
            let resp = try await Task {
                try await MaimaiAPI.fetchPlayerRecords(importToken: token)
            }.value

            let trimmedRemark = remarkName.trimmingCharacters(in: .whitespacesAndNewlines)
            let resolvedUsername = (resp.username?.isEmpty == false) ? resp.username! : trimmedRemark
            let accountKey = (resp.username?.isEmpty == false) ? resp.username! : "df_token_\(token.sha256Hex.prefix(16))"
            let resolvedNickname = (resp.nickname?.isEmpty == false) ? resp.nickname! : trimmedRemark
            let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())

            let binding = DivingFishBinding(
                authMethod: .divingFishImportToken,
                accountKey: accountKey,
                username: resolvedUsername,
                nickname: resolvedNickname,
                boundAtEpochMs: nowMs
            )
            let targetId = resolveOrCreateIdentity(
                targetIdentityId: targetIdentityId,
                matchingDFKey: accountKey,
                defaultLabel: resolvedNickname.isEmpty ? resolvedUsername : resolvedNickname
            ) { identity in
                if identity.selfHosted != nil || identity.lxns != nil {
                    identity.hasMixedScoreSources = true
                }
                identity.divingFish = binding
                if identity.selfHosted == nil {
                    identity.lastSyncMode = .divingFish
                    identity.lastSyncedAtEpochMs = nowMs
                }
            }
            guard AccountCredentialStore.saveDivingFishSecret(
                DivingFishSecret(username: resolvedUsername, password: "", cookie: "", importToken: token),
                for: targetId
            ) else {
                throw MaimaiAPIError.httpError(-1, "保存水鱼导入 Token 失败")
            }

            let makeActive = (activeIdentityId == nil) || (targetIdentityId == nil) || (activeIdentityId == targetId)
            if identities.first(where: { $0.id == targetId })?.selfHosted != nil {
                if makeActive && activeIdentityId != targetId {
                    activeIdentityId = targetId
                }
                SyncDiagnostics.shared.record(operation: "bind", channel: "divingFish", message: "绑定水鱼导入密钥（保留国服直连成绩）")
                return
            }
            let fetched = resp.records ?? []
            let finalRecords: [ProberRecord]
            if targetIdentityId != nil {
                let existing = await loadRecordsSnapshot(for: targetId)
                if !existing.isEmpty, let listIdx = identities.firstIndex(where: { $0.id == targetId }) {
                    var list = identities
                    list[listIdx].hasMixedScoreSources = true
                    identities = list
                }
                finalRecords = existing.isEmpty ? ScoreSyncEngine.applyFullSync(incoming: fetched)
                    : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: fetched)
            } else {
                finalRecords = ScoreSyncEngine.applyFullSync(incoming: fetched)
            }
            try await installFetchedRecords(finalRecords, for: targetId, syncMode: .divingFish, makeActive: makeActive)
            await resolveUnknownSongsAfterSync(in: finalRecords, for: targetId)
        }
    }

    /// 3. LXNS OAuth 2.0 + PKCE (`ASWebAuthenticationSession`)
    func connectLXNSOAuth(
        using webAuthRunner: WebAuthSessionRunner,
        targetIdentityId: String? = nil
    ) async throws {
        try ensureCredentialStoreWritable()
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

        let callbackURL = try await webAuthRunner.authenticate(
            url: authorizeURL,
            callbackScheme: LXNSConfiguration.callbackScheme
        )
        let code = try MaimaiAPI.parseOAuthCallback(url: callbackURL, expectedState: state)

        try await runRecordSync(identityId: targetIdentityId, operation: .bind) {
            let tokenResp = try await MaimaiAPI.exchangeLXNSCode(code: code, codeVerifier: verifier)
            let expiresAt = Int64((Date().timeIntervalSince1970 + Double(tokenResp.expiresIn)).rounded())
            let userInfo = try? await MaimaiAPI.fetchLXNSUserInfo(accessToken: tokenResp.accessToken)

            let accountKey: String = {
                if let sub = userInfo?.sub, !sub.isEmpty { return "oidc_\(sub)" }
                return "lxns_oauth_\(UUID().uuidString.lowercased())"
            }()
            let lxUsername = userInfo?.displayUsername ?? ""
            let grantedScopes = tokenResp.scope
            let hasWriteScope = grantedScopes?.split(whereSeparator: \.isWhitespace).contains("write_player") ?? true
            let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())

            let binding = LXNSBinding(
                authMethod: .lxnsOAuth,
                accountKey: accountKey,
                displayName: lxUsername.isEmpty ? "落雪用户" : lxUsername,
                friendCode: nil,
                hasWriteScope: hasWriteScope,
                boundAtEpochMs: nowMs
            )
            let targetId = resolveOrCreateIdentity(
                targetIdentityId: targetIdentityId,
                matchingLXNSKey: accountKey,
                defaultLabel: binding.displayName
            ) { identity in
                if identity.selfHosted != nil || identity.divingFish != nil {
                    identity.hasMixedScoreSources = true
                }
                identity.lxns = binding
            }

            guard AccountCredentialStore.saveLXNSSecret(
                LXNSSecret(
                    personalToken: "",
                    accessToken: tokenResp.accessToken,
                    refreshToken: tokenResp.refreshToken ?? "",
                    accessTokenExpiresAtEpochSec: expiresAt,
                    grantedScopes: grantedScopes
                ),
                for: targetId
            ) else {
                throw MaimaiAPIError.httpError(-1, "保存落雪令牌失败")
            }

            let makeActive = (activeIdentityId == nil) || (targetIdentityId == nil) || (activeIdentityId == targetId)
            if identities.first(where: { $0.id == targetId })?.selfHosted != nil {
                if makeActive && activeIdentityId != targetId {
                    activeIdentityId = targetId
                }
                SyncDiagnostics.shared.record(operation: "bind", channel: "lxns", message: "绑定落雪账号（保留国服直连成绩）")
                return
            }
            do {
                try await performPullLXNS(for: targetId, mergeExisting: targetIdentityId != nil, makeActive: makeActive)
            } catch {
                if makeActive && activeIdentityId != targetId {
                    activeIdentityId = targetId
                }
                throw MaimaiAPIError.recordSyncFailedAfterLogin(error.localizedDescription)
            }
        }
    }

    /// 4. LXNS Personal API Key (`X-User-Token`)
    func connectLXNSPersonalToken(
        _ rawToken: String,
        remarkName: String = "",
        targetIdentityId: String? = nil
    ) async throws {
        try await runRecordSync(identityId: targetIdentityId, operation: .bind) {
            let token = rawToken.trimmingCharacters(in: .whitespacesAndNewlines)
            let (player, lxScores) = try await Task {
                try await MaimaiAPI.fetchLXNSPlayerAndScores(personalToken: token)
            }.value

            let trimmedRemark = remarkName.trimmingCharacters(in: .whitespacesAndNewlines)
            let accountKey = player?.friendCode.map { "fc_\($0)" } ?? "lxns_pt_\(token.sha256Hex.prefix(16))"
            let nickname = (player?.name.isEmpty == false) ? player!.name : (trimmedRemark.isEmpty ? "落雪用户" : trimmedRemark)
            let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())

            let binding = LXNSBinding(
                authMethod: .lxnsPersonalToken,
                accountKey: accountKey,
                displayName: nickname,
                friendCode: player?.friendCode,
                hasWriteScope: true,
                boundAtEpochMs: nowMs
            )
            let targetId = resolveOrCreateIdentity(
                targetIdentityId: targetIdentityId,
                matchingLXNSKey: accountKey,
                defaultLabel: nickname
            ) { identity in
                if identity.selfHosted != nil || identity.divingFish != nil {
                    identity.hasMixedScoreSources = true
                }
                identity.lxns = binding
                if identity.selfHosted == nil {
                    identity.lastSyncMode = .lxns
                    identity.lastSyncedAtEpochMs = nowMs
                }
            }

            guard AccountCredentialStore.saveLXNSSecret(
                LXNSSecret(personalToken: token, grantedScopes: LXNSConfiguration.scopes),
                for: targetId
            ) else {
                throw MaimaiAPIError.httpError(-1, "保存落雪密钥失败")
            }

            let makeActive = (activeIdentityId == nil) || (targetIdentityId == nil) || (activeIdentityId == targetId)
            if identities.first(where: { $0.id == targetId })?.selfHosted != nil {
                if makeActive && activeIdentityId != targetId {
                    activeIdentityId = targetId
                }
                SyncDiagnostics.shared.record(operation: "bind", channel: "lxns", message: "绑定落雪个人密钥（保留国服直连成绩）")
                return
            }

            let lookup = scoreImportSongLookup()
            let converted = await Task.detached(priority: .userInitiated) {
                LXNSCatalogConverter.convertScores(lxScores, songsById: lookup)
            }.value
            let finalRecords: [ProberRecord]
            if targetIdentityId != nil {
                let existing = await loadRecordsSnapshot(for: targetId)
                if !existing.isEmpty, let listIdx = identities.firstIndex(where: { $0.id == targetId }) {
                    var list = identities
                    list[listIdx].hasMixedScoreSources = true
                    identities = list
                }
                finalRecords = existing.isEmpty ? ScoreSyncEngine.applyFullSync(incoming: converted)
                    : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: converted)
            } else {
                finalRecords = ScoreSyncEngine.applyFullSync(incoming: converted)
            }
            try await installFetchedRecords(finalRecords, for: targetId, syncMode: .lxns, makeActive: makeActive)
            await resolveUnknownSongsAfterSync(in: finalRecords, for: targetId)
        }
    }

    func pullFromLXNS(identityId: String? = nil) async throws {
        guard let targetId = (identityId ?? activeIdentityId)?.lowercased(),
              let identity = identities.first(where: { $0.id == targetId }),
              identity.lxns != nil else {
            throw MaimaiAPIError.unauthorized("当前档案未绑定落雪咖啡屋")
        }
        if identity.selfHosted != nil {
            throw MaimaiAPIError.unauthorized("已绑定国服直连，请使用快速同步或完整同步")
        }
        let mergeExisting = identity.hasMixedScoreSources ||
            (identity.boundChannelCount > 1) ||
            (identity.lastSyncMode != nil && identity.lastSyncMode != .lxns)
        try await runRecordSync(identityId: targetId, operation: .pullLXNS) {
            try await performPullLXNS(for: targetId, mergeExisting: mergeExisting, makeActive: false)
        }
    }

    func pullFromDivingFish(identityId: String? = nil) async throws {
        guard let targetId = (identityId ?? activeIdentityId)?.lowercased(),
              let identity = identities.first(where: { $0.id == targetId }),
              identity.divingFish != nil else {
            throw MaimaiAPIError.unauthorized("当前档案未绑定水鱼查分器")
        }
        if identity.selfHosted != nil {
            throw MaimaiAPIError.unauthorized("已绑定国服直连，请使用快速同步或完整同步")
        }
        let mergeExisting = identity.hasMixedScoreSources ||
            (identity.boundChannelCount > 1) ||
            (identity.lastSyncMode != nil && identity.lastSyncMode != .divingFish)
        try await runRecordSync(identityId: targetId, operation: .pullDivingFish) {
            try await performPullDivingFish(for: targetId, mergeExisting: mergeExisting, makeActive: false)
        }
    }

    @discardableResult
    func pullMergedProbers(identityId: String? = nil) async throws -> SyncOutcome {
        guard let targetId = (identityId ?? activeIdentityId)?.lowercased(),
              let identity = identities.first(where: { $0.id == targetId }),
              identity.lxns != nil,
              identity.divingFish != nil else {
            throw MaimaiAPIError.unauthorized("当前档案未同时绑定落雪咖啡屋和水鱼查分器")
        }
        if identity.selfHosted != nil {
            throw MaimaiAPIError.unauthorized("已绑定国服直连，请使用快速同步或完整同步")
        }
        return try await runRecordSync(identityId: targetId, operation: .pullMerged) {
            async let lxnsResult: Result<(playerName: String?, friendCode: Int64?, records: [ProberRecord]), Error> = {
                do {
                    return .success(try await withTransientRetry { try await self.fetchLXNSRecordsRaw(for: targetId) })
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    return .failure(error)
                }
            }()
            async let dfResult: Result<(username: String?, nickname: String?, records: [ProberRecord]), Error> = {
                do {
                    return .success(try await withTransientRetry { try await self.fetchDivingFishRecordsRaw(for: targetId) })
                } catch is CancellationError {
                    throw CancellationError()
                } catch {
                    return .failure(error)
                }
            }()

            let (lxRes, dfRes) = try await (lxnsResult, dfResult)
            let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
            let existing = await loadRecordsSnapshot(for: targetId)

            switch (lxRes, dfRes) {
            case let (.success(lx), .success(df)):
                var list = identities
                if let idx = list.firstIndex(where: { $0.id == targetId }) {
                    if let name = lx.playerName, !name.isEmpty { list[idx].lxns?.displayName = name }
                    if let fc = lx.friendCode { list[idx].lxns?.friendCode = fc }
                    if let uname = df.username, !uname.isEmpty { list[idx].divingFish?.username = uname }
                    if let nick = df.nickname, !nick.isEmpty { list[idx].divingFish?.nickname = nick }
                    list[idx].lastSyncMode = .lxns
                    list[idx].lastSyncedAtEpochMs = nowMs
                    list[idx].hasMixedScoreSources = true
                    identities = list
                }
                let withDF = existing.isEmpty
                    ? ScoreSyncEngine.applyFullSync(incoming: df.records)
                    : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: df.records)
                let finalRecords = ScoreSyncEngine.mergeQuickSync(existing: withDF, incoming: lx.records)
                try await installFetchedRecords(finalRecords, for: targetId, syncMode: .lxns, makeActive: false)
                await resolveUnknownSongsAfterSync(in: finalRecords, for: targetId)
                SyncDiagnostics.shared.record(operation: "pull_merged", message: "双查分器合并同步完成（共 \(finalRecords.count) 条）")
                return SyncOutcome()

            case let (.success(lx), .failure(dfErr)):
                var list = identities
                if let idx = list.firstIndex(where: { $0.id == targetId }) {
                    if let name = lx.playerName, !name.isEmpty { list[idx].lxns?.displayName = name }
                    if let fc = lx.friendCode { list[idx].lxns?.friendCode = fc }
                    list[idx].lastSyncMode = .lxns
                    list[idx].lastSyncedAtEpochMs = nowMs
                    list[idx].hasMixedScoreSources = true
                    identities = list
                }
                let finalRecords = existing.isEmpty
                    ? ScoreSyncEngine.applyFullSync(incoming: lx.records)
                    : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: lx.records)
                try await installFetchedRecords(finalRecords, for: targetId, syncMode: .lxns, makeActive: false)
                await resolveUnknownSongsAfterSync(in: finalRecords, for: targetId)
                recordDiagnosticFailure(operation: "pull_merged", channel: "divingFish", error: dfErr)
                return SyncOutcome(warningMessage: "水鱼同步失败（已合并落雪与本地最佳成绩）：\(dfErr.localizedDescription)")

            case let (.failure(lxErr), .success(df)):
                var list = identities
                if let idx = list.firstIndex(where: { $0.id == targetId }) {
                    if let uname = df.username, !uname.isEmpty { list[idx].divingFish?.username = uname }
                    if let nick = df.nickname, !nick.isEmpty { list[idx].divingFish?.nickname = nick }
                    list[idx].lastSyncMode = .divingFish
                    list[idx].lastSyncedAtEpochMs = nowMs
                    list[idx].hasMixedScoreSources = true
                    identities = list
                }
                let finalRecords = existing.isEmpty
                    ? ScoreSyncEngine.applyFullSync(incoming: df.records)
                    : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: df.records)
                try await installFetchedRecords(finalRecords, for: targetId, syncMode: .divingFish, makeActive: false)
                await resolveUnknownSongsAfterSync(in: finalRecords, for: targetId)
                recordDiagnosticFailure(operation: "pull_merged", channel: "lxns", error: lxErr)
                return SyncOutcome(warningMessage: "落雪同步失败（已合并水鱼与本地最佳成绩）：\(lxErr.localizedDescription)")

            case let (.failure(lxErr), .failure(dfErr)):
                recordDiagnosticFailure(operation: "pull_merged", channel: "lxns", error: lxErr)
                recordDiagnosticFailure(operation: "pull_merged", channel: "divingFish", error: dfErr)
                throw MaimaiAPIError.httpError(
                    -1,
                    "落雪与水鱼均同步失败：落雪（\(lxErr.localizedDescription)）；水鱼（\(dfErr.localizedDescription)）"
                )
            }
        }
    }

    func writebackToBoundProbers(identityId: String? = nil) async throws -> WritebackSummary {
        guard let targetId = (identityId ?? activeIdentityId)?.lowercased(),
              identities.contains(where: { $0.id == targetId }) else {
            throw MaimaiAPIError.unauthorized("请先选择要上传的成绩档案")
        }
        return try await runRecordSync(identityId: targetId, operation: .writeback) {
            let localRecords = await loadRecordsSnapshot(for: targetId)
            return try await performWriteback(for: targetId, localRecords: localRecords)
        }
    }

    /// Runs the identity's default sync plan (CN Direct quick sync, merged probers, or single prober pull).
    @discardableResult
    func syncActiveIdentityDefault() async throws -> SyncOutcome {
        guard let identity = activeIdentity else {
            throw MaimaiAPIError.unauthorized("请先添加成绩档案或绑定账号")
        }
        switch identity.syncPlan {
        case .cnDirectQuick:
            _ = try await quickSyncSelfHosted(identityId: identity.id, writeback: false)
            return SyncOutcome()
        case .singleProber(.lxns):
            try await pullFromLXNS(identityId: identity.id)
            return SyncOutcome()
        case .singleProber(.divingFish):
            try await pullFromDivingFish(identityId: identity.id)
            return SyncOutcome()
        case .singleProber:
            throw MaimaiAPIError.unauthorized("请先绑定账号或查分器")
        case .mergedProbers:
            return try await pullMergedProbers(identityId: identity.id)
        case .none:
            throw MaimaiAPIError.unauthorized("请先绑定账号或查分器")
        }
    }

    // MARK: - Internal Channel Sync & Writeback Helpers

    private func ensureCredentialStoreWritable() throws {
        if credentialStoreLoadFailed {
            throw MaimaiAPIError.unauthorized("本地安全存储读取异常，已暂停写入以保护档案；请重启 App 或解锁设备后重试")
        }
    }

    private func resolveOrCreateIdentity(
        targetIdentityId: String?,
        matchingSelfHostedId: Int64? = nil,
        matchingLXNSKey: String? = nil,
        matchingDFKey: String? = nil,
        defaultLabel: String,
        mutate: (inout PlayerIdentity) -> Void
    ) -> String {
        var list = identities
        let targetIdx: Int? = {
            if let explicit = targetIdentityId?.lowercased(),
               let i = list.firstIndex(where: { $0.id == explicit }) {
                return i
            }
            if let uid = matchingSelfHostedId,
               let i = list.firstIndex(where: { $0.selfHosted?.externalUserId == uid }) {
                return i
            }
            if let key = matchingLXNSKey,
               let i = list.firstIndex(where: { $0.lxns?.accountKey == key }) {
                return i
            }
            if let key = matchingDFKey,
               let i = list.firstIndex(where: { $0.divingFish?.accountKey == key }) {
                return i
            }
            return nil
        }()

        let resolvedId: String
        if let idx = targetIdx {
            mutate(&list[idx])
            if list[idx].label.isEmpty && !defaultLabel.isEmpty {
                list[idx].label = defaultLabel
            }
            resolvedId = list[idx].id
        } else {
            var created = PlayerIdentity(
                id: UUID().uuidString.lowercased(),
                label: defaultLabel
            )
            mutate(&created)
            list.append(created)
            resolvedId = created.id
        }
        identities = list
        return resolvedId
    }

    private func isTransientSyncError(_ error: Error) -> Bool {
        if error is CancellationError { return false }
        if let urlError = error as? URLError {
            return urlError.code != .cancelled
        }
        if case let MaimaiAPIError.httpError(code, _) = error {
            return code >= 500 || code == -1
        }
        if case let MaimaiAPIError.backendError(statusCode, _, _, _) = error {
            return statusCode >= 500
        }
        return false
    }

    private func withTransientRetry<T>(_ work: () async throws -> T) async throws -> T {
        do {
            return try await work()
        } catch {
            guard isTransientSyncError(error) else { throw error }
            try await Task.sleep(nanoseconds: 600_000_000)
            return try await work()
        }
    }

    private func recordDiagnosticFailure(operation: String, channel: String? = nil, error: Error) {
        if error is CancellationError { return }
        var statusCode: Int?
        var errorCode: String?
        if case let MaimaiAPIError.httpError(code, _) = error {
            statusCode = code
        } else if case let MaimaiAPIError.backendError(code, errCode, _, _) = error {
            statusCode = code
            errorCode = errCode
        } else if let urlError = error as? URLError {
            errorCode = "URLError(\(urlError.code.rawValue))"
        }
        SyncDiagnostics.shared.record(
            operation: operation,
            channel: channel,
            statusCode: statusCode,
            errorCode: errorCode,
            message: error.localizedDescription
        )
    }

    private func fetchDivingFishRecordsRaw(
        for identityId: String
    ) async throws -> (username: String?, nickname: String?, records: [ProberRecord]) {
        guard let identity = identities.first(where: { $0.id == identityId }),
              let df = identity.divingFish else {
            throw MaimaiAPIError.unauthorized("当前档案未绑定水鱼查分器")
        }
        var secret = AccountCredentialStore.loadDivingFishSecret(for: identityId)

        let resp: ProberRecordsResponse
        switch df.authMethod {
        case .divingFishPassword:
            let username = df.username.isEmpty ? secret.username : df.username
            let password = secret.password
            var cookie = secret.cookie

            let (fetchedResp, usedCookie) = try await Task { () -> (ProberRecordsResponse, String) in
                if cookie.isEmpty {
                    guard !username.isEmpty, !password.isEmpty else {
                        throw MaimaiAPIError.unauthorized("水鱼登录已过期，请解绑后重新绑定")
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
                AccountCredentialStore.saveDivingFishSecret(secret, for: identityId)
            }
            resp = fetchedResp

        case .divingFishImportToken:
            let token = secret.importToken
            guard !token.isEmpty else {
                throw MaimaiAPIError.unauthorized("未找到保存的水鱼导入密钥，请解绑后重新绑定")
            }
            resp = try await Task {
                try await MaimaiAPI.fetchPlayerRecords(importToken: token)
            }.value

        default:
            throw MaimaiAPIError.unauthorized("水鱼认证方式不受支持")
        }

        return (resp.username, resp.nickname, resp.records ?? [])
    }

    private func fetchLXNSRecordsRaw(
        for identityId: String
    ) async throws -> (playerName: String?, friendCode: Int64?, records: [ProberRecord]) {
        guard let identity = identities.first(where: { $0.id == identityId }),
              let lxns = identity.lxns else {
            throw MaimaiAPIError.unauthorized("当前档案未绑定落雪咖啡屋")
        }
        let secret = AccountCredentialStore.loadLXNSSecret(for: identityId)

        let player: LXNSPlayer?
        let lxScores: [LXNSScore]

        switch lxns.authMethod {
        case .lxnsOAuth:
            let initialToken = try await validLXNSAccessToken(for: identityId, forceRefresh: false)
            do {
                (player, lxScores) = try await Task {
                    try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: initialToken)
                }.value
            } catch MaimaiAPIError.unauthorized {
                let refreshedToken = try await validLXNSAccessToken(
                    for: identityId,
                    forceRefresh: true,
                    failedAccessToken: initialToken
                )
                (player, lxScores) = try await Task {
                    try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: refreshedToken)
                }.value
            }

        case .lxnsPersonalToken:
            let token = secret.personalToken
            guard !token.isEmpty else {
                throw MaimaiAPIError.unauthorized("未找到保存的落雪个人密钥，请解绑后重新绑定")
            }
            (player, lxScores) = try await Task {
                try await MaimaiAPI.fetchLXNSPlayerAndScores(personalToken: token)
            }.value

        default:
            throw MaimaiAPIError.unauthorized("落雪认证方式不受支持")
        }

        let lookup = scoreImportSongLookup()
        let converted = await Task.detached(priority: .userInitiated) {
            LXNSCatalogConverter.convertScores(lxScores, songsById: lookup)
        }.value
        return (player?.name, player?.friendCode, converted)
    }

    private func performPullDivingFish(for identityId: String, mergeExisting: Bool, makeActive: Bool) async throws {
        let fetched = try await fetchDivingFishRecordsRaw(for: identityId)

        let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
        var list = identities
        if let idx = list.firstIndex(where: { $0.id == identityId }) {
            if let uname = fetched.username, !uname.isEmpty {
                list[idx].divingFish?.username = uname
            }
            if let nick = fetched.nickname, !nick.isEmpty {
                list[idx].divingFish?.nickname = nick
            }
            list[idx].lastSyncMode = .divingFish
            list[idx].lastSyncedAtEpochMs = nowMs
            if mergeExisting {
                list[idx].hasMixedScoreSources = true
            }
            identities = list
        }

        let incoming = fetched.records
        let finalRecords: [ProberRecord]
        if mergeExisting {
            let existing = await loadRecordsSnapshot(for: identityId)
            finalRecords = existing.isEmpty ? ScoreSyncEngine.applyFullSync(incoming: incoming)
                : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: incoming)
        } else {
            finalRecords = ScoreSyncEngine.applyFullSync(incoming: incoming)
        }
        try await installFetchedRecords(finalRecords, for: identityId, syncMode: .divingFish, makeActive: makeActive)
        await resolveUnknownSongsAfterSync(in: finalRecords, for: identityId)
        SyncDiagnostics.shared.record(operation: "pull_df", channel: "divingFish", message: "同步水鱼成绩完成（共 \(finalRecords.count) 条）")
    }

    private func performPullLXNS(for identityId: String, mergeExisting: Bool, makeActive: Bool) async throws {
        let fetched = try await fetchLXNSRecordsRaw(for: identityId)

        let nowMs = Int64((Date().timeIntervalSince1970 * 1000.0).rounded())
        var list = identities
        if let idx = list.firstIndex(where: { $0.id == identityId }) {
            if let name = fetched.playerName, !name.isEmpty {
                list[idx].lxns?.displayName = name
            }
            if let fc = fetched.friendCode {
                list[idx].lxns?.friendCode = fc
            }
            list[idx].lastSyncMode = .lxns
            list[idx].lastSyncedAtEpochMs = nowMs
            if mergeExisting {
                list[idx].hasMixedScoreSources = true
            }
            identities = list
        }

        let finalRecords: [ProberRecord]
        if mergeExisting {
            let existing = await loadRecordsSnapshot(for: identityId)
            finalRecords = existing.isEmpty ? ScoreSyncEngine.applyFullSync(incoming: fetched.records)
                : ScoreSyncEngine.mergeQuickSync(existing: existing, incoming: fetched.records)
        } else {
            finalRecords = ScoreSyncEngine.applyFullSync(incoming: fetched.records)
        }
        try await installFetchedRecords(finalRecords, for: identityId, syncMode: .lxns, makeActive: makeActive)
        await resolveUnknownSongsAfterSync(in: finalRecords, for: identityId)
        SyncDiagnostics.shared.record(operation: "pull_lxns", channel: "lxns", message: "同步落雪成绩完成（共 \(finalRecords.count) 条）")
    }

    private func performWriteback(for identityId: String, localRecords: [ProberRecord]) async throws -> WritebackSummary {
        guard let identity = identities.first(where: { $0.id == identityId }) else {
            return WritebackSummary(results: [])
        }
        let cnCatalog = pureCNSongsLookup()
        guard !cnCatalog.isEmpty else {
            throw MaimaiAPIError.httpError(-1, "国服曲库尚未加载完成，请稍后再试")
        }
        let titleLookup = scoreImportSongLookup()
        var results: [WritebackChannelStatus] = []

        for channel in identity.uploadTargets {
            switch channel {
            case .divingFish:
                guard let df = identity.divingFish else { continue }
                do {
                    let outcome = try await withTransientRetry {
                        try await writebackToDivingFish(
                            identityId: identityId,
                            binding: df,
                            localRecords: localRecords,
                            songsById: titleLookup,
                            cnSongsById: cnCatalog
                        )
                    }
                    results.append(WritebackChannelStatus(
                        channel: .divingFish,
                        uploadedCount: outcome.uploadedCount,
                        skippedCount: outcome.skippedCount,
                        errorMessage: nil
                    ))
                    SyncDiagnostics.shared.record(
                        operation: "writeback",
                        channel: "divingFish",
                        message: outcome.uploadedCount == 0 ? "水鱼无需更新" : "水鱼上传成功",
                        uploadedCount: outcome.uploadedCount,
                        skippedCount: outcome.skippedCount,
                        sampleSkippedIds: outcome.sampleSkippedIds
                    )
                } catch {
                    recordDiagnosticFailure(operation: "writeback", channel: "divingFish", error: error)
                    results.append(WritebackChannelStatus(
                        channel: .divingFish,
                        uploadedCount: 0,
                        skippedCount: 0,
                        errorMessage: error.localizedDescription
                    ))
                }

            case .lxns:
                guard let lxns = identity.lxns else { continue }
                do {
                    let outcome = try await withTransientRetry {
                        try await writebackToLXNS(
                            identityId: identityId,
                            binding: lxns,
                            localRecords: localRecords,
                            songsById: titleLookup,
                            cnSongsById: cnCatalog
                        )
                    }
                    results.append(WritebackChannelStatus(
                        channel: .lxns,
                        uploadedCount: outcome.uploadedCount,
                        skippedCount: outcome.skippedCount,
                        errorMessage: nil
                    ))
                    SyncDiagnostics.shared.record(
                        operation: "writeback",
                        channel: "lxns",
                        message: outcome.uploadedCount == 0 ? "落雪无需更新" : "落雪上传成功",
                        uploadedCount: outcome.uploadedCount,
                        skippedCount: outcome.skippedCount,
                        sampleSkippedIds: outcome.sampleSkippedIds
                    )
                } catch {
                    recordDiagnosticFailure(operation: "writeback", channel: "lxns", error: error)
                    results.append(WritebackChannelStatus(
                        channel: .lxns,
                        uploadedCount: 0,
                        skippedCount: 0,
                        errorMessage: error.localizedDescription
                    ))
                }

            case .selfHosted:
                continue
            }
        }

        return WritebackSummary(results: results)
    }

    private func writebackToDivingFish(
        identityId: String,
        binding: DivingFishBinding,
        localRecords: [ProberRecord],
        songsById: [Int: SongData],
        cnSongsById: [Int: SongData]
    ) async throws -> (uploadedCount: Int, skippedCount: Int, sampleSkippedIds: [Int]) {
        var secret = AccountCredentialStore.loadDivingFishSecret(for: identityId)
        let username = binding.username.isEmpty ? secret.username : binding.username
        let password = secret.password
        var cookie = secret.cookie

        let cloudResp: ProberRecordsResponse
        switch binding.authMethod {
        case .divingFishPassword:
            let (resp, usedCookie) = try await Task { () -> (ProberRecordsResponse, String) in
                if cookie.isEmpty {
                    guard !username.isEmpty, !password.isEmpty else {
                        throw MaimaiAPIError.unauthorized("水鱼凭据已过期，请解绑后重新绑定")
                    }
                    cookie = try await MaimaiAPI.login(username: username, password: password)
                }
                do {
                    return (try await MaimaiAPI.fetchPlayerRecords(cookie: cookie), cookie)
                } catch where MaimaiAPI.shouldRetryLogin(after: error) {
                    guard !username.isEmpty, !password.isEmpty else { throw error }
                    let freshCookie = try await MaimaiAPI.login(username: username, password: password)
                    return (try await MaimaiAPI.fetchPlayerRecords(cookie: freshCookie), freshCookie)
                }
            }.value
            if usedCookie != secret.cookie {
                secret.cookie = usedCookie
                AccountCredentialStore.saveDivingFishSecret(secret, for: identityId)
            }
            cloudResp = resp

        case .divingFishImportToken:
            guard !secret.importToken.isEmpty else {
                throw MaimaiAPIError.unauthorized("缺少水鱼成绩导入密钥")
            }
            cloudResp = try await Task {
                try await MaimaiAPI.fetchPlayerRecords(importToken: secret.importToken)
            }.value

        default:
            return (0, 0, [])
        }

        let cloudRecords = cloudResp.records ?? []
        let needsTitleMap = ScoreSyncEngine.needsDivingFishTitleMap(
            localRecords: localRecords,
            cloudRecords: cloudRecords,
            cnSongsById: cnSongsById
        )
        if needsTitleMap && divingFishMusicTitles.isEmpty {
            let fetchedTitles: [Int: String]
            do {
                fetchedTitles = try await MaimaiAPI.fetchDivingFishMusicTitles()
            } catch {
                throw MaimaiAPIError.httpError(-1, "获取水鱼曲名列表失败，已取消本次上传以避免误匹配曲目")
            }
            guard !fetchedTitles.isEmpty else {
                throw MaimaiAPIError.httpError(-1, "获取水鱼曲名列表失败，已取消本次上传以避免误匹配曲目")
            }
            divingFishMusicTitles = fetchedTitles
        }

        let result = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords: localRecords,
            cloudRecords: cloudRecords,
            songsById: songsById,
            cnSongsById: cnSongsById,
            musicTitlesById: divingFishMusicTitles
        )
        let uploads = result.uploads
        guard !uploads.isEmpty else {
            return (0, result.skippedDeletedCount, result.sampleSkippedSongIds)
        }

        switch binding.authMethod {
        case .divingFishPassword:
            let finalCookie = try await Task { () -> String in
                do {
                    try await MaimaiAPI.uploadDivingFishRecords(uploads, cookie: secret.cookie)
                    return secret.cookie
                } catch where MaimaiAPI.shouldRetryUploadLogin(after: error) {
                    guard !username.isEmpty, !password.isEmpty else { throw error }
                    let freshCookie = try await MaimaiAPI.login(username: username, password: password)
                    try await MaimaiAPI.uploadDivingFishRecords(uploads, cookie: freshCookie)
                    return freshCookie
                }
            }.value
            if finalCookie != secret.cookie {
                secret.cookie = finalCookie
                AccountCredentialStore.saveDivingFishSecret(secret, for: identityId)
            }

        case .divingFishImportToken:
            try await Task {
                try await MaimaiAPI.uploadDivingFishRecords(uploads, importToken: secret.importToken)
            }.value

        default:
            break
        }
        return (uploads.count, result.skippedDeletedCount, result.sampleSkippedSongIds)
    }

    private func writebackToLXNS(
        identityId: String,
        binding: LXNSBinding,
        localRecords: [ProberRecord],
        songsById: [Int: SongData],
        cnSongsById: [Int: SongData]
    ) async throws -> (uploadedCount: Int, skippedCount: Int, sampleSkippedIds: [Int]) {
        let secret = AccountCredentialStore.loadLXNSSecret(for: identityId)
        let lxScores: [LXNSScore]
        var resolvedBearer: String?

        switch binding.authMethod {
        case .lxnsOAuth:
            let initialToken = try await validLXNSAccessToken(for: identityId, forceRefresh: false)
            do {
                (_, lxScores) = try await Task {
                    try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: initialToken)
                }.value
                resolvedBearer = initialToken
            } catch MaimaiAPIError.unauthorized {
                let refreshed = try await validLXNSAccessToken(
                    for: identityId,
                    forceRefresh: true,
                    failedAccessToken: initialToken
                )
                (_, lxScores) = try await Task {
                    try await MaimaiAPI.fetchLXNSPlayerAndScores(bearerToken: refreshed)
                }.value
                resolvedBearer = refreshed
            }

        case .lxnsPersonalToken:
            guard !secret.personalToken.isEmpty else {
                throw MaimaiAPIError.unauthorized("缺少落雪个人密钥")
            }
            (_, lxScores) = try await Task {
                try await MaimaiAPI.fetchLXNSPlayerAndScores(personalToken: secret.personalToken)
            }.value

        default:
            return (0, 0, [])
        }

        let cloudRecords = await Task.detached(priority: .userInitiated) {
            LXNSCatalogConverter.convertScores(lxScores, songsById: songsById)
        }.value
        let result = ScoreSyncEngine.buildLXNSWriteback(
            localRecords: localRecords,
            cloudRecords: cloudRecords,
            cnSongsById: cnSongsById
        )
        let uploads = result.uploads
        guard !uploads.isEmpty else {
            return (0, result.skippedDeletedCount, result.sampleSkippedSongIds)
        }

        do {
            switch binding.authMethod {
            case .lxnsOAuth:
                let bearer: String
                if let resolvedBearer {
                    bearer = resolvedBearer
                } else {
                    bearer = try await validLXNSAccessToken(for: identityId, forceRefresh: false)
                }
                do {
                    try await Task {
                        try await MaimaiAPI.uploadLXNSScores(uploads, bearerToken: bearer)
                    }.value
                } catch MaimaiAPIError.unauthorized {
                    let refreshed = try await validLXNSAccessToken(
                        for: identityId,
                        forceRefresh: true,
                        failedAccessToken: bearer
                    )
                    try await Task {
                        try await MaimaiAPI.uploadLXNSScores(uploads, bearerToken: refreshed)
                    }.value
                }

            case .lxnsPersonalToken:
                try await Task {
                    try await MaimaiAPI.uploadLXNSScores(uploads, personalToken: secret.personalToken)
                }.value

            default:
                break
            }
        } catch let error as MaimaiAPIError {
            if case .httpError(let status, _) = error, status == 403 {
                var list = identities
                if let idx = list.firstIndex(where: { $0.id == identityId.lowercased() }) {
                    list[idx].lxns?.hasWriteScope = false
                    identities = list
                }
            }
            throw error
        }
        return (uploads.count, result.skippedDeletedCount, result.sampleSkippedSongIds)
    }

    /// Serializes LXNS refresh-token exchange per identity and persists rotated `refresh_token` immediately.
    private func validLXNSAccessToken(
        for identityId: String,
        forceRefresh: Bool,
        failedAccessToken: String? = nil
    ) async throws -> String {
        let lower = identityId.lowercased()
        if let existingTask = inFlightTokenRefreshTasks[lower] {
            return try await existingTask.value
        }

        let currentSecret = AccountCredentialStore.loadLXNSSecret(for: lower)
        let alreadyRotatedSinceFailure = failedAccessToken != nil
            && !currentSecret.accessToken.isEmpty
            && currentSecret.accessToken != failedAccessToken
        if (!forceRefresh || alreadyRotatedSinceFailure),
           !currentSecret.accessToken.isEmpty,
           let expiresSec = currentSecret.accessTokenExpiresAtEpochSec,
           Date().timeIntervalSince1970 < Double(expiresSec) - SyncPolicy.oauthExpiryBufferSeconds {
            return currentSecret.accessToken
        }

        guard !currentSecret.refreshToken.isEmpty else {
            throw MaimaiAPIError.unauthorized("落雪授权已过期，请解绑后重新绑定")
        }

        let refreshTokenToUse = currentSecret.refreshToken
        let task = Task<String, Error> { @MainActor in
            defer { inFlightTokenRefreshTasks[lower] = nil }
            let refreshed = try await MaimaiAPI.refreshLXNSToken(refreshToken: refreshTokenToUse)
            guard identities.contains(where: { $0.id == lower && $0.lxns != nil }) else {
                throw MaimaiAPIError.unauthorized("该落雪绑定已被移除")
            }
            var updated = AccountCredentialStore.loadLXNSSecret(for: lower)
            updated.accessToken = refreshed.accessToken
            if let newRefresh = refreshed.refreshToken, !newRefresh.isEmpty {
                updated.refreshToken = newRefresh
            }
            updated.accessTokenExpiresAtEpochSec = Int64((Date().timeIntervalSince1970 + Double(refreshed.expiresIn)).rounded())
            if let newScope = refreshed.scope, !newScope.isEmpty {
                updated.grantedScopes = newScope
            }
            let persisted = AccountCredentialStore.saveLXNSSecret(updated, for: lower)
                || AccountCredentialStore.saveLXNSSecret(updated, for: lower)
            guard persisted else {
                throw MaimaiAPIError.unauthorized("落雪授权令牌保存失败，请解绑后重新绑定")
            }
            return refreshed.accessToken
        }
        inFlightTokenRefreshTasks[lower] = task
        return try await task.value
    }

    private func pureCNSongsLookup() -> [Int: SongData] {
        cnSongsById
    }

    private func scoreImportSongLookup() -> [Int: SongData] {
        var map = deletedSongsById
        if !songsById.isEmpty {
            map.merge(songsById) { _, active in active }
        }
        if !cnSongsById.isEmpty {
            map.merge(cnSongsById) { _, cn in cn }
        }
        return map
    }

    private func loadRecordsSnapshot(for identityId: String) async -> [ProberRecord] {
        let lower = identityId.lowercased()
        if lower == activeIdentityId && recordsOwnerIdentityId == lower && !records.isEmpty {
            return records
        }
        let url = AccountCredentialStore.recordsFileURL(for: lower, in: appSupportDir)
        return await Task.detached(priority: .utility) {
            guard let data = try? Data(contentsOf: url),
                  let decoded = try? JSONDecoder().decode([ProberRecord].self, from: data) else {
                return []
            }
            return ScoreSyncEngine.normalizeCachedRecords(decoded)
        }.value
    }

    private func installFetchedRecords(
        _ fetched: [ProberRecord],
        for identityId: String,
        syncMode: SyncMode,
        makeActive: Bool
    ) async throws {
        let lower = identityId.lowercased()
        let fileURL = AccountCredentialStore.recordsFileURL(for: lower, in: appSupportDir)
        let encoded = try JSONEncoder().encode(fetched)
        try await Task.detached(priority: .userInitiated) {
            try encoded.write(to: fileURL, options: .atomic)
        }.value

        let historyLookup = cnSongsById
        if !historyLookup.isEmpty {
            let historyStore = syncHistoryStore
            Task.detached(priority: .utility) {
                try? historyStore.appendSnapshot(
                    identityId: lower,
                    syncMode: syncMode,
                    records: fetched,
                    songsById: historyLookup
                )
            }
        }

        if makeActive && activeIdentityId != lower {
            isRestoringCachedRecords = false
            suppressNextIdentityRestore = true
            activeIdentityId = lower
        }
        if activeIdentityId == lower {
            recordsEpoch += 1
            isRestoringCachedRecords = false
            setRecordsSnapshot(fetched, ownerIdentityId: lower)
            prefetchBest50Covers()
        }
    }

    private func resolveUnknownSongsAfterSync(in syncedRecords: [ProberRecord], for identityId: String) async {
        guard !syncedRecords.isEmpty else { return }
        func computeUnknownIds() -> Set<Int> {
            var out = Set<Int>()
            for rec in syncedRecords {
                let id = rec.songId
                if id > 0,
                   self.cnSongsById[id] == nil,
                   self.deletedSongsById[id] == nil,
                   !self.unresolvableDeletedSongIds.contains(id) {
                    out.insert(id)
                }
            }
            return out
        }

        var unknownIds = computeUnknownIds()
        guard !unknownIds.isEmpty else { return }

        var didRefreshCN = false
        if !isFetchingCNSongs {
            let canRefreshCN: Bool = {
                guard let lastAttempt = lastUnknownSongCatalogRefreshDate else { return true }
                return Date().timeIntervalSince(lastAttempt) >= SyncPolicy.unknownSongCatalogCooldown
            }()
            if canRefreshCN {
                let result = await performLXNSCatalogSync()
                if let fetchedSongs = result.songs, !fetchedSongs.isEmpty {
                    lastUnknownSongCatalogRefreshDate = Date()
                    didRefreshCN = true
                    let cnSnapshot = await storeCNBaseline(fetchedSongs)
                    if serverRegion == .cn {
                        applySnapshot(cnSnapshot, epoch: regionEpoch)
                    } else if let catalog = zetarakuCatalog {
                        await applyZetarakuCatalog(catalog, for: serverRegion, epoch: regionEpoch)
                    }
                    unknownIds = computeUnknownIds()
                }
            }
        }

        let didConfirmFreshCNCatalog = didRefreshCN || (
            !cnSongsById.isEmpty &&
            (lastCNCatalogSync.map { Date().timeIntervalSince($0) < SyncPolicy.unknownSongCatalogCooldown } ?? false)
        )

        if !unknownIds.isEmpty && didConfirmFreshCNCatalog {
            if zetarakuCatalog == nil || dxDataIdMap == nil {
                let canFetchZetaraku: Bool = {
                    guard let lastAttempt = lastDeletedSongsResolveAttemptDate else { return true }
                    return Date().timeIntervalSince(lastAttempt) >= SyncPolicy.deletedSongsResolveCooldown
                }()
                if canFetchZetaraku && !isFetchingZetaraku {
                    let zErr = await performZetarakuSync()
                    if zErr == nil && zetarakuCatalog != nil {
                        lastDeletedSongsResolveAttemptDate = Date()
                    }
                }
            }

            if let zCatalog = zetarakuCatalog, let idMap = dxDataIdMap {
                let resolution = await Task.detached(priority: .userInitiated) {
                    ZetarakuCatalogConverter.resolveDeletedCNSongs(
                        unknownIds: unknownIds,
                        catalog: zCatalog,
                        dxIdMap: idMap
                    )
                }.value

                if !resolution.resolved.isEmpty || !resolution.unresolvableIds.isEmpty {
                    var updatedDeleted = deletedSongsById
                    for song in resolution.resolved where song.numericId > 0 {
                        updatedDeleted[song.numericId] = song
                    }
                    deletedSongsById = updatedDeleted
                    unresolvableDeletedSongIds.formUnion(resolution.unresolvableIds)

                    let cachePayload = DeletedCNSongsCache(
                        songs: Array(updatedDeleted.values).sorted { $0.numericId < $1.numericId },
                        unresolvableIds: Array(unresolvableDeletedSongIds).sorted()
                    )
                    let cacheURL = cachedDeletedCNSongsURL
                    Task.detached(priority: .utility) {
                        if let encoded = try? JSONEncoder().encode(cachePayload) {
                            try? encoded.write(to: cacheURL, options: .atomic)
                        }
                    }
                    catalogRevision &+= 1
                    SyncDiagnostics.shared.record(
                        operation: "resolve_deleted",
                        message: "解析删除曲 \(resolution.resolved.count) 首，未收录 \(resolution.unresolvableIds.count) 首",
                        sampleSkippedIds: Array(resolution.unresolvableIds).sorted()
                    )
                }
            }
        }

        if activeIdentityId == identityId.lowercased() {
            let lookup = scoreImportSongLookup()
            let reprojected = records.map { rec -> ProberRecord in
                if let song = lookup[rec.songId] {
                    return rec.projected(onto: song, region: serverRegion)
                }
                return rec
            }
            setRecordsSnapshot(reprojected, ownerIdentityId: identityId)
        }
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
        let targetId = activeIdentityId
        recordsEpoch += 1
        let epoch = recordsEpoch
        isRestoringCachedRecords = true
        setRecordsSnapshot([], ownerIdentityId: nil)
        guard let targetId else {
            isRestoringCachedRecords = false
            return
        }
        let url = AccountCredentialStore.recordsFileURL(for: targetId, in: appSupportDir)
        Task(priority: .userInitiated) {
            let cached = await Task.detached(priority: .utility) { () -> [ProberRecord]? in
                guard let data = try? Data(contentsOf: url),
                      let decoded = try? JSONDecoder().decode([ProberRecord].self, from: data) else { return nil }
                return ScoreSyncEngine.normalizeCachedRecords(decoded)
            }.value
            guard activeIdentityId == targetId,
                  recordsEpoch == epoch else { return }
            isRestoringCachedRecords = false
            setRecordsSnapshot(cached ?? [], ownerIdentityId: targetId)
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
        let pool: [SongData]
        if serverRegion == .cn, !deletedSongsById.isEmpty, !playedSongIds.isEmpty {
            var list = songs
            for songId in playedSongIds {
                if songsById[songId] == nil, let deleted = deletedSongsById[songId] {
                    list.append(deleted)
                }
            }
            pool = list
        } else {
            pool = songs
        }
        return SongFilter.apply(
            criteria,
            to: pool,
            favoriteIds: favoriteIds,
            enableAliasSearch: enableAliasSearch,
            enableCharterSearch: enableCharterSearch,
            region: serverRegion,
            playedSongIds: playedSongIds
        )
    }

    // MARK: - Records & Best50 Helpers

    var isLoggedIn: Bool {
        activeIdentity != nil
    }

    func song(forId rawId: String) -> SongData? {
        if let numId = Int(rawId) {
            if let active = songsById[numId] { return active }
            if serverRegion == .cn, let deleted = deletedSongsById[numId] { return deleted }
        }
        return songs.first { $0.id == rawId }
    }

    /// Finds the SD <-> DX counterpart of the same song (`id ± 10000` or same title+genre in JP/INTL), if present.
    func counterpartSong(for song: SongData) -> SongData? {
        guard !song.isUtage else { return nil }
        if song.numericId > 0 {
            let targetId = song.numericId < 10_000 ? song.numericId + 10_000 : song.numericId - 10_000
            if let candidate = songsById[targetId] ?? (serverRegion == .cn ? deletedSongsById[targetId] : nil),
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
        if let exact = recordsByKey["\(songId)_\(difficultyIndex)"] {
            return exact
        }
        if difficultyIndex == 11 {
            return recordsByKey["\(songId)_10"]
        }
        return nil
    }

    func sortedRecords(isNewVersion: Bool) -> [ProberRecord] {
        let region = serverRegion
        return records.compactMap { rec in
            guard let song = songsById[rec.songId],
                  !song.isUtage,
                  song.isDisabled != true,
                  song.chartArrayIndex(forRecordLevelIndex: rec.levelIndex) != nil,
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
                if deletedSongsById[rec.songId] != nil || unresolvableDeletedSongIds.contains(rec.songId) {
                    continue
                }
                if region == .cn || cnSongsById[rec.songId] == nil {
                    matching = false
                }
                continue
            }
            guard !song.isUtage,
                  song.isDisabled != true,
                  song.chartArrayIndex(forRecordLevelIndex: rec.levelIndex) != nil else { continue }
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
        if lhs.songId != rhs.songId {
            return lhs.songId < rhs.songId
        }
        return lhs.levelIndex < rhs.levelIndex
    }

    var displayPlayerName: String {
        activeIdentity?.primaryDisplayName ?? "Player"
    }

    var activeB50PlayerName: String {
        activeIdentity?.b50PlayerName ?? "Player"
    }
}

private struct LocalCaches {
    var songs: [SongData]?
    var plates: CNPlateCatalog?
    var zetaraku: ZetarakuCatalog?
    var dxIdMap: DXDataIdMap?
    var deletedCache: DeletedCNSongsCache?
    var stats: ChartStatsResponse?
    var records: [ProberRecord]?
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
