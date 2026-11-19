package dev.sorrowrain.maimaidata.services

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.sorrowrain.maimaidata.BuildConfig
import dev.sorrowrain.maimaidata.models.AppReleaseInfo
import dev.sorrowrain.maimaidata.models.CNPlateCatalog
import dev.sorrowrain.maimaidata.models.CNPlateRuleGroup
import dev.sorrowrain.maimaidata.models.ChartStatEntry
import dev.sorrowrain.maimaidata.models.ChartStatsResponse
import dev.sorrowrain.maimaidata.models.LXNSPlayer
import dev.sorrowrain.maimaidata.models.LXNSScore
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.MaxNotesStats
import dev.sorrowrain.maimaidata.models.PlateCheckScope
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.ScoreProfile
import dev.sorrowrain.maimaidata.models.ScoreProfileSecret
import dev.sorrowrain.maimaidata.models.ScoreProvider
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

val LocalDataStore = staticCompositionLocalOf<DataStore> {
    error("LocalDataStore has not been provided")
}

data class Best50Summary(
    val b35: List<ProberRecord>,
    val b15: List<ProberRecord>,
    val isMatching: Boolean
) {
    val b35Rating: Int = b35.sumOf { it.computedRating }
    val b15Rating: Int = b15.sumOf { it.computedRating }
    val totalRating: Int get() = b35Rating + b15Rating
}

data class DataSyncNotice(
    val kind: Kind,
    val message: String,
    val id: String = UUID.randomUUID().toString()
) {
    enum class Kind {
        SONGS,
        STATS
    }
}

/**
 * Global application state center (1:1 architectural and business-rule parity with iOS `DataStore.swift`).
 * Exposes Compose snapshot-backed properties mutated on `Dispatchers.Main.immediate` while running all
 * JSON serialization, catalog conversion, and encrypted credential I/O off the main thread.
 */
@Stable
class DataStore(context: Context) {
    private val appContext: Context = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PreferenceKeys.PREFS_NAME, Context.MODE_PRIVATE)
    private val appFilesDir: File = appContext.filesDir
    private val storeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private object SyncPolicy {
        const val CATALOG_AUTO_REFRESH_MS = 7L * 24L * 3600L * 1000L
        const val CATALOG_MANUAL_COOLDOWN_MS = 10L * 60L * 1000L
        const val STATS_AUTO_REFRESH_MS = 5L * 24L * 3600L * 1000L
        const val STATS_MANUAL_COOLDOWN_MS = 5L * 60L * 1000L
        const val APP_UPDATE_CHECK_INTERVAL_MS = 24L * 3600L * 1000L
        const val BOOTSTRAP_DELAY_MS = 3_000L
        const val OAUTH_EXPIRY_BUFFER_SEC = 30L
    }

    // Domain Catalog & Score State
    var songs: List<SongData> by mutableStateOf(emptyList())
        private set
    var songsById: Map<Int, SongData> by mutableStateOf(emptyMap())
        private set
    var cnSongs: List<SongData> by mutableStateOf(emptyList())
        private set
    var cnSongsById: Map<Int, SongData> by mutableStateOf(emptyMap())
        private set
    var plateCatalog: CNPlateCatalog? by mutableStateOf(null)
        private set
    private var zetarakuCatalog: ZetarakuCatalog? by mutableStateOf(null)
    private var dxDataIdMap: DXDataIdMap? = null

    var chartStats: Map<Int, List<ChartStatEntry>> by mutableStateOf(emptyMap())
        private set

    private var _records: List<ProberRecord> by mutableStateOf(emptyList())
    private var recordsByKey: Map<String, ProberRecord> = emptyMap()
    val records: List<ProberRecord>
        get() = _records

    var catalogRevision: Int by mutableIntStateOf(0)
        private set
    var maxNotesStats: MaxNotesStats by mutableStateOf(MaxNotesStats.fallback)
        private set

    // Monotonic concurrency race guards
    private var regionEpoch: Int = 0
    private var recordsEpoch: Int = 0
    private var didBootstrap: Boolean = false

    // Sync & Loading Flags
    private var isFetchingCNSongs: Boolean by mutableStateOf(false)
    private var isFetchingZetaraku: Boolean by mutableStateOf(false)
    val isSyncingSongs: Boolean
        get() = isFetchingCNSongs || isFetchingZetaraku

    var isSyncingStats: Boolean by mutableStateOf(false)
        private set
    var isSyncingRecords: Boolean by mutableStateOf(false)
        private set
    var isLoadingLocalData: Boolean by mutableStateOf(true)
        private set

    var statusMessage: String? by mutableStateOf(null)
    var syncNotice: DataSyncNotice? by mutableStateOf(null)
    var availableAppUpdate: AppReleaseInfo? by mutableStateOf(null)
    var isCheckingAppUpdate: Boolean by mutableStateOf(false)
        private set

    // OAuth Deep-Link Callback Stream & Pending PKCE Session
    private data class PendingPKCESession(
        val codeVerifier: String,
        val state: String
    )

    @Volatile
    private var pendingPKCESession: PendingPKCESession? = null

    private val _pendingOAuthCallbacks = Channel<String>(capacity = Channel.BUFFERED)
    val pendingOAuthCallbacks: Flow<String> = _pendingOAuthCallbacks.receiveAsFlow()

    // Per-profile single-flight mutex for LXNS refresh_token rotation & profile list write serialization
    private val tokenRefreshMutexes = ConcurrentHashMap<String, Mutex>()
    private val profilePersistMutex = Mutex()

    // Persisted Preferences & Multi-Provider Score Profiles
    private var _favoriteIds: Set<String> by mutableStateOf(emptySet())
    var favoriteIds: Set<String>
        get() = _favoriteIds
        set(value) {
            if (_favoriteIds != value) {
                _favoriteIds = value
                prefs.edit().putStringSet(PreferenceKeys.favorites, HashSet(value)).apply()
            }
        }

    private var _searchHistory: List<String> by mutableStateOf(emptyList())
    var searchHistory: List<String>
        get() = _searchHistory
        set(value) {
            if (_searchHistory != value) {
                _searchHistory = value
                prefs.edit()
                    .putString(PreferenceKeys.searchHistory, MaimaiAPI.json.encodeToString(value))
                    .apply()
            }
        }

    var profiles: List<ScoreProfile> by mutableStateOf(emptyList())
        private set

    var activeProfileId: String? by mutableStateOf(null)
        private set

    val activeProfile: ScoreProfile?
        get() {
            val id = activeProfileId ?: return null
            return profiles.firstOrNull { it.id == id }
        }

    val isLoggedIn: Boolean
        get() = activeProfile != null

    private var _serverRegion: MaimaiConstants.ServerRegion by mutableStateOf(MaimaiConstants.ServerRegion.CN)
    var serverRegion: MaimaiConstants.ServerRegion
        get() = _serverRegion
        private set(value) {
            if (_serverRegion != value) {
                _serverRegion = value
                prefs.edit().putString(PreferenceKeys.serverRegion, value.rawValue).apply()
            }
        }

    private var _lastCNCatalogSyncEpochMs: Long? by mutableStateOf(null)
    var lastCNCatalogSyncEpochMs: Long?
        get() = _lastCNCatalogSyncEpochMs
        private set(value) {
            _lastCNCatalogSyncEpochMs = value
            writeOptionalEpochMs(PreferenceKeys.cnCatalogSync, value)
        }

    private var _lastZetarakuSyncEpochMs: Long? by mutableStateOf(null)
    var lastZetarakuSyncEpochMs: Long?
        get() = _lastZetarakuSyncEpochMs
        private set(value) {
            _lastZetarakuSyncEpochMs = value
            writeOptionalEpochMs(PreferenceKeys.zetarakuSync, value)
        }

    private var _lastChartStatsSyncEpochMs: Long? by mutableStateOf(null)
    var lastChartStatsSyncEpochMs: Long?
        get() = _lastChartStatsSyncEpochMs
        private set(value) {
            _lastChartStatsSyncEpochMs = value
            writeOptionalEpochMs(PreferenceKeys.chartStatsSync, value)
        }

    private var _enableShowAlias: Boolean by mutableStateOf(true)
    var enableShowAlias: Boolean
        get() = _enableShowAlias
        set(value) {
            if (_enableShowAlias != value) {
                _enableShowAlias = value
                prefs.edit().putBoolean(PreferenceKeys.showAlias, value).apply()
            }
        }

    private var _enableAliasSearch: Boolean by mutableStateOf(true)
    var enableAliasSearch: Boolean
        get() = _enableAliasSearch
        set(value) {
            if (_enableAliasSearch != value) {
                _enableAliasSearch = value
                prefs.edit().putBoolean(PreferenceKeys.aliasSearch, value).apply()
            }
        }

    private var _enableCharterSearch: Boolean by mutableStateOf(false)
    var enableCharterSearch: Boolean
        get() = _enableCharterSearch
        set(value) {
            if (_enableCharterSearch != value) {
                _enableCharterSearch = value
                prefs.edit().putBoolean(PreferenceKeys.charterSearch, value).apply()
            }
        }

    private var _useProberNickname: Boolean by mutableStateOf(true)
    var useProberNickname: Boolean
        get() = _useProberNickname
        set(value) {
            if (_useProberNickname != value) {
                _useProberNickname = value
                prefs.edit().putBoolean(PreferenceKeys.useDFNickname, value).apply()
            }
        }

    private var _customNickname: String by mutableStateOf("")
    var customNickname: String
        get() = _customNickname
        set(value) {
            if (_customNickname != value) {
                _customNickname = value
                prefs.edit().putString(PreferenceKeys.customNickname, value).apply()
            }
        }

    private var _lastVersionCheckIndex: Int by mutableIntStateOf(0)
    var lastVersionCheckIndex: Int
        get() = _lastVersionCheckIndex
        set(value) {
            if (_lastVersionCheckIndex != value) {
                _lastVersionCheckIndex = value
                prefs.edit().putInt(PreferenceKeys.lastVersionCheck, value).apply()
            }
        }

    private var _lastVersionCheckIntlIndex: Int by mutableIntStateOf(0)
    var lastVersionCheckIntlIndex: Int
        get() = _lastVersionCheckIntlIndex
        set(value) {
            if (_lastVersionCheckIntlIndex != value) {
                _lastVersionCheckIntlIndex = value
                prefs.edit().putInt(PreferenceKeys.lastVersionCheckIntl, value).apply()
            }
        }

    private var _lastVersionCheckJpIndex: Int by mutableIntStateOf(0)
    var lastVersionCheckJpIndex: Int
        get() = _lastVersionCheckJpIndex
        set(value) {
            if (_lastVersionCheckJpIndex != value) {
                _lastVersionCheckJpIndex = value
                prefs.edit().putInt(PreferenceKeys.lastVersionCheckJp, value).apply()
            }
        }

    private var _lastLevelCheckIndex: Int by mutableIntStateOf(0)
    var lastLevelCheckIndex: Int
        get() = _lastLevelCheckIndex
        set(value) {
            if (_lastLevelCheckIndex != value) {
                _lastLevelCheckIndex = value
                prefs.edit().putInt(PreferenceKeys.lastLevelCheck, value).apply()
            }
        }

    var activeVersionCheckIndex: Int
        get() = when (serverRegion) {
            MaimaiConstants.ServerRegion.CN -> lastVersionCheckIndex
            MaimaiConstants.ServerRegion.INTL -> lastVersionCheckIntlIndex
            MaimaiConstants.ServerRegion.JP -> lastVersionCheckJpIndex
        }
        set(value) {
            when (serverRegion) {
                MaimaiConstants.ServerRegion.CN -> lastVersionCheckIndex = value
                MaimaiConstants.ServerRegion.INTL -> lastVersionCheckIntlIndex = value
                MaimaiConstants.ServerRegion.JP -> lastVersionCheckJpIndex = value
            }
        }

    val displayPlayerName: String
        get() {
            if (!useProberNickname) {
                val trimmed = customNickname.trim()
                if (trimmed.isNotEmpty()) return trimmed
            }
            activeProfile?.let { return it.displayTitle }
            return "Player"
        }

    var cnPlateCheckGroups: List<CNPlateRuleGroup> by mutableStateOf(emptyList())
        private set

    var activePlateScopes: List<PlateCheckScope> by mutableStateOf(emptyList())
        private set

    private fun rebuildPlateScopeCache() {
        val list = plateCatalog?.groups.orEmpty().toMutableList()
        val sourceSongs = cnSongs.ifEmpty { songs }
        val newSongs = sourceSongs.filter {
            !it.isUtage && it.isDisabled != true && it.basicInfo.isNew && it.numericId > 0
        }
        if (newSongs.isNotEmpty()) {
            val currentTitle = newSongs.first().basicInfo.from.ifEmpty { MaimaiConstants.latestCNVersion.name }
            val alreadyCovered = list.any { it.title.contains(currentTitle) }
            if (!alreadyCovered) {
                list += CNPlateRuleGroup(
                    id = "current_$currentTitle",
                    title = currentTitle,
                    versionAsset = MaimaiConstants.cnVersionAsset(currentTitle) ?: MaimaiConstants.latestCNVersion.asset,
                    badges = emptyList(),
                    basicToMasterSongIds = newSongs.map { it.numericId },
                    remasterSongIds = emptyList()
                )
            }
        }
        cnPlateCheckGroups = list
        activePlateScopes = LXNSCatalogConverter.buildPlateScopes(
            region = serverRegion,
            cnPlateGroups = list,
            activeSongs = songs,
            zetarakuVersions = zetarakuCatalog?.versions
        )
    }

    val lastCatalogSyncText: String
        get() {
            val latest = when {
                lastCNCatalogSyncEpochMs != null && lastZetarakuSyncEpochMs != null ->
                    max(lastCNCatalogSyncEpochMs!!, lastZetarakuSyncEpochMs!!)
                lastCNCatalogSyncEpochMs != null -> lastCNCatalogSyncEpochMs
                lastZetarakuSyncEpochMs != null -> lastZetarakuSyncEpochMs
                else -> null
            }
            return latest?.let(::formatSyncTimestamp) ?: "内置离线数据"
        }

    val lastChartStatsSyncText: String
        get() = lastChartStatsSyncEpochMs?.let(::formatSyncTimestamp) ?: "尚未同步"

    private val cachedSongsFile: File get() = File(appFilesDir, "songdata.json")
    private val cachedPlatesFile: File get() = File(appFilesDir, "plate_rules.json")
    private val cachedZetarakuFile: File get() = File(appFilesDir, "zetaraku_data.json")
    private val cachedDXDataIdMapFile: File get() = File(appFilesDir, "dxdata_id_map.json")
    private val cachedStatsFile: File get() = File(appFilesDir, "chart_stats.json")

    init {
        PreferenceKeys.ensureDefaultBooleans(prefs)

        _favoriteIds = prefs.getStringSet(PreferenceKeys.favorites, null)?.toSet() ?: emptySet()
        _searchHistory = prefs.getString(PreferenceKeys.searchHistory, null)?.let { raw ->
            runCatching { MaimaiAPI.json.decodeFromString<List<String>>(raw) }.getOrNull()
        } ?: emptyList()

        val loadedProfiles = AccountCredentialStore.loadOrMigrateProfiles(appContext, prefs)
        profiles = loadedProfiles.profiles
        activeProfileId = loadedProfiles.activeProfileId

        _serverRegion = MaimaiConstants.ServerRegion.fromRawValue(
            prefs.getString(PreferenceKeys.serverRegion, null)
        )

        _lastCNCatalogSyncEpochMs = readOptionalEpochMs(PreferenceKeys.cnCatalogSync)
        _lastZetarakuSyncEpochMs = readOptionalEpochMs(PreferenceKeys.zetarakuSync)
        _lastChartStatsSyncEpochMs = readOptionalEpochMs(PreferenceKeys.chartStatsSync)

        _enableShowAlias = prefs.getBoolean(PreferenceKeys.showAlias, true)
        _enableAliasSearch = prefs.getBoolean(PreferenceKeys.aliasSearch, true)
        _enableCharterSearch = prefs.getBoolean(PreferenceKeys.charterSearch, false)
        _useProberNickname = prefs.getBoolean(PreferenceKeys.useDFNickname, true)
        _customNickname = prefs.getString(PreferenceKeys.customNickname, null).orEmpty()

        _lastVersionCheckIndex = prefs.getInt(PreferenceKeys.lastVersionCheck, 0)
        _lastVersionCheckIntlIndex = prefs.getInt(PreferenceKeys.lastVersionCheckIntl, 0)
        _lastVersionCheckJpIndex = prefs.getInt(PreferenceKeys.lastVersionCheckJp, 0)
        _lastLevelCheckIndex = prefs.getInt(PreferenceKeys.lastLevelCheck, 0)
    }

    // Bootstrap & Local Caching

    fun launchBootstrap() {
        storeScope.launch {
            bootstrap()
        }
    }

    suspend fun bootstrap() = withContext(Dispatchers.Main.immediate) {
        if (didBootstrap) return@withContext
        didBootstrap = true
        loadLocalCaches()

        if (serverRegion != MaimaiConstants.ServerRegion.CN && zetarakuCatalog == null) {
            syncZetarakuCatalog()
        }

        delay(SyncPolicy.BOOTSTRAP_DELAY_MS)
        coroutineScope {
            val catalogJob = async { syncAllCatalogsAndRules(force = false) }
            val statsJob = async { refreshChartStatsIfStale(force = false) }
            val updateJob = async { checkAppUpdateIfNeeded(manual = false) }
            catalogJob.await()
            statsJob.await()
            updateJob.await()
        }
    }

    suspend fun checkAppUpdateIfNeeded(manual: Boolean) = withContext(Dispatchers.Main.immediate) {
        if (isCheckingAppUpdate) return@withContext
        val nowMs = System.currentTimeMillis()
        if (!manual) {
            val lastMs = prefs.getLong(PreferenceKeys.appUpdateCheck, 0L)
            if (lastMs > 0L && nowMs - lastMs < SyncPolicy.APP_UPDATE_CHECK_INTERVAL_MS) {
                return@withContext
            }
        }

        isCheckingAppUpdate = true
        try {
            val latest = MaimaiAPI.fetchLatestGitHubRelease(manual = manual)
            prefs.edit().putLong(PreferenceKeys.appUpdateCheck, System.currentTimeMillis()).apply()
            val currentShort = BuildConfig.VERSION_NAME

            if (latest != null && latest.isNewerThan(currentShort)) {
                val ignored = prefs.getString(PreferenceKeys.ignoredAppUpdateVersion, null)
                if (manual || ignored != latest.version) {
                    availableAppUpdate = latest
                }
            } else if (manual) {
                statusMessage = "当前已是最新版本（v$currentShort）"
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (manual) {
                statusMessage = "检查更新失败，请检查网络连接后重试"
            }
        } finally {
            isCheckingAppUpdate = false
        }
    }

    fun ignoreAppUpdate(version: String) {
        prefs.edit().putString(PreferenceKeys.ignoredAppUpdateVersion, version).apply()
        availableAppUpdate = null
    }

    private data class LocalCaches(
        val songs: List<SongData>? = null,
        val plates: CNPlateCatalog? = null,
        val zetaraku: ZetarakuCatalog? = null,
        val dxIdMap: DXDataIdMap? = null,
        val stats: ChartStatsResponse? = null,
        val records: List<ProberRecord>? = null
    )

    private data class SongSnapshot(
        val songs: List<SongData>,
        val songsById: Map<Int, SongData>,
        val maxNotes: MaxNotesStats
    )

    private suspend fun loadLocalCaches() {
        val currentProfileId = activeProfileId
        val recordsFile = currentProfileId?.let { AccountCredentialStore.recordsFile(appFilesDir, it) }

        val caches = withContext(Dispatchers.IO) {
            var loadedSongs: List<SongData>? = null
            if (cachedSongsFile.exists()) {
                loadedSongs = runCatching {
                    MaimaiAPI.json.decodeFromString<List<SongData>>(cachedSongsFile.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            if (loadedSongs == null) {
                val seedBytes = runCatching {
                    appContext.assets.open("songdata_seed.json").use { it.readBytes() }
                }.getOrNull()
                if (seedBytes != null) {
                    val decoded = runCatching {
                        MaimaiAPI.json.decodeFromString<List<SongData>>(seedBytes.toString(Charsets.UTF_8))
                    }.getOrNull()
                    if (decoded != null) {
                        writeBytesAtomically(cachedSongsFile, seedBytes)
                        loadedSongs = decoded
                    }
                }
            }

            var loadedPlates: CNPlateCatalog? = null
            if (cachedPlatesFile.exists()) {
                loadedPlates = runCatching {
                    MaimaiAPI.json.decodeFromString<CNPlateCatalog>(cachedPlatesFile.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            if (loadedPlates == null) {
                val plateSeedBytes = runCatching {
                    appContext.assets.open("plate_rules_seed.json").use { it.readBytes() }
                }.getOrNull()
                if (plateSeedBytes != null) {
                    val decoded = runCatching {
                        MaimaiAPI.json.decodeFromString<CNPlateCatalog>(plateSeedBytes.toString(Charsets.UTF_8))
                    }.getOrNull()
                    if (decoded != null) {
                        writeBytesAtomically(cachedPlatesFile, plateSeedBytes)
                        loadedPlates = decoded
                    }
                }
            }

            val loadedZetaraku = if (cachedZetarakuFile.exists()) {
                runCatching {
                    MaimaiAPI.json.decodeFromString<ZetarakuCatalog>(cachedZetarakuFile.readText(Charsets.UTF_8))
                }.getOrNull()
            } else {
                null
            }

            val loadedIdMap = if (cachedDXDataIdMapFile.exists()) {
                runCatching {
                    MaimaiAPI.json.decodeFromString<DXDataIdMap>(cachedDXDataIdMapFile.readText(Charsets.UTF_8))
                }.getOrNull()
            } else {
                null
            }

            val loadedStats = if (cachedStatsFile.exists()) {
                runCatching {
                    MaimaiAPI.json.decodeFromString<ChartStatsResponse>(cachedStatsFile.readText(Charsets.UTF_8))
                }.getOrNull()
            } else {
                null
            }

            val loadedRecords = if (recordsFile != null && recordsFile.exists()) {
                runCatching {
                    MaimaiAPI.json.decodeFromString<List<ProberRecord>>(recordsFile.readText(Charsets.UTF_8))
                }.getOrNull()
            } else {
                null
            }

            LocalCaches(
                songs = loadedSongs,
                plates = loadedPlates,
                zetaraku = loadedZetaraku,
                dxIdMap = loadedIdMap,
                stats = loadedStats,
                records = loadedRecords
            )
        }

        plateCatalog = caches.plates
        zetarakuCatalog = caches.zetaraku
        dxDataIdMap = caches.dxIdMap
        var cnSnapshot: SongSnapshot? = null
        if (caches.songs != null) {
            cnSnapshot = storeCNBaseline(caches.songs)
        }

        val epoch = regionEpoch
        when (serverRegion) {
            MaimaiConstants.ServerRegion.CN -> {
                cnSnapshot?.let { applySnapshot(it, epoch = epoch) }
            }
            MaimaiConstants.ServerRegion.INTL, MaimaiConstants.ServerRegion.JP -> {
                zetarakuCatalog?.let { catalog ->
                    applyZetarakuCatalog(catalog, region = serverRegion, epoch = epoch)
                }
            }
        }
        rebuildPlateScopeCache()

        caches.stats?.let(::applyChartStats)
        if (activeProfileId == currentProfileId && recordsEpoch == 0) {
            setRecordsSnapshot(caches.records.orEmpty())
        }
        isLoadingLocalData = false
        prefetchBest50Covers()
    }

    private suspend fun storeCNBaseline(rawList: List<SongData>): SongSnapshot {
        val zCatalog = zetarakuCatalog
        val snapshot = withContext(Dispatchers.Default) {
            val withUtageMeta = ZetarakuCatalogConverter.enrichCNSongsWithUtageMetadata(rawList, zCatalog)
            buildSnapshotSync(withUtageMeta)
        }
        cnSongs = snapshot.songs
        cnSongsById = snapshot.songsById
        return snapshot
    }

    private fun applySnapshot(snapshot: SongSnapshot, epoch: Int? = null) {
        if (epoch != null && regionEpoch != epoch) return
        songs = snapshot.songs
        songsById = snapshot.songsById
        maxNotesStats = snapshot.maxNotes
        catalogRevision += 1
        rebuildPlateScopeCache()
    }

    private suspend fun applySongs(list: List<SongData>, epoch: Int? = null) {
        val snapshot = withContext(Dispatchers.Default) {
            buildSnapshotSync(list)
        }
        applySnapshot(snapshot, epoch = epoch)
    }

    private suspend fun applyZetarakuCatalog(
        catalog: ZetarakuCatalog,
        region: MaimaiConstants.ServerRegion,
        epoch: Int? = null
    ) {
        val cnRef = cnSongs
        val idMap = dxDataIdMap
        val (snapshot, migrations) = withContext(Dispatchers.Default) {
            val output = ZetarakuCatalogConverter.convertWithMigrations(
                catalog = catalog,
                region = region,
                cnReference = cnRef,
                dxIdMap = idMap
            )
            buildSnapshotSync(output.songs) to output.migratedIdPairs
        }
        if (epoch != null && regionEpoch != epoch) return
        migrateFavoriteIdsIfNeeded(migrations)
        applySnapshot(snapshot, epoch = epoch)
    }

    private fun migrateFavoriteIdsIfNeeded(pairs: List<ZetarakuCatalogConverter.MigratedIdPair>) {
        if (favoriteIds.isEmpty() || pairs.isEmpty()) return
        val updated = favoriteIds.toMutableSet()
        var changed = false
        for (pair in pairs) {
            if (updated.remove(pair.legacyId)) {
                updated.add(pair.resolvedId)
                changed = true
            }
        }
        if (changed) {
            favoriteIds = updated
        }
    }

    private fun applyChartStats(response: ChartStatsResponse) {
        val map = HashMap<Int, List<ChartStatEntry>>(response.charts.size)
        for ((key, value) in response.charts) {
            val id = key.toIntOrNull()
            if (id != null) {
                map[id] = value
            }
        }
        chartStats = map
    }

    private fun setRecordsSnapshot(list: List<ProberRecord>) {
        val map = HashMap<String, ProberRecord>(list.size)
        for (rec in list) {
            map["${rec.songId}_${rec.levelIndex}"] = rec
        }
        recordsByKey = map
        _records = list
    }

    // Server Region Switching & Multi-Tier Throttled Sync

    suspend fun switchServerRegion(newRegion: MaimaiConstants.ServerRegion) = withContext(Dispatchers.Main.immediate) {
        if (newRegion == serverRegion) return@withContext
        regionEpoch += 1
        val epoch = regionEpoch
        serverRegion = newRegion
        syncNotice = null

        when (newRegion) {
            MaimaiConstants.ServerRegion.CN -> {
                applySongs(cnSongs, epoch = epoch)
            }
            MaimaiConstants.ServerRegion.INTL, MaimaiConstants.ServerRegion.JP -> {
                val cached = zetarakuCatalog
                if (cached != null) {
                    applyZetarakuCatalog(cached, region = newRegion, epoch = epoch)
                    refreshZetarakuIfStale()
                } else {
                    songs = emptyList()
                    songsById = emptyMap()
                    catalogRevision += 1
                    rebuildPlateScopeCache()
                    loadOrFetchZetaraku(region = newRegion, epoch = epoch)
                }
            }
        }
        if (regionEpoch == epoch) {
            prefetchBest50Covers()
        }
    }

    private suspend fun loadOrFetchZetaraku(region: MaimaiConstants.ServerRegion, epoch: Int) {
        val (diskCatalog, diskIdMap) = withContext(Dispatchers.IO) {
            val z = if (cachedZetarakuFile.exists()) {
                runCatching {
                    MaimaiAPI.json.decodeFromString<ZetarakuCatalog>(cachedZetarakuFile.readText(Charsets.UTF_8))
                }.getOrNull()
            } else {
                null
            }
            val m = if (cachedDXDataIdMapFile.exists()) {
                runCatching {
                    MaimaiAPI.json.decodeFromString<DXDataIdMap>(cachedDXDataIdMapFile.readText(Charsets.UTF_8))
                }.getOrNull()
            } else {
                null
            }
            z to m
        }
        if (diskIdMap != null && dxDataIdMap == null) {
            dxDataIdMap = diskIdMap
        }
        if (diskCatalog != null) {
            if (regionEpoch != epoch) return
            zetarakuCatalog = diskCatalog
            applyZetarakuCatalog(diskCatalog, region = region, epoch = epoch)
            refreshZetarakuIfStale()
            return
        }
        syncZetarakuCatalog()
    }

    suspend fun syncAllCatalogsAndRules(force: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        if (isSyncingSongs) return@withContext
        val now = System.currentTimeMillis()

        if (force) {
            val lastCN = lastCNCatalogSyncEpochMs
            val lastZ = lastZetarakuSyncEpochMs
            if (lastCN != null &&
                lastZ != null &&
                now - min(lastCN, lastZ) < SyncPolicy.CATALOG_MANUAL_COOLDOWN_MS &&
                cnSongs.isNotEmpty() &&
                plateCatalog != null &&
                zetarakuCatalog != null
            ) {
                statusMessage = "当前已是最新数据"
                return@withContext
            }
        } else {
            if (lastCNCatalogSyncEpochMs == null && cnSongs.isNotEmpty() && plateCatalog != null) {
                // Record seed baseline timestamp on cold install; never burst upstream APIs on first launch.
                lastCNCatalogSyncEpochMs = now
                return@withContext
            }
            val cnFresh = lastCNCatalogSyncEpochMs?.let { now - it < SyncPolicy.CATALOG_AUTO_REFRESH_MS } ?: false
            val zFresh = (serverRegion == MaimaiConstants.ServerRegion.CN && zetarakuCatalog == null) ||
                (lastZetarakuSyncEpochMs?.let { now - it < SyncPolicy.CATALOG_AUTO_REFRESH_MS } ?: false)
            if (cnFresh && zFresh && cnSongs.isNotEmpty() && plateCatalog != null) {
                return@withContext
            }
        }

        val (cnResult, zErr) = coroutineScope {
            val cnDeferred = async { performLXNSCatalogSync() }
            val zDeferred = async { performZetarakuSync() }
            cnDeferred.await() to zDeferred.await()
        }
        val cnErr = cnResult.second

        val cnSource = cnResult.first ?: cnSongs
        val cnSnapshot = if (cnSource.isNotEmpty()) storeCNBaseline(cnSource) else null
        when (serverRegion) {
            MaimaiConstants.ServerRegion.CN -> {
                if (cnSnapshot != null) {
                    applySnapshot(cnSnapshot, epoch = regionEpoch)
                } else {
                    rebuildPlateScopeCache()
                }
            }
            MaimaiConstants.ServerRegion.INTL, MaimaiConstants.ServerRegion.JP -> {
                val catalog = zetarakuCatalog
                if (catalog != null) {
                    applyZetarakuCatalog(catalog, region = serverRegion, epoch = regionEpoch)
                } else {
                    rebuildPlateScopeCache()
                }
            }
        }

        if (force) {
            if (cnErr == null && zErr == null) {
                statusMessage = "已更新至最新数据"
                syncNotice = null
            } else {
                val err = cnErr ?: zErr
                val detail = err?.message ?: "网络请求异常"
                statusMessage = "部分数据同步失败：$detail"
                syncNotice = DataSyncNotice(
                    kind = DataSyncNotice.Kind.SONGS,
                    message = "曲库与规则同步未完全成功，正在使用本地缓存数据。\n$detail"
                )
            }
        }
    }

    private suspend fun refreshZetarakuIfStale() {
        val last = lastZetarakuSyncEpochMs
        if (last != null &&
            System.currentTimeMillis() - last < SyncPolicy.CATALOG_AUTO_REFRESH_MS &&
            zetarakuCatalog != null &&
            dxDataIdMap != null
        ) {
            return
        }
        syncZetarakuCatalog()
    }

    private suspend fun syncZetarakuCatalog() = withContext(Dispatchers.Main.immediate) {
        val err = performZetarakuSync()
        val catalog = zetarakuCatalog
        if (catalog != null && serverRegion != MaimaiConstants.ServerRegion.CN) {
            applyZetarakuCatalog(catalog, region = serverRegion, epoch = regionEpoch)
        }
        if (err != null && serverRegion != MaimaiConstants.ServerRegion.CN) {
            val detail = err.message ?: "网络请求异常"
            syncNotice = DataSyncNotice(
                kind = DataSyncNotice.Kind.SONGS,
                message = "${serverRegion.shortName}曲库获取失败，正在使用本地缓存。\n$detail"
            )
        }
    }

    private suspend fun performZetarakuSync(): Throwable? {
        if (isFetchingZetaraku) return null
        isFetchingZetaraku = true
        try {
            val (zResult, fetchedIdMap) = coroutineScope {
                val zTask = async { runCatching { MaimaiAPI.fetchZetarakuCatalog() } }
                val idTask = async { runCatching { MaimaiAPI.fetchDXDataIdMap() }.getOrNull() }
                zTask.await() to idTask.await()
            }

            if (fetchedIdMap != null) {
                dxDataIdMap = fetchedIdMap
                withContext(Dispatchers.IO) {
                    runCatching {
                        val bytes = MaimaiAPI.json.encodeToString(fetchedIdMap).toByteArray(Charsets.UTF_8)
                        writeBytesAtomically(cachedDXDataIdMapFile, bytes)
                    }
                }
            }

            return zResult.fold(
                onSuccess = { (rawBytes, decoded) ->
                    val persisted = withContext(Dispatchers.IO) {
                        runCatching { writeBytesAtomically(cachedZetarakuFile, rawBytes) }.isSuccess
                    }
                    zetarakuCatalog = decoded
                    if (persisted) {
                        lastZetarakuSyncEpochMs = System.currentTimeMillis()
                    }
                    null
                },
                onFailure = { err ->
                    if (err is CancellationException) throw err
                    err
                }
            )
        } finally {
            isFetchingZetaraku = false
        }
    }

    private suspend fun performLXNSCatalogSync(): Pair<List<SongData>?, Throwable?> {
        if (isFetchingCNSongs) return null to null
        isFetchingCNSongs = true
        try {
            val songResp = MaimaiAPI.fetchLXNSSongCatalog()
            val aliasResp = runCatching { MaimaiAPI.fetchLXNSAliases() }.getOrNull()
            val plateResp = runCatching { MaimaiAPI.fetchLXNSPlates() }.getOrNull()

            val baselineSnapshot = cnSongs
            val (convertedSongs, convertedPlates) = withContext(Dispatchers.Default) {
                val catalogResult = LXNSCatalogConverter.convertCatalog(
                    songResponse = songResp,
                    aliasResponse = aliasResp,
                    existingBaseline = baselineSnapshot
                )
                val platesResult = plateResp?.let { LXNSCatalogConverter.extractPlateCatalog(it) }
                catalogResult.songs to platesResult
            }

            withContext(Dispatchers.IO) {
                val encodedSongs = MaimaiAPI.json.encodeToString(convertedSongs).toByteArray(Charsets.UTF_8)
                writeBytesAtomically(cachedSongsFile, encodedSongs)
                if (convertedPlates != null) {
                    val encodedPlates = MaimaiAPI.json.encodeToString(convertedPlates).toByteArray(Charsets.UTF_8)
                    writeBytesAtomically(cachedPlatesFile, encodedPlates)
                }
            }

            if (convertedPlates != null) {
                plateCatalog = convertedPlates
            }
            lastCNCatalogSyncEpochMs = System.currentTimeMillis()
            return convertedSongs to null
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            return null to err
        } finally {
            isFetchingCNSongs = false
        }
    }

    suspend fun refreshChartStatsIfStale(force: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        if (isSyncingStats) return@withContext
        val now = System.currentTimeMillis()
        if (force) {
            val last = lastChartStatsSyncEpochMs
            if (last != null && now - last < SyncPolicy.STATS_MANUAL_COOLDOWN_MS && chartStats.isNotEmpty()) {
                statusMessage = "当前已是最新数据"
                return@withContext
            }
        } else {
            val last = lastChartStatsSyncEpochMs
            if (last != null && now - last < SyncPolicy.STATS_AUTO_REFRESH_MS && chartStats.isNotEmpty()) {
                return@withContext
            }
        }

        isSyncingStats = true
        try {
            val (rawBytes, decoded) = MaimaiAPI.fetchChartStats()
            withContext(Dispatchers.IO) {
                writeBytesAtomically(cachedStatsFile, rawBytes)
            }
            lastChartStatsSyncEpochMs = System.currentTimeMillis()
            applyChartStats(decoded)
            if (force) {
                statusMessage = "拟合定数已刷新"
                syncNotice = null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            val detail = err.message ?: "网络请求异常"
            statusMessage = if (force) "拟合定数获取失败：$detail" else null
            syncNotice = DataSyncNotice(
                kind = DataSyncNotice.Kind.STATS,
                message = "拟合定数获取失败，正在使用本地数据。可前往「设置与数据」重试。"
            )
        } finally {
            isSyncingStats = false
        }
    }

    // Multi-Provider Score Profile Management & OAuth 2.0 + PKCE

    fun onOAuthCallbackIntent(uriString: String) {
        _pendingOAuthCallbacks.trySend(uriString)
    }

    fun cancelPendingLXNSOAuth() {
        pendingPKCESession = null
    }

    fun beginLXNSOAuth(): String {
        if (isSyncingRecords) throw MaimaiAPIException.SyncInProgress()
        val verifier = PKCEHelper.generateCodeVerifier()
        val state = PKCEHelper.generateState()
        val nonce = PKCEHelper.generateState()
        val challenge = PKCEHelper.codeChallengeS256(verifier)
        pendingPKCESession = PendingPKCESession(codeVerifier = verifier, state = state)
        return MaimaiAPI.buildLXNSAuthorizeUrl(
            codeChallenge = challenge,
            state = state,
            nonce = nonce
        )
    }

    private suspend fun <T> runDetachedRecordSync(block: suspend () -> T): T =
        withContext(Dispatchers.Main.immediate) {
            if (isSyncingRecords) throw MaimaiAPIException.SyncInProgress()
            isSyncingRecords = true
            storeScope.async {
                try {
                    block()
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        isSyncingRecords = false
                    }
                }
            }.await()
        }

    suspend fun completeLXNSOAuth(callbackUriString: String): ScoreProfile = withContext(Dispatchers.Main.immediate) {
        val session = pendingPKCESession ?: throw MaimaiAPIException.OAuthStateMismatch()
        pendingPKCESession = null
        val code = MaimaiAPI.parseOAuthCallback(callbackUriString, expectedState = session.state)

        runDetachedRecordSync {
            val tokenResp = MaimaiAPI.exchangeLXNSCode(code = code, codeVerifier = session.codeVerifier)
            val expiresAtSec = System.currentTimeMillis() / 1000L + tokenResp.expiresIn
            val userInfo = runCatching { MaimaiAPI.fetchLXNSUserInfo(tokenResp.accessToken) }.getOrNull()

            val accountKey = userInfo?.sub?.takeIf { it.isNotEmpty() }?.let { "oidc_$it" }
                ?: "lxns_oauth_${UUID.randomUUID().toString().lowercase(Locale.ROOT)}"
            val lxUsername = userInfo?.displayUsername.orEmpty()

            val initialProfile = upsertProfile(
                provider = ScoreProvider.LXNS,
                authMethod = ScoreAuthMethod.LXNS_OAUTH,
                accountKey = accountKey,
                username = lxUsername,
                nickname = null
            )
            withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.saveSecret(
                    appContext,
                    initialProfile.id,
                    ScoreProfileSecret(
                        accessToken = tokenResp.accessToken,
                        refreshToken = tokenResp.refreshToken.orEmpty(),
                        accessTokenExpiresAtEpochSec = expiresAtSec
                    )
                )
            }
            setActiveProfileInternal(initialProfile.id, restoreFromCache = false)

            try {
                val playerAndScores = MaimaiAPI.fetchLXNSOAuthPlayerAndScores(tokenResp.accessToken)
                val lookup = mergedSongLookupForScoreImport()
                val converted = withContext(Dispatchers.Default) {
                    LXNSCatalogConverter.convertScores(playerAndScores.scores, lookup)
                }
                val syncedAt = System.currentTimeMillis()
                updateProfileMetadata(
                    profileId = initialProfile.id,
                    username = lxUsername,
                    nickname = playerAndScores.player?.name ?: lxUsername,
                    syncedAtEpochMs = syncedAt
                )
                installFetchedRecords(converted, initialProfile.id)
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                throw MaimaiAPIException.RecordSyncFailedAfterLogin(err.message ?: "成绩拉取失败")
            }

            profiles.first { it.id == initialProfile.id }
        }
    }

    suspend fun addLXNSPersonalTokenProfile(
        rawToken: String,
        remarkName: String = ""
    ): ScoreProfile = runDetachedRecordSync {
        val token = rawToken.trim()
        val playerAndScores = MaimaiAPI.fetchLXNSPersonalTokenPlayerAndScores(token)
        val trimmedRemark = remarkName.trim()
        val accountKey = playerAndScores.player?.friendCode?.let { "fc_$it" }
            ?: "lxns_pt_${token.sha256Hex().take(16)}"
        val nickname = playerAndScores.player?.name?.takeIf { it.isNotEmpty() } ?: trimmedRemark

        val lookup = mergedSongLookupForScoreImport()
        val converted = withContext(Dispatchers.Default) {
            LXNSCatalogConverter.convertScores(playerAndScores.scores, lookup)
        }

        val profile = upsertProfile(
            provider = ScoreProvider.LXNS,
            authMethod = ScoreAuthMethod.LXNS_PERSONAL_TOKEN,
            accountKey = accountKey,
            username = trimmedRemark,
            nickname = nickname,
            syncedAtEpochMs = System.currentTimeMillis()
        )
        withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveSecret(
                appContext,
                profile.id,
                ScoreProfileSecret(personalToken = token)
            )
        }
        installFetchedRecords(converted, profile.id)
        profile
    }

    suspend fun addDivingFishPasswordProfile(
        username: String,
        password: String
    ): ScoreProfile = runDetachedRecordSync {
        val trimmedUser = username.trim()
        val cookie = MaimaiAPI.loginDivingFish(trimmedUser, password)
        val profile = upsertProfile(
            provider = ScoreProvider.DIVING_FISH,
            authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD,
            accountKey = trimmedUser,
            username = trimmedUser,
            nickname = null
        )
        withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveSecret(
                appContext,
                profile.id,
                ScoreProfileSecret(password = password, cookie = cookie)
            )
        }
        setActiveProfileInternal(profile.id, restoreFromCache = false)

        try {
            performSyncRecords(profile.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            throw MaimaiAPIException.RecordSyncFailedAfterLogin(err.message ?: "成绩拉取失败")
        }

        profiles.first { it.id == profile.id }
    }

    suspend fun addDivingFishImportTokenProfile(
        rawToken: String,
        remarkName: String = ""
    ): ScoreProfile = runDetachedRecordSync {
        val token = rawToken.trim()
        val resp = MaimaiAPI.fetchDivingFishRecordsByImportToken(token)
        val trimmedRemark = remarkName.trim()
        val resolvedUsername = resp.username?.takeIf { it.isNotEmpty() } ?: trimmedRemark
        val accountKey = resp.username?.takeIf { it.isNotEmpty() }
            ?: "df_token_${token.sha256Hex().take(16)}"
        val resolvedNickname = resp.nickname?.takeIf { it.isNotEmpty() } ?: trimmedRemark

        val profile = upsertProfile(
            provider = ScoreProvider.DIVING_FISH,
            authMethod = ScoreAuthMethod.DIVING_FISH_IMPORT_TOKEN,
            accountKey = accountKey,
            username = resolvedUsername,
            nickname = resolvedNickname,
            syncedAtEpochMs = System.currentTimeMillis()
        )
        withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveSecret(
                appContext,
                profile.id,
                ScoreProfileSecret(importToken = token)
            )
        }
        installFetchedRecords(resp.records.orEmpty(), profile.id)
        profile
    }

    fun switchProfile(profileId: String) {
        if (profiles.none { it.id == profileId } || activeProfileId == profileId) return
        setActiveProfileInternal(profileId, restoreFromCache = true)
    }

    fun removeProfile(profileId: String) {
        val wasActive = activeProfileId == profileId
        val updated = profiles.filterNot { it.id == profileId }
        profiles = updated
        tokenRefreshMutexes.remove(profileId)
        storeScope.launch {
            persistProfiles(updated)
            withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.deleteSecret(appContext, profileId)
                val recordFile = AccountCredentialStore.recordsFile(appFilesDir, profileId)
                if (recordFile.exists()) {
                    recordFile.delete()
                }
            }
        }

        if (wasActive) {
            val nextId = updated.firstOrNull()?.id
            if (nextId != null) {
                setActiveProfileInternal(nextId, restoreFromCache = true)
            } else {
                activeProfileId = null
                prefs.edit().remove(PreferenceKeys.activeProfileId).apply()
                recordsEpoch += 1
                setRecordsSnapshot(emptyList())
            }
        }
    }

    suspend fun syncActiveProfileRecords() = withContext(Dispatchers.Main.immediate) {
        if (isSyncingRecords) return@withContext
        val profile = activeProfile ?: throw MaimaiAPIException.Unauthorized("请先添加查分器成绩档案")
        runDetachedRecordSync {
            performSyncRecords(profile.id)
        }
    }

    private suspend fun performSyncRecords(profileId: String) {
        val profile = profiles.firstOrNull { it.id == profileId } ?: return
        val secret = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadSecret(appContext, profileId)
        }

        when (profile.authMethod) {
            ScoreAuthMethod.DIVING_FISH_PASSWORD -> {
                val username = profile.username
                val password = secret.password
                var cookie = secret.cookie
                if (cookie.isEmpty()) {
                    if (username.isEmpty() || password.isEmpty()) {
                        throw MaimaiAPIException.Unauthorized("水鱼登录已过期，请重新登录")
                    }
                    cookie = MaimaiAPI.loginDivingFish(username, password)
                }

                val (resp, usedCookie) = try {
                    MaimaiAPI.fetchDivingFishRecordsByCookie(cookie) to cookie
                } catch (err: Throwable) {
                    if (MaimaiAPI.shouldRetryLogin(err) && username.isNotEmpty() && password.isNotEmpty()) {
                        val freshCookie = MaimaiAPI.loginDivingFish(username, password)
                        MaimaiAPI.fetchDivingFishRecordsByCookie(freshCookie) to freshCookie
                    } else {
                        throw err
                    }
                }

                if (usedCookie != secret.cookie) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        AccountCredentialStore.saveSecret(
                            appContext,
                            profileId,
                            secret.copy(cookie = usedCookie)
                        )
                    }
                }
                updateProfileMetadata(
                    profileId = profileId,
                    username = resp.username ?: profile.username,
                    nickname = resp.nickname,
                    syncedAtEpochMs = System.currentTimeMillis()
                )
                installFetchedRecords(resp.records.orEmpty(), profileId)
            }

            ScoreAuthMethod.DIVING_FISH_IMPORT_TOKEN -> {
                val token = secret.importToken
                if (token.isEmpty()) {
                    throw MaimaiAPIException.Unauthorized("未找到保存的水鱼导入 Token，请重新添加档案")
                }
                val resp = MaimaiAPI.fetchDivingFishRecordsByImportToken(token)
                updateProfileMetadata(
                    profileId = profileId,
                    username = resp.username ?: profile.username,
                    nickname = resp.nickname,
                    syncedAtEpochMs = System.currentTimeMillis()
                )
                installFetchedRecords(resp.records.orEmpty(), profileId)
            }

            ScoreAuthMethod.LXNS_OAUTH -> {
                val initialToken = validLXNSAccessToken(profileId, forceRefresh = false)
                val playerAndScores: MaimaiAPI.LXNSPlayerAndScores = try {
                    MaimaiAPI.fetchLXNSOAuthPlayerAndScores(initialToken)
                } catch (_: MaimaiAPIException.Unauthorized) {
                    val refreshedToken = validLXNSAccessToken(
                        profileId = profileId,
                        forceRefresh = true,
                        failedAccessToken = initialToken
                    )
                    MaimaiAPI.fetchLXNSOAuthPlayerAndScores(refreshedToken)
                }

                val lookup = mergedSongLookupForScoreImport()
                val converted = withContext(Dispatchers.Default) {
                    LXNSCatalogConverter.convertScores(playerAndScores.scores, lookup)
                }
                updateProfileMetadata(
                    profileId = profileId,
                    username = profile.username,
                    nickname = playerAndScores.player?.name,
                    syncedAtEpochMs = System.currentTimeMillis()
                )
                installFetchedRecords(converted, profileId)
            }

            ScoreAuthMethod.LXNS_PERSONAL_TOKEN -> {
                val token = secret.personalToken
                if (token.isEmpty()) {
                    throw MaimaiAPIException.Unauthorized("未找到保存的落雪个人密钥，请重新添加档案")
                }
                val (player: LXNSPlayer?, lxScores: List<LXNSScore>) =
                    MaimaiAPI.fetchLXNSPersonalTokenPlayerAndScores(token)
                val lookup = mergedSongLookupForScoreImport()
                val converted = withContext(Dispatchers.Default) {
                    LXNSCatalogConverter.convertScores(lxScores, lookup)
                }
                updateProfileMetadata(
                    profileId = profileId,
                    username = profile.username,
                    nickname = player?.name,
                    syncedAtEpochMs = System.currentTimeMillis()
                )
                installFetchedRecords(converted, profileId)
            }
        }
    }

    /**
     * Single-flight per-profile `Mutex` serialization for LXNS OAuth `refresh_token` rotation.
     * Immediately persists rotated `refresh_token` under `NonCancellable` before releasing the lock.
     */
    private suspend fun validLXNSAccessToken(
        profileId: String,
        forceRefresh: Boolean,
        failedAccessToken: String? = null
    ): String {
        val mutex = tokenRefreshMutexes.getOrPut(profileId) { Mutex() }
        return mutex.withLock {
            val currentSecret = withContext(Dispatchers.IO) {
                AccountCredentialStore.loadSecret(appContext, profileId)
            }
            val nowSec = System.currentTimeMillis() / 1000L
            val alreadyRotatedSinceFailure = failedAccessToken != null &&
                currentSecret.accessToken.isNotEmpty() &&
                currentSecret.accessToken != failedAccessToken

            if ((!forceRefresh || alreadyRotatedSinceFailure) &&
                currentSecret.accessToken.isNotEmpty() &&
                currentSecret.accessTokenExpiresAtEpochSec != null &&
                nowSec < currentSecret.accessTokenExpiresAtEpochSec - SyncPolicy.OAUTH_EXPIRY_BUFFER_SEC
            ) {
                return@withLock currentSecret.accessToken
            }

            if (currentSecret.refreshToken.isEmpty()) {
                throw MaimaiAPIException.Unauthorized("落雪授权已过期，请重新授权登录")
            }

            val refreshed = MaimaiAPI.refreshLXNSToken(currentSecret.refreshToken)
            val stillExists = withContext(Dispatchers.Main.immediate) {
                profiles.any { it.id == profileId }
            }
            if (!stillExists) {
                throw MaimaiAPIException.Unauthorized("该成绩档案已被移除")
            }
            val rotatedAtSec = System.currentTimeMillis() / 1000L
            val updatedSecret = currentSecret.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken?.takeIf { it.isNotEmpty() } ?: currentSecret.refreshToken,
                accessTokenExpiresAtEpochSec = rotatedAtSec + refreshed.expiresIn
            )
            withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.saveSecret(appContext, profileId, updatedSecret)
            }
            refreshed.accessToken
        }
    }

    private suspend fun persistProfiles(snapshot: List<ScoreProfile>) {
        profilePersistMutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.saveProfiles(appContext, snapshot)
            }
        }
    }

    private fun mergedSongLookupForScoreImport(): Map<Int, SongData> {
        if (cnSongsById.isEmpty()) return songsById
        val merged = HashMap<Int, SongData>(songsById.size + cnSongsById.size)
        merged.putAll(songsById)
        merged.putAll(cnSongsById)
        return merged
    }

    private suspend fun upsertProfile(
        provider: ScoreProvider,
        authMethod: ScoreAuthMethod,
        accountKey: String,
        username: String,
        nickname: String?,
        syncedAtEpochMs: Long? = null
    ): ScoreProfile {
        val list = profiles.toMutableList()
        val existingIdx = list.indexOfFirst { it.provider == provider && it.accountKey == accountKey }
        val target = if (existingIdx >= 0) {
            val cur = list[existingIdx]
            val updated = cur.copy(
                authMethod = authMethod,
                username = username.ifEmpty { cur.username },
                nickname = if (!nickname.isNullOrEmpty()) nickname else cur.nickname,
                lastSyncedAtEpochMs = syncedAtEpochMs ?: cur.lastSyncedAtEpochMs
            )
            list[existingIdx] = updated
            updated
        } else {
            val created = ScoreProfile(
                id = UUID.randomUUID().toString().lowercase(Locale.ROOT),
                provider = provider,
                authMethod = authMethod,
                accountKey = accountKey,
                username = username,
                nickname = nickname.orEmpty(),
                lastSyncedAtEpochMs = syncedAtEpochMs
            )
            list += created
            created
        }
        profiles = list
        persistProfiles(list)
        return target
    }

    private suspend fun updateProfileMetadata(
        profileId: String,
        username: String?,
        nickname: String?,
        syncedAtEpochMs: Long
    ) {
        val idx = profiles.indexOfFirst { it.id == profileId }
        if (idx < 0) return
        val list = profiles.toMutableList()
        val cur = list[idx]
        list[idx] = cur.copy(
            username = if (!username.isNullOrEmpty()) username else cur.username,
            nickname = if (!nickname.isNullOrEmpty()) nickname else cur.nickname,
            lastSyncedAtEpochMs = syncedAtEpochMs
        )
        profiles = list
        persistProfiles(list)
    }

    private suspend fun installFetchedRecords(fetched: List<ProberRecord>, profileId: String) {
        val file = AccountCredentialStore.recordsFile(appFilesDir, profileId)
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching {
                val bytes = MaimaiAPI.json.encodeToString(fetched).toByteArray(Charsets.UTF_8)
                writeBytesAtomically(file, bytes)
            }
        }
        if (activeProfileId != profileId) {
            setActiveProfileInternal(profileId, restoreFromCache = false)
        }
        recordsEpoch += 1
        setRecordsSnapshot(fetched)
        prefetchBest50Covers()
    }

    private fun setActiveProfileInternal(profileId: String?, restoreFromCache: Boolean) {
        activeProfileId = profileId
        prefs.edit().putString(PreferenceKeys.activeProfileId, profileId).apply()
        if (restoreFromCache) {
            restoreCachedRecords()
        }
    }

    private fun restoreCachedRecords() {
        val targetProfileId = activeProfileId
        recordsEpoch += 1
        val epoch = recordsEpoch
        setRecordsSnapshot(emptyList())
        if (targetProfileId == null) return

        val file = AccountCredentialStore.recordsFile(appFilesDir, targetProfileId)
        storeScope.launch {
            val cached = withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                runCatching {
                    MaimaiAPI.json.decodeFromString<List<ProberRecord>>(file.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            if (activeProfileId != targetProfileId || recordsEpoch != epoch) return@launch
            setRecordsSnapshot(cached.orEmpty())
            prefetchBest50Covers()
        }
    }

    private fun prefetchBest50Covers() {
        val summary = best35AndBest15()
        val b50Songs = (summary.b35 + summary.b15).mapNotNull { songsById[it.songId] }
        if (b50Songs.isEmpty()) return
        storeScope.launch(Dispatchers.IO) {
            for (song in b50Songs) {
                CoverImageManager.loadCoverBitmap(appContext, song, targetPx = 200)
            }
        }
    }

    // Search, Filter & Favorites

    fun isFavorite(song: SongData): Boolean = song.id in favoriteIds

    fun toggleFavorite(song: SongData) {
        favoriteIds = if (song.id in favoriteIds) {
            favoriteIds - song.id
        } else {
            favoriteIds + song.id
        }
    }

    fun addSearchHistory(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        val next = buildList {
            add(trimmed)
            for (item in searchHistory) {
                if (item != trimmed && size < 20) {
                    add(item)
                }
            }
        }
        searchHistory = next
    }

    fun clearSearchHistory() {
        searchHistory = emptyList()
    }

    fun filteredSongs(criteria: SongFilterCriteria): List<SongData> =
        SongFilter.apply(
            songs = songs,
            criteria = criteria,
            region = serverRegion,
            favoriteIds = favoriteIds,
            enableAliasSearch = enableAliasSearch,
            enableCharterSearch = enableCharterSearch
        )

    // Rating, Records & Counterpart Queries

    fun record(songId: Int, difficultyIndex: Int): ProberRecord? {
        if (songId <= 0) return null
        return recordsByKey["${songId}_$difficultyIndex"]
    }

    fun fitDiff(songId: Int, chartIndex: Int): Double? {
        if (songId <= 0) return null
        return chartStats[songId]?.getOrNull(chartIndex)?.fitDiff
    }

    fun counterpartSong(song: SongData): SongData? {
        if (song.isUtage) return null
        if (song.numericId > 0) {
            val targetId = if (song.numericId < 10_000) song.numericId + 10_000 else song.numericId - 10_000
            val candidate = songsById[targetId]
            if (candidate != null &&
                !candidate.isUtage &&
                candidate.title == song.title &&
                candidate.isDX != song.isDX
            ) {
                return candidate
            }
        }
        return songs.firstOrNull { candidate ->
            !candidate.isUtage &&
                candidate.isDX != song.isDX &&
                candidate.title == song.title &&
                candidate.basicInfo.genre == song.basicInfo.genre
        }
    }

    /**
     * Baseline single-song rating a chart must exceed to increase total B50 rating.
     * Enabled across ALL 3 regions (`CN`, `INTL`, `JP`) whenever [isLoggedIn] is true.
     */
    fun b50BaselineRating(
        song: SongData,
        difficultyIndex: Int,
        summary: Best50Summary? = null
    ): Int? {
        if (!isLoggedIn || song.isUtage || song.isDisabled == true) return null
        val resolved = summary ?: best35AndBest15()
        val bucket = if (song.basicInfo.isNew) resolved.b15 else resolved.b35
        val limit = if (song.basicInfo.isNew) 15 else 35
        if (song.numericId > 0) {
            val existing = bucket.firstOrNull {
                it.songId == song.numericId && it.levelIndex == difficultyIndex
            }
            if (existing != null) {
                return existing.computedRating
            }
        }
        if (bucket.size >= limit) {
            return bucket.lastOrNull()?.computedRating ?: 0
        }
        return 0
    }

    fun sortedRecords(isNewVersion: Boolean): List<ProberRecord> {
        val region = serverRegion
        return records.mapNotNull { rec ->
            val song = songsById[rec.songId] ?: return@mapNotNull null
            if (song.isUtage ||
                song.isDisabled == true ||
                rec.levelIndex >= song.ds.size ||
                song.basicInfo.isNew != isNewVersion
            ) {
                return@mapNotNull null
            }
            rec.projected(onto = song, region = region)
        }.sortedWith(recordComparator)
    }

    fun best35AndBest15(): Best50Summary {
        val region = serverRegion
        var matching = true
        val oldList = ArrayList<ProberRecord>()
        val newList = ArrayList<ProberRecord>()

        for (rec in records) {
            val song = songsById[rec.songId]
            if (song == null) {
                if (region == MaimaiConstants.ServerRegion.CN || !cnSongsById.containsKey(rec.songId)) {
                    matching = false
                }
                continue
            }
            if (song.isUtage || song.isDisabled == true || rec.levelIndex >= song.ds.size) continue
            val projected = rec.projected(onto = song, region = region)
            if (song.basicInfo.isNew) {
                newList += projected
            } else {
                oldList += projected
            }
        }

        oldList.sortWith(recordComparator)
        newList.sortWith(recordComparator)
        return Best50Summary(
            b35 = oldList.take(35),
            b15 = newList.take(15),
            isMatching = matching
        )
    }

    // Internal Utilities

    private fun readOptionalEpochMs(key: String): Long? {
        if (!prefs.contains(key)) return null
        val raw = runCatching { prefs.getLong(key, -1L) }.getOrDefault(-1L)
        if (raw <= 0L) return null
        return if (raw < 100_000_000_000L) raw * 1000L else raw
    }

    private fun writeOptionalEpochMs(key: String, value: Long?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putLong(key, value)
        }
        editor.apply()
    }

    companion object {
        private val SYNC_TIME_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.getDefault())

        private val recordComparator = Comparator<ProberRecord> { lhs, rhs ->
            val raCmp = rhs.computedRating.compareTo(lhs.computedRating)
            if (raCmp != 0) return@Comparator raCmp
            val achiCmp = rhs.achievements.compareTo(lhs.achievements)
            if (achiCmp != 0) return@Comparator achiCmp
            rhs.ds.compareTo(lhs.ds)
        }

        private fun buildSnapshotSync(list: List<SongData>): SongSnapshot {
            val enriched = SongData.mergingCounterpartAliases(list)
            val ordered = enriched.sortedWith { a, b ->
                val keyCmp = b.defaultSortKey.compareTo(a.defaultSortKey)
                if (keyCmp != 0) keyCmp else b.id.compareTo(a.id)
            }
            val map = HashMap<Int, SongData>(ordered.size)
            for (song in ordered) {
                if (song.numericId > 0) {
                    map[song.numericId] = song
                }
            }
            return SongSnapshot(
                songs = ordered,
                songsById = map,
                maxNotes = MaxNotesStats.compute(ordered)
            )
        }

        private fun writeBytesAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        }

        private fun formatSyncTimestamp(epochMs: Long): String =
            Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(SYNC_TIME_FORMATTER)
    }
}
