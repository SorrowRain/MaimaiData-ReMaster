package dev.sorrowrain.maimaidata.services

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.sorrowrain.maimaidata.BuildConfig
import dev.sorrowrain.maimaidata.components.CheckDisplayMode
import dev.sorrowrain.maimaidata.models.AppReleaseInfo
import dev.sorrowrain.maimaidata.models.CNPlateCatalog
import dev.sorrowrain.maimaidata.models.CNPlateRuleGroup
import dev.sorrowrain.maimaidata.models.ChartStatEntry
import dev.sorrowrain.maimaidata.models.ChartStatsResponse
import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.DivingFishBinding
import dev.sorrowrain.maimaidata.models.DivingFishSecret
import dev.sorrowrain.maimaidata.models.LXNSBinding
import dev.sorrowrain.maimaidata.models.LXNSConfiguration
import dev.sorrowrain.maimaidata.models.LXNSScore
import dev.sorrowrain.maimaidata.models.LXNSSecret
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.MaxNotesStats
import dev.sorrowrain.maimaidata.models.PlateCheckScope
import dev.sorrowrain.maimaidata.models.PlayerIdentity
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.ProberRecordsResponse
import dev.sorrowrain.maimaidata.models.ResolvedChart
import dev.sorrowrain.maimaidata.models.ScoreAuthMethod
import dev.sorrowrain.maimaidata.models.SelfHostedBinding
import dev.sorrowrain.maimaidata.models.SelfHostedEndpointConfig
import dev.sorrowrain.maimaidata.models.SelfHostedSecret
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import dev.sorrowrain.maimaidata.models.ActiveSyncTask
import dev.sorrowrain.maimaidata.models.SyncChannelKind
import dev.sorrowrain.maimaidata.models.SyncHistoryEntry
import dev.sorrowrain.maimaidata.models.SyncMode
import dev.sorrowrain.maimaidata.models.SyncOperationKind
import dev.sorrowrain.maimaidata.models.SyncOutcome
import dev.sorrowrain.maimaidata.models.SyncPlan
import dev.sorrowrain.maimaidata.models.WritebackChannelStatus
import dev.sorrowrain.maimaidata.models.WritebackSummary
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
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

val LocalDataStore = staticCompositionLocalOf<DataStore> {
    error("LocalDataStore has not been provided")
}

data class LevelCheckChartItem(
    val song: SongData,
    val chart: ResolvedChart
) {
    val id: String get() = "${song.id}_${chart.index}"
}

data class LevelCheckDSBucket(
    val ds: Double,
    val items: List<LevelCheckChartItem>
) {
    val id: Int get() = (ds * 10.0).roundToInt()
}

data class Best50Summary(
    val b35: List<ProberRecord>,
    val b15: List<ProberRecord>
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
        const val UNKNOWN_SONG_CATALOG_COOLDOWN_MS = 12L * 3600L * 1000L
        const val STATS_AUTO_REFRESH_MS = 5L * 24L * 3600L * 1000L
        const val STATS_MANUAL_COOLDOWN_MS = 5L * 60L * 1000L
        const val APP_UPDATE_CHECK_INTERVAL_MS = 2L * 3600L * 1000L
        const val BOOTSTRAP_DELAY_MS = 3_000L
        const val OAUTH_EXPIRY_BUFFER_SEC = 30L
    }

    // Domain Catalog & Score State
    var songs: List<SongData> by mutableStateOf(emptyList())
        private set
    var songsById: Map<Int, SongData> by mutableStateOf(emptyMap())
        private set
    var levelCheckBuckets: Map<String, List<LevelCheckDSBucket>> by mutableStateOf(emptyMap())
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
    private var recordsOwnerIdentityId: String? = null
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
    var activeSyncTask: ActiveSyncTask? by mutableStateOf(null)
        private set
    val isSyncingRecords: Boolean
        get() = activeSyncTask != null
    var isLoadingLocalData: Boolean by mutableStateOf(true)
        private set
    var isRestoringCachedRecords: Boolean by mutableStateOf(false)
        private set

    fun isSyncing(identityId: String, operation: SyncOperationKind): Boolean =
        activeSyncTask?.identityId == identityId.lowercase(Locale.ROOT) &&
            activeSyncTask?.operation == operation

    var catalogSyncMessage: String? by mutableStateOf(null)
    var catalogSyncToast: String? by mutableStateOf(null)
    var appUpdateMessage: String? by mutableStateOf(null)
    var appUpdateToast: String? by mutableStateOf(null)
    var syncNotice: DataSyncNotice? by mutableStateOf(null)
    var availableAppUpdate: AppReleaseInfo? by mutableStateOf(null)
    private var pendingAppUpdate: AppReleaseInfo? = null
    private var appUpdateBlockerCount: Int = 0
    var isCheckingAppUpdate: Boolean by mutableStateOf(false)
        private set

    // OAuth Deep-Link Callback Stream & Pending PKCE Session
    private data class PendingPKCESession(
        val codeVerifier: String,
        val state: String,
        val targetIdentityId: String? = null,
        val createdAtEpochMs: Long = System.currentTimeMillis()
    )

    @Volatile
    private var pendingPKCESession: PendingPKCESession? = null

    private val _pendingOAuthCallbacks = Channel<String>(capacity = Channel.BUFFERED)
    val pendingOAuthCallbacks: Flow<String> = _pendingOAuthCallbacks.receiveAsFlow()

    private fun clearPendingLXNSOAuthSessionSync() {
        pendingPKCESession = null
        AccountCredentialStore.clearPendingOAuthSession(appContext)
    }

    private suspend fun loadActivePendingOAuthSession(): PendingPKCESession? {
        val now = System.currentTimeMillis()
        val ttlMs = 15L * 60L * 1000L
        val mem = pendingPKCESession
        if (mem != null) {
            if (now - mem.createdAtEpochMs <= ttlMs) {
                return mem
            }
            withContext(Dispatchers.IO) { clearPendingLXNSOAuthSessionSync() }
            return null
        }
        val persisted = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadPendingOAuthSession(appContext)
        } ?: return null
        if (now - persisted.createdAtEpochMs <= ttlMs) {
            val restored = PendingPKCESession(
                codeVerifier = persisted.codeVerifier,
                state = persisted.state,
                targetIdentityId = persisted.targetIdentityId,
                createdAtEpochMs = persisted.createdAtEpochMs
            )
            pendingPKCESession = restored
            return restored
        }
        withContext(Dispatchers.IO) { clearPendingLXNSOAuthSessionSync() }
        return null
    }

    // Per-identity single-flight mutex for LXNS refresh_token rotation & identity list write serialization
    private val tokenRefreshMutexes = ConcurrentHashMap<String, Mutex>()
    private val identityPersistMutex = Mutex()
    private val syncHistoryStore = SyncHistoryStore(appFilesDir)
    private var divingFishMusicTitles: Map<Int, String> = emptyMap()

    private var lastUnknownSongCatalogRefreshMs: Long?
        get() = readOptionalEpochMs(PreferenceKeys.unknownSongCatalogRefresh)
        set(value) = writeOptionalEpochMs(PreferenceKeys.unknownSongCatalogRefresh, value)

    // Persisted Preferences & Multi-Channel Player Identities
    var credentialStoreLoadFailed: Boolean by mutableStateOf(false)
        private set

    var selfHostedConfig: SelfHostedEndpointConfig by mutableStateOf(SelfHostedEndpointConfig())
        private set

    val isCNDirectAvailable: Boolean
        get() = selfHostedConfig.isConfigured

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

    var identities: List<PlayerIdentity> by mutableStateOf(emptyList())
        private set

    var activeIdentityId: String? by mutableStateOf(null)
        private set

    val activeIdentity: PlayerIdentity?
        get() {
            val id = activeIdentityId ?: return null
            return identities.firstOrNull { it.id == id }
        }

    val isLoggedIn: Boolean
        get() = activeIdentity != null

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

    private var _lastVersionCheckScopeId: String? by mutableStateOf(null)
    var lastVersionCheckScopeId: String?
        get() = _lastVersionCheckScopeId
        set(value) {
            if (_lastVersionCheckScopeId != value) {
                _lastVersionCheckScopeId = value
                writeOptionalString(PreferenceKeys.lastVersionCheckScopeId, value)
            }
        }

    private var _lastVersionCheckIntlScopeId: String? by mutableStateOf(null)
    var lastVersionCheckIntlScopeId: String?
        get() = _lastVersionCheckIntlScopeId
        set(value) {
            if (_lastVersionCheckIntlScopeId != value) {
                _lastVersionCheckIntlScopeId = value
                writeOptionalString(PreferenceKeys.lastVersionCheckIntlScopeId, value)
            }
        }

    private var _lastVersionCheckJpScopeId: String? by mutableStateOf(null)
    var lastVersionCheckJpScopeId: String?
        get() = _lastVersionCheckJpScopeId
        set(value) {
            if (_lastVersionCheckJpScopeId != value) {
                _lastVersionCheckJpScopeId = value
                writeOptionalString(PreferenceKeys.lastVersionCheckJpScopeId, value)
            }
        }

    // In-memory session state across navigation push/pop and bottom tab switches
    var checklistDisplayMode: CheckDisplayMode by mutableStateOf(CheckDisplayMode.SSS)
    var checklistOnlyUnfinished: Boolean by mutableStateOf(false)
    var versionCheckDifficulty: DifficultyType by mutableStateOf(DifficultyType.MASTER)

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

    var activeVersionCheckScopeId: String?
        get() = when (serverRegion) {
            MaimaiConstants.ServerRegion.CN -> lastVersionCheckScopeId
            MaimaiConstants.ServerRegion.INTL -> lastVersionCheckIntlScopeId
            MaimaiConstants.ServerRegion.JP -> lastVersionCheckJpScopeId
        }
        set(value) {
            when (serverRegion) {
                MaimaiConstants.ServerRegion.CN -> lastVersionCheckScopeId = value
                MaimaiConstants.ServerRegion.INTL -> lastVersionCheckIntlScopeId = value
                MaimaiConstants.ServerRegion.JP -> lastVersionCheckJpScopeId = value
            }
        }

    val displayPlayerName: String
        get() = activeIdentity?.primaryDisplayName ?: "Player"

    val activeB50PlayerName: String
        get() = activeIdentity?.b50PlayerName ?: "Player"

    fun updateCustomNickname(raw: String, identityId: String) {
        if (credentialStoreLoadFailed) return
        val lower = identityId.lowercase(Locale.ROOT)
        val trimmed = raw.trim()
        val list = identities.toMutableList()
        val idx = list.indexOfFirst { it.id == lower }
        if (idx < 0 || list[idx].customNickname == trimmed) return
        list[idx] = list[idx].copy(customNickname = trimmed)
        identities = list
        storeScope.launch {
            persistIdentities(list)
        }
    }

    fun exportSyncDiagnosticLog(): String = SyncDiagnostics.exportText()

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
        if (activeVersionCheckScopeId == null && activePlateScopes.isNotEmpty()) {
            activeVersionCheckScopeId = migrateLegacyPlateScopeId(
                legacyIndex = activeVersionCheckIndex,
                scopes = activePlateScopes
            )
        }
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
        SyncDiagnostics.configure(appFilesDir)
        PreferenceKeys.ensureDefaultBooleans(prefs)

        _favoriteIds = prefs.getStringSet(PreferenceKeys.favorites, null)?.toSet() ?: emptySet()
        _searchHistory = prefs.getString(PreferenceKeys.searchHistory, null)?.let { raw ->
            runCatching { MaimaiAPI.json.decodeFromString<List<String>>(raw) }.getOrNull()
        } ?: emptyList()

        val loaded = AccountCredentialStore.loadOrMigrateIdentities(appContext, prefs)
        credentialStoreLoadFailed = loaded.loadFailed
        var initialIdentities = loaded.identities
        if (!loaded.loadFailed) {
            val legacyUseDF = prefs.getBoolean(PreferenceKeys.useDFNickname, true)
            val legacyCustom = prefs.getString(PreferenceKeys.customNickname, null).orEmpty()
            val migrated = ScoreSyncEngine.migrateLegacyB50Nickname(
                identities = initialIdentities,
                legacyUseProberNickname = legacyUseDF,
                legacyCustomNickname = legacyCustom
            )
            var savedMigrated = true
            if (migrated.didChange) {
                initialIdentities = migrated.identities
                savedMigrated = AccountCredentialStore.saveIdentities(appContext, initialIdentities)
            }
            if (!migrated.didChange || savedMigrated) {
                prefs.edit()
                    .remove(PreferenceKeys.useDFNickname)
                    .remove(PreferenceKeys.customNickname)
                    .apply()
            }
        }
        identities = initialIdentities
        activeIdentityId = loaded.activeIdentityId
        AccountCredentialStore.deleteSelfHostedEndpointConfig(appContext)
        selfHostedConfig = SyncServiceConfig.builtIn()

        _serverRegion = MaimaiConstants.ServerRegion.fromRawValue(
            prefs.getString(PreferenceKeys.serverRegion, null)
        )

        _lastCNCatalogSyncEpochMs = readOptionalEpochMs(PreferenceKeys.cnCatalogSync)
        _lastZetarakuSyncEpochMs = readOptionalEpochMs(PreferenceKeys.zetarakuSync)
        _lastChartStatsSyncEpochMs = readOptionalEpochMs(PreferenceKeys.chartStatsSync)

        _enableShowAlias = prefs.getBoolean(PreferenceKeys.showAlias, true)
        _enableAliasSearch = prefs.getBoolean(PreferenceKeys.aliasSearch, true)
        _enableCharterSearch = prefs.getBoolean(PreferenceKeys.charterSearch, false)

        _lastVersionCheckIndex = prefs.getInt(PreferenceKeys.lastVersionCheck, 0)
        _lastVersionCheckIntlIndex = prefs.getInt(PreferenceKeys.lastVersionCheckIntl, 0)
        _lastVersionCheckJpIndex = prefs.getInt(PreferenceKeys.lastVersionCheckJp, 0)
        _lastVersionCheckScopeId = prefs.getString(PreferenceKeys.lastVersionCheckScopeId, null)
        _lastVersionCheckIntlScopeId = prefs.getString(PreferenceKeys.lastVersionCheckIntlScopeId, null)
        _lastVersionCheckJpScopeId = prefs.getString(PreferenceKeys.lastVersionCheckJpScopeId, null)
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

        val needsReleaseDateBackfill = cnSongs.isNotEmpty() && cnSongs.all { it.releaseDate == null }
        delay(SyncPolicy.BOOTSTRAP_DELAY_MS)
        coroutineScope {
            val catalogJob = async { syncAllCatalogsAndRules(force = needsReleaseDateBackfill) }
            val statsJob = async { refreshChartStatsIfStale(force = false) }
            val updateJob = async { checkAppUpdateIfNeeded(manual = false, ignoreInterval = true) }
            catalogJob.await()
            statsJob.await()
            updateJob.await()
        }
    }

    fun checkAppUpdateOnForeground() {
        if (!didBootstrap) return
        storeScope.launch {
            checkAppUpdateIfNeeded(manual = false)
        }
    }

    suspend fun checkAppUpdateIfNeeded(manual: Boolean, ignoreInterval: Boolean = false) = withContext(Dispatchers.Main.immediate) {
        if (isCheckingAppUpdate) return@withContext
        val nowMs = System.currentTimeMillis()
        if (!manual && !ignoreInterval) {
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

            if (latest.isNewerThan(currentShort)) {
                val ignored = prefs.getString(PreferenceKeys.ignoredAppUpdateVersion, null)
                if (manual || ignored != latest.version) {
                    if (!manual && appUpdateBlockerCount > 0) {
                        pendingAppUpdate = latest
                    } else {
                        pendingAppUpdate = null
                        availableAppUpdate = latest
                    }
                }
            } else if (manual) {
                appUpdateToast = "当前已是最新版本（v$currentShort）"
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            if (manual) {
                appUpdateMessage = "检查更新失败，请检查网络连接后重试"
            }
        } finally {
            isCheckingAppUpdate = false
        }
    }

    fun beginAppUpdatePresentationBlock() {
        appUpdateBlockerCount += 1
    }

    fun endAppUpdatePresentationBlock() {
        appUpdateBlockerCount = max(0, appUpdateBlockerCount - 1)
        if (appUpdateBlockerCount == 0) {
            val deferred = pendingAppUpdate ?: return
            pendingAppUpdate = null
            if (availableAppUpdate == null) {
                availableAppUpdate = deferred
            }
        }
    }

    fun ignoreAppUpdate(version: String) {
        prefs.edit().putString(PreferenceKeys.ignoredAppUpdateVersion, version).apply()
        availableAppUpdate = null
        pendingAppUpdate = null
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
        val levelCheckBuckets: Map<String, List<LevelCheckDSBucket>>,
        val maxNotes: MaxNotesStats
    )

    private suspend fun loadLocalCaches() {
        val currentIdentityId = activeIdentityId
        val recordsFile = currentIdentityId?.let { AccountCredentialStore.recordsFile(appFilesDir, it) }

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
                    val decoded = MaimaiAPI.json.decodeFromString<List<ProberRecord>>(recordsFile.readText(Charsets.UTF_8))
                    ScoreSyncEngine.normalizeCachedRecords(decoded)
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
        if (activeIdentityId == currentIdentityId && recordsEpoch == 0) {
            setRecordsSnapshot(caches.records.orEmpty(), ownerIdentityId = currentIdentityId)
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
        levelCheckBuckets = snapshot.levelCheckBuckets
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

    private fun setRecordsSnapshot(list: List<ProberRecord>, ownerIdentityId: String?) {
        val map = HashMap<String, ProberRecord>(list.size)
        for (rec in list) {
            map["${rec.songId}_${rec.levelIndex}"] = rec
        }
        recordsByKey = map
        recordsOwnerIdentityId = ownerIdentityId?.lowercase(Locale.ROOT)
        _records = list
    }

    // Server Region Switching & Multi-Tier Throttled Sync

    suspend fun switchServerRegion(newRegion: MaimaiConstants.ServerRegion) {
        val (epoch, cachedCatalog) = withContext(Dispatchers.Main.immediate) {
            if (newRegion == serverRegion) return@withContext null
            regionEpoch += 1
            val ep = regionEpoch
            serverRegion = newRegion
            syncNotice = null
            if (newRegion == MaimaiConstants.ServerRegion.CN) {
                ep to null
            } else {
                val cached = zetarakuCatalog
                if (cached == null) {
                    songs = emptyList()
                    songsById = emptyMap()
                    levelCheckBuckets = emptyMap()
                    maxNotesStats = MaxNotesStats.fallback
                    catalogRevision += 1
                    rebuildPlateScopeCache()
                }
                ep to cached
            }
        } ?: return

        storeScope.async {
            when (newRegion) {
                MaimaiConstants.ServerRegion.CN -> {
                    applySongs(cnSongs, epoch = epoch)
                }
                MaimaiConstants.ServerRegion.INTL, MaimaiConstants.ServerRegion.JP -> {
                    if (cachedCatalog != null) {
                        applyZetarakuCatalog(cachedCatalog, region = newRegion, epoch = epoch)
                        refreshZetarakuIfStale()
                    } else {
                        loadOrFetchZetaraku(region = newRegion, epoch = epoch)
                    }
                }
            }
            if (regionEpoch == epoch) {
                prefetchBest50Covers()
            }
        }.await()
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
                catalogSyncToast = "当前已是最新数据"
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
                catalogSyncToast = "已更新至最新数据"
                syncNotice = null
            } else {
                val err = cnErr ?: zErr
                val detail = err?.message ?: "网络请求异常"
                catalogSyncMessage = "部分数据同步失败：$detail"
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
                val zTask = async { runCatchingCancellable { MaimaiAPI.fetchZetarakuCatalog() } }
                val idTask = async { runCatchingCancellable { MaimaiAPI.fetchDXDataIdMap() }.getOrNull() }
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
            val aliasResp = runCatchingCancellable { MaimaiAPI.fetchLXNSAliases() }.getOrNull()
            val plateResp = runCatchingCancellable { MaimaiAPI.fetchLXNSPlates() }.getOrNull()

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
                catalogSyncToast = "当前已是最新数据"
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
                catalogSyncToast = "拟合定数已刷新"
                syncNotice = null
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            val detail = err.message ?: "网络请求异常"
            catalogSyncMessage = if (force) "拟合定数获取失败：$detail" else null
            syncNotice = DataSyncNotice(
                kind = DataSyncNotice.Kind.STATS,
                message = "拟合定数获取失败，正在使用本地数据。可前往「设置与数据」重试。"
            )
        } finally {
            isSyncingStats = false
        }
    }

    // Multi-Channel Identity Connection, Sync & Writeback & OAuth 2.0 + PKCE

    fun loadHistory(identityId: String): List<SyncHistoryEntry> =
        syncHistoryStore.loadHistory(identityId)

    fun switchIdentity(identityId: String) {
        val lower = identityId.lowercase(Locale.ROOT)
        if (identities.none { it.id == lower } || activeIdentityId == lower) return
        setActiveIdentityInternal(lower, restoreFromCache = true)
    }

    fun removeIdentity(identityId: String) {
        if (credentialStoreLoadFailed) return
        val lower = identityId.lowercase(Locale.ROOT)
        val wasActive = activeIdentityId == lower
        val updated = identities.filterNot { it.id == lower }
        identities = updated
        if (tokenRefreshMutexes[lower]?.isLocked == false) {
            tokenRefreshMutexes.remove(lower)
        }

        val endpointCfg = selfHostedConfig
        storeScope.launch {
            persistIdentities(updated)
            withContext(NonCancellable + Dispatchers.IO) {
                val shSecret = AccountCredentialStore.loadSelfHostedSecret(appContext, lower)
                if (shSecret.syncToken.isNotEmpty() && endpointCfg.isConfigured) {
                    launch {
                        runCatchingCancellable {
                            MaimaiAPI.syncBackendUnbind(endpointCfg, shSecret.syncToken)
                        }
                    }
                }
                AccountCredentialStore.deleteAllChannelSecrets(appContext, lower)
                AccountCredentialStore.deleteLegacyProfileSecret(appContext, lower)
                val recordFile = AccountCredentialStore.recordsFile(appFilesDir, lower)
                if (recordFile.exists()) {
                    recordFile.delete()
                }
                val legacyRecordFile = AccountCredentialStore.legacyProfileRecordsFile(appFilesDir, lower)
                if (legacyRecordFile.exists()) {
                    legacyRecordFile.delete()
                }
                syncHistoryStore.deleteHistory(lower)
            }
        }

        if (wasActive) {
            val nextId = updated.firstOrNull()?.id
            if (nextId != null) {
                setActiveIdentityInternal(nextId, restoreFromCache = true)
            } else {
                activeIdentityId = null
                prefs.edit().remove(PreferenceKeys.activeIdentityId).apply()
                recordsEpoch += 1
                setRecordsSnapshot(emptyList(), ownerIdentityId = null)
            }
        }
    }

    fun resetCredentialStoreAfterLoadFailure() {
        if (!credentialStoreLoadFailed) return
        AccountCredentialStore.resetIdentitiesAfterLoadFailure(appContext, prefs)
        credentialStoreLoadFailed = false
        identities = emptyList()
        activeIdentityId = null
        recordsEpoch += 1
        setRecordsSnapshot(emptyList(), ownerIdentityId = null)
    }

    suspend fun unbindChannel(channel: SyncChannelKind, identityId: String) = withContext(Dispatchers.Main.immediate) {
        ensureCredentialStoreWritable()
        val lower = identityId.lowercase(Locale.ROOT)
        if (identities.none { it.id == lower }) return@withContext

        when (channel) {
            SyncChannelKind.SELF_HOSTED -> {
                val endpointCfg = selfHostedConfig
                val shSecret = withContext(Dispatchers.IO) {
                    AccountCredentialStore.loadSelfHostedSecret(appContext, lower)
                }
                if (shSecret.syncToken.isNotEmpty() && endpointCfg.isConfigured) {
                    storeScope.launch(Dispatchers.IO) {
                        runCatchingCancellable {
                            MaimaiAPI.syncBackendUnbind(endpointCfg, shSecret.syncToken)
                        }
                    }
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    AccountCredentialStore.deleteSelfHostedSecret(appContext, lower)
                }
            }

            SyncChannelKind.LXNS -> {
                if (tokenRefreshMutexes[lower]?.isLocked == false) {
                    tokenRefreshMutexes.remove(lower)
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    AccountCredentialStore.deleteLXNSSecret(appContext, lower)
                }
            }

            SyncChannelKind.DIVING_FISH -> {
                withContext(NonCancellable + Dispatchers.IO) {
                    AccountCredentialStore.deleteDivingFishSecret(appContext, lower)
                }
            }
        }

        val list = identities.toMutableList()
        val idx = list.indexOfFirst { it.id == lower }
        if (idx < 0) return@withContext
        val cur = list[idx]
        list[idx] = when (channel) {
            SyncChannelKind.SELF_HOSTED -> cur.copy(selfHosted = null, hasMixedScoreSources = true)
            SyncChannelKind.LXNS -> cur.copy(lxns = null, hasMixedScoreSources = true)
            SyncChannelKind.DIVING_FISH -> cur.copy(divingFish = null, hasMixedScoreSources = true)
        }
        identities = list
        persistIdentities(list)
    }

    fun onOAuthCallbackIntent(uriString: String) {
        val now = System.currentTimeMillis()
        val ttlMs = 15L * 60L * 1000L
        val mem = pendingPKCESession
        if (mem != null) {
            if (now - mem.createdAtEpochMs <= ttlMs) {
                _pendingOAuthCallbacks.trySend(uriString)
            } else {
                pendingPKCESession = null
                storeScope.launch(Dispatchers.IO) {
                    AccountCredentialStore.clearPendingOAuthSession(appContext)
                }
            }
            return
        }
        storeScope.launch(Dispatchers.IO) {
            val persisted = AccountCredentialStore.loadPendingOAuthSession(appContext)
            if (persisted != null && System.currentTimeMillis() - persisted.createdAtEpochMs <= ttlMs) {
                pendingPKCESession = PendingPKCESession(
                    codeVerifier = persisted.codeVerifier,
                    state = persisted.state,
                    targetIdentityId = persisted.targetIdentityId,
                    createdAtEpochMs = persisted.createdAtEpochMs
                )
                _pendingOAuthCallbacks.trySend(uriString)
            } else if (persisted != null) {
                AccountCredentialStore.clearPendingOAuthSession(appContext)
            }
        }
    }

    fun cancelPendingLXNSOAuth() {
        pendingPKCESession = null
        storeScope.launch(Dispatchers.IO) {
            AccountCredentialStore.clearPendingOAuthSession(appContext)
        }
    }

    fun beginLXNSOAuth(targetIdentityId: String? = null): String {
        if (isSyncingRecords) throw MaimaiAPIException.SyncInProgress()
        val verifier = PKCEHelper.generateCodeVerifier()
        val state = PKCEHelper.generateState()
        val nonce = PKCEHelper.generateState()
        val challenge = PKCEHelper.codeChallengeS256(verifier)
        val nowMs = System.currentTimeMillis()
        val session = PendingPKCESession(
            codeVerifier = verifier,
            state = state,
            targetIdentityId = targetIdentityId?.lowercase(Locale.ROOT),
            createdAtEpochMs = nowMs
        )
        pendingPKCESession = session
        storeScope.launch(Dispatchers.IO) {
            AccountCredentialStore.savePendingOAuthSession(
                appContext,
                AccountCredentialStore.PersistedPKCESession(
                    codeVerifier = verifier,
                    state = state,
                    targetIdentityId = session.targetIdentityId,
                    createdAtEpochMs = nowMs
                )
            )
        }
        return MaimaiAPI.buildLXNSAuthorizeUrl(
            codeChallenge = challenge,
            state = state,
            nonce = nonce
        )
    }

    private suspend fun <T> runDetachedRecordSync(
        identityId: String? = null,
        operation: SyncOperationKind,
        block: suspend () -> T
    ): T =
        withContext(Dispatchers.Main.immediate) {
            if (isSyncingRecords) throw MaimaiAPIException.SyncInProgress()
            activeSyncTask = ActiveSyncTask(
                identityId = identityId?.lowercase(Locale.ROOT),
                operation = operation
            )
            storeScope.async {
                try {
                    block()
                } catch (ce: CancellationException) {
                    throw ce
                } catch (err: Throwable) {
                    if (operation != SyncOperationKind.PULL_MERGED && operation != SyncOperationKind.WRITEBACK) {
                        recordDiagnosticFailure(operation = operation.name.lowercase(Locale.ROOT), error = err)
                    }
                    throw err
                } finally {
                    withContext(NonCancellable + Dispatchers.Main.immediate) {
                        activeSyncTask = null
                    }
                }
            }.await()
        }

    /** 0A. Self-Hosted Bind (paste QR text/URL -> initial quick or full sync) */
    suspend fun bindSelfHosted(
        qrContent: String,
        initialFullSync: Boolean,
        targetIdentityId: String? = null
    ): Pair<PlayerIdentity, Int> = runDetachedRecordSync(
        identityId = targetIdentityId,
        operation = SyncOperationKind.BIND
    ) {
        ensureCredentialStoreWritable()
        val endpoint = selfHostedConfig
        if (!endpoint.isConfigured) {
            throw MaimaiAPIException.Unauthorized("当前版本暂不支持国服直连")
        }

        val resp = MaimaiAPI.syncBackendBind(endpoint, qrContent = qrContent, fullSync = initialFullSync)
        val syncToken = resp.syncToken?.takeIf { it.isNotEmpty() }
            ?: throw MaimaiAPIException.HttpError(-1, "服务端未返回同步令牌")

        val explicitLower = targetIdentityId?.lowercase(Locale.ROOT)
        val explicitTarget = explicitLower?.let { target -> identities.firstOrNull { it.id == target } }
        val existingUID = explicitTarget?.selfHosted?.externalUserId
        if (existingUID != null && existingUID != resp.profile.userId) {
            storeScope.launch(Dispatchers.IO) {
                runCatchingCancellable {
                    MaimaiAPI.syncBackendUnbind(endpoint, syncToken)
                }
            }
            throw MaimaiAPIException.BackendError(
                statusCode = 403,
                errorCode = "USER_ID_MISMATCH",
                serverMessage = "二维码对应的玩家与该档案已绑定的国服账号不一致，请确认微信账号"
            )
        }

        val resolvedId = when {
            explicitTarget != null -> explicitTarget.id
            else -> identities.firstOrNull { it.selfHosted?.externalUserId == resp.profile.userId }?.id
                ?: UUID.randomUUID().toString().lowercase(Locale.ROOT)
        }

        val previousToken = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadSelfHostedSecret(appContext, resolvedId).syncToken
        }
        val savedSecret = withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveSelfHostedSecret(appContext, resolvedId, SelfHostedSecret(syncToken = syncToken))
        }
        if (!savedSecret) {
            throw MaimaiAPIException.HttpError(-1, "保存国服直连凭据失败")
        }
        if (previousToken.isNotEmpty() && previousToken != syncToken) {
            storeScope.launch(Dispatchers.IO) {
                runCatchingCancellable {
                    MaimaiAPI.syncBackendUnbind(endpoint, previousToken)
                }
            }
        }

        val mode = if (initialFullSync) SyncMode.FULL else SyncMode.QUICK
        val nowMs = if (resp.syncedAtUnixMs > 0L) resp.syncedAtUnixMs else System.currentTimeMillis()
        val displayName = resp.profile.userName?.takeIf { it.isNotEmpty() } ?: "UID ${resp.profile.userId}"
        val binding = SelfHostedBinding(
            externalUserId = resp.profile.userId,
            displayName = displayName,
            playerRating = resp.profile.playerRating,
            boundAtEpochMs = nowMs
        )

        val list = identities.toMutableList()
        val idx = list.indexOfFirst { it.id == resolvedId }
        if (idx >= 0) {
            val cur = list[idx]
            list[idx] = cur.copy(
                selfHosted = binding,
                lastSyncMode = mode,
                lastSyncedAtEpochMs = nowMs,
                lastFullSyncAtEpochMs = if (initialFullSync) nowMs else cur.lastFullSyncAtEpochMs,
                hasMixedScoreSources = if (initialFullSync) false else cur.hasMixedScoreSources
            )
        } else {
            list += PlayerIdentity(
                id = resolvedId,
                label = displayName,
                selfHosted = binding,
                lastSyncMode = mode,
                lastSyncedAtEpochMs = nowMs,
                lastFullSyncAtEpochMs = if (initialFullSync) nowMs else null
            )
        }
        identities = list
        persistIdentities(list)

        val lookup = scoreImportSongLookup()
        val incoming = withContext(Dispatchers.Default) {
            ScoreSyncEngine.normalizeBackendRecords(
                resp.records,
                songsById = lookup,
                region = MaimaiConstants.ServerRegion.CN
            )
        }
        val merged = if (initialFullSync) {
            ScoreSyncEngine.applyFullSync(incoming)
        } else {
            val existing = loadRecordsSnapshot(resolvedId)
            ScoreSyncEngine.mergeQuickSync(existing = existing, incoming = incoming)
        }

        val shouldMakeActive = (activeIdentityId == null) || (targetIdentityId == null) || (activeIdentityId == resolvedId)
        installFetchedRecords(merged, resolvedId, mode, makeActive = shouldMakeActive)
        refreshCNCatalogIfUnknownSongsFound(merged, resolvedId)
        SyncDiagnostics.record(operation = "bind_cn", message = "绑定国服直连成功（共 ${merged.size} 条）")

        val updatedIdentity = identities.firstOrNull { it.id == resolvedId } ?: list.last()
        updatedIdentity to merged.size
    }

    /** 0B. Self-Hosted Quick Sync (uses saved syncToken, preserves local FC/FS badges) */
    suspend fun quickSyncSelfHosted(
        identityId: String? = null,
        writeback: Boolean = false
    ): WritebackSummary? {
        val (targetId, endpoint) = withContext(Dispatchers.Main.immediate) {
            ensureCredentialStoreWritable()
            val resolvedId = (identityId ?: activeIdentityId)?.lowercase(Locale.ROOT)
            val identity = resolvedId?.let { id -> identities.firstOrNull { it.id == id } }
            if (resolvedId == null || identity == null || identity.selfHosted == null) {
                throw MaimaiAPIException.Unauthorized("当前档案未绑定国服直连")
            }
            val cfg = selfHostedConfig
            if (!cfg.isConfigured) {
                throw MaimaiAPIException.Unauthorized("当前版本暂不支持国服直连")
            }
            resolvedId to cfg
        }
        return runDetachedRecordSync(identityId = targetId, operation = SyncOperationKind.QUICK_SYNC) {
            val secret = withContext(Dispatchers.IO) {
                AccountCredentialStore.loadSelfHostedSecret(appContext, targetId)
            }
            if (secret.syncToken.isEmpty()) {
                throw MaimaiAPIException.Unauthorized("国服直连已失效，请重新绑定")
            }

            val resp = MaimaiAPI.syncBackendQuick(endpoint, syncToken = secret.syncToken)
            val nowMs = if (resp.syncedAtUnixMs > 0L) resp.syncedAtUnixMs else System.currentTimeMillis()
            val list = identities.toMutableList()
            val idx = list.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                val cur = list[idx]
                val curSh = cur.selfHosted
                val nextSh = curSh?.copy(
                    displayName = resp.profile.userName?.takeIf { it.isNotEmpty() } ?: curSh.displayName,
                    playerRating = resp.profile.playerRating ?: curSh.playerRating
                )
                list[idx] = cur.copy(
                    selfHosted = nextSh,
                    lastSyncMode = SyncMode.QUICK,
                    lastSyncedAtEpochMs = nowMs
                )
                identities = list
                persistIdentities(list)
            }

            val lookup = scoreImportSongLookup()
            val incoming = withContext(Dispatchers.Default) {
                ScoreSyncEngine.normalizeBackendRecords(
                    resp.records,
                    songsById = lookup,
                    region = MaimaiConstants.ServerRegion.CN
                )
            }
            val existing = loadRecordsSnapshot(targetId)
            val merged = ScoreSyncEngine.mergeQuickSync(existing = existing, incoming = incoming)

            installFetchedRecords(merged, targetId, SyncMode.QUICK, makeActive = false)
            refreshCNCatalogIfUnknownSongsFound(merged, targetId)
            SyncDiagnostics.record(operation = "quick_sync", message = "快速同步完成（共 ${merged.size} 条）")

            if (writeback) {
                performWriteback(targetId, merged)
            } else {
                null
            }
        }
    }

    /** 0C. Self-Hosted Full Sync (requires pasted QR, authoritative badge overwrite) */
    suspend fun fullSyncSelfHosted(
        identityId: String? = null,
        qrContent: String,
        writeback: Boolean = false
    ): WritebackSummary? {
        val (targetId, endpoint) = withContext(Dispatchers.Main.immediate) {
            ensureCredentialStoreWritable()
            val resolvedId = (identityId ?: activeIdentityId)?.lowercase(Locale.ROOT)
            val identity = resolvedId?.let { id -> identities.firstOrNull { it.id == id } }
            if (resolvedId == null || identity == null || identity.selfHosted == null) {
                throw MaimaiAPIException.Unauthorized("当前档案未绑定国服直连")
            }
            val cfg = selfHostedConfig
            if (!cfg.isConfigured) {
                throw MaimaiAPIException.Unauthorized("当前版本暂不支持国服直连")
            }
            resolvedId to cfg
        }
        return runDetachedRecordSync(identityId = targetId, operation = SyncOperationKind.FULL_SYNC) {
            val secret = withContext(Dispatchers.IO) {
                AccountCredentialStore.loadSelfHostedSecret(appContext, targetId)
            }
            if (secret.syncToken.isEmpty()) {
                throw MaimaiAPIException.Unauthorized("国服直连已失效，请重新绑定")
            }

            val resp = MaimaiAPI.syncBackendFull(endpoint, syncToken = secret.syncToken, qrContent = qrContent)
            val nowMs = if (resp.syncedAtUnixMs > 0L) resp.syncedAtUnixMs else System.currentTimeMillis()
            val list = identities.toMutableList()
            val idx = list.indexOfFirst { it.id == targetId }
            if (idx >= 0) {
                val cur = list[idx]
                val curSh = cur.selfHosted
                val nextSh = curSh?.copy(
                    displayName = resp.profile.userName?.takeIf { it.isNotEmpty() } ?: curSh.displayName,
                    playerRating = resp.profile.playerRating ?: curSh.playerRating
                )
                list[idx] = cur.copy(
                    selfHosted = nextSh,
                    lastSyncMode = SyncMode.FULL,
                    lastSyncedAtEpochMs = nowMs,
                    lastFullSyncAtEpochMs = nowMs,
                    hasMixedScoreSources = false
                )
                identities = list
                persistIdentities(list)
            }

            val lookup = scoreImportSongLookup()
            val incoming = withContext(Dispatchers.Default) {
                ScoreSyncEngine.normalizeBackendRecords(
                    resp.records,
                    songsById = lookup,
                    region = MaimaiConstants.ServerRegion.CN
                )
            }
            val replaced = ScoreSyncEngine.applyFullSync(incoming)

            installFetchedRecords(replaced, targetId, SyncMode.FULL, makeActive = false)
            refreshCNCatalogIfUnknownSongsFound(replaced, targetId)
            SyncDiagnostics.record(operation = "full_sync", message = "完整同步完成（共 ${replaced.size} 条）")

            if (writeback) {
                performWriteback(targetId, replaced)
            } else {
                null
            }
        }
    }

    /** 1. LXNS OAuth 2.0 + PKCE Bind */
    suspend fun completeLXNSOAuth(callbackUriString: String): PlayerIdentity = withContext(Dispatchers.Main.immediate) {
        val session = loadActivePendingOAuthSession() ?: throw MaimaiAPIException.OAuthStateMismatch()
        val code = try {
            MaimaiAPI.parseOAuthCallback(callbackUriString, expectedState = session.state)
        } catch (err: MaimaiAPIException.OAuthStateMismatch) {
            throw err
        } catch (err: Throwable) {
            withContext(Dispatchers.IO) { clearPendingLXNSOAuthSessionSync() }
            throw err
        }
        withContext(Dispatchers.IO) { clearPendingLXNSOAuthSessionSync() }

        runDetachedRecordSync(identityId = session.targetIdentityId, operation = SyncOperationKind.BIND) {
            ensureCredentialStoreWritable()
            val tokenResp = MaimaiAPI.exchangeLXNSCode(code = code, codeVerifier = session.codeVerifier)
            val expiresAtSec = System.currentTimeMillis() / 1000L + tokenResp.expiresIn
            val userInfo = runCatchingCancellable { MaimaiAPI.fetchLXNSUserInfo(tokenResp.accessToken) }.getOrNull()

            val accountKey = userInfo?.sub?.takeIf { it.isNotEmpty() }?.let { "oidc_$it" }
                ?: "lxns_oauth_${UUID.randomUUID().toString().lowercase(Locale.ROOT)}"
            val lxUsername = userInfo?.displayUsername.orEmpty()
            val grantedScopes = tokenResp.scope
            val hasWriteScope = grantedScopes?.split(Regex("\\s+"))?.contains("write_player") ?: true
            val nowMs = System.currentTimeMillis()

            val binding = LXNSBinding(
                authMethod = ScoreAuthMethod.LXNS_OAUTH,
                accountKey = accountKey,
                displayName = lxUsername.ifEmpty { "落雪用户" },
                friendCode = null,
                hasWriteScope = hasWriteScope,
                boundAtEpochMs = nowMs
            )
            val targetId = resolveOrCreateIdentity(
                targetIdentityId = session.targetIdentityId,
                matchingLXNSKey = accountKey,
                defaultLabel = binding.displayName
            ) { cur ->
                val mixed = cur.hasMixedScoreSources || cur.selfHosted != null || cur.divingFish != null
                cur.copy(lxns = binding, hasMixedScoreSources = mixed)
            }

            val savedSecret = withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.saveLXNSSecret(
                    appContext,
                    targetId,
                    LXNSSecret(
                        accessToken = tokenResp.accessToken,
                        refreshToken = tokenResp.refreshToken.orEmpty(),
                        accessTokenExpiresAtEpochSec = expiresAtSec,
                        grantedScopes = grantedScopes
                    )
                )
            }
            if (!savedSecret) {
                throw MaimaiAPIException.HttpError(-1, "保存落雪令牌失败")
            }

            val makeActive = (activeIdentityId == null) ||
                (session.targetIdentityId == null) ||
                (activeIdentityId == targetId)
            val resolvedIdentity = identities.first { it.id == targetId }
            if (resolvedIdentity.selfHosted != null) {
                if (makeActive && activeIdentityId != targetId) {
                    setActiveIdentityInternal(targetId, restoreFromCache = true)
                }
                SyncDiagnostics.record(operation = "bind", channel = "lxns", message = "绑定落雪账号（保留国服直连成绩）")
                return@runDetachedRecordSync resolvedIdentity
            }
            try {
                performPullLXNS(
                    identityId = targetId,
                    mergeExisting = session.targetIdentityId != null,
                    makeActive = makeActive
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (err: Throwable) {
                if (makeActive && activeIdentityId != targetId) {
                    setActiveIdentityInternal(targetId, restoreFromCache = true)
                }
                throw MaimaiAPIException.RecordSyncFailedAfterLogin(err.message ?: "成绩拉取失败")
            }

            identities.first { it.id == targetId }
        }
    }

    /** 2. LXNS Personal API Key Bind (`X-User-Token`) */
    suspend fun bindLXNSPersonalToken(
        rawToken: String,
        remarkName: String = "",
        targetIdentityId: String? = null
    ): PlayerIdentity = runDetachedRecordSync(identityId = targetIdentityId, operation = SyncOperationKind.BIND) {
        ensureCredentialStoreWritable()
        val token = rawToken.trim()
        val playerAndScores = MaimaiAPI.fetchLXNSPersonalTokenPlayerAndScores(token)
        val trimmedRemark = remarkName.trim()
        val accountKey = playerAndScores.player?.friendCode?.let { "fc_$it" }
            ?: "lxns_pt_${token.sha256Hex().take(16)}"
        val nickname = playerAndScores.player?.name?.takeIf { it.isNotEmpty() }
            ?: trimmedRemark.ifEmpty { "落雪用户" }
        val nowMs = System.currentTimeMillis()

        val binding = LXNSBinding(
            authMethod = ScoreAuthMethod.LXNS_PERSONAL_TOKEN,
            accountKey = accountKey,
            displayName = nickname,
            friendCode = playerAndScores.player?.friendCode,
            hasWriteScope = true,
            boundAtEpochMs = nowMs
        )
        val targetId = resolveOrCreateIdentity(
            targetIdentityId = targetIdentityId,
            matchingLXNSKey = accountKey,
            defaultLabel = nickname
        ) { cur ->
            val mixed = cur.hasMixedScoreSources || cur.selfHosted != null || cur.divingFish != null
            if (cur.selfHosted == null) {
                cur.copy(
                    lxns = binding,
                    lastSyncMode = SyncMode.LXNS,
                    lastSyncedAtEpochMs = nowMs,
                    hasMixedScoreSources = mixed
                )
            } else {
                cur.copy(lxns = binding, hasMixedScoreSources = mixed)
            }
        }

        val savedSecret = withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveLXNSSecret(
                appContext,
                targetId,
                LXNSSecret(personalToken = token, grantedScopes = LXNSConfiguration.SCOPES)
            )
        }
        if (!savedSecret) {
            throw MaimaiAPIException.HttpError(-1, "保存落雪密钥失败")
        }

        val makeActive = (activeIdentityId == null) || (targetIdentityId == null) || (activeIdentityId == targetId)
        val resolvedIdentity = identities.first { it.id == targetId }
        if (resolvedIdentity.selfHosted != null) {
            if (makeActive && activeIdentityId != targetId) {
                setActiveIdentityInternal(targetId, restoreFromCache = true)
            }
            SyncDiagnostics.record(operation = "bind", channel = "lxns", message = "绑定落雪个人密钥（保留国服直连成绩）")
            return@runDetachedRecordSync resolvedIdentity
        }

        val lookup = scoreImportSongLookup()
        val converted = withContext(Dispatchers.Default) {
            LXNSCatalogConverter.convertScores(playerAndScores.scores, lookup)
        }
        val finalRecords = if (targetIdentityId != null) {
            val existing = loadRecordsSnapshot(targetId)
            if (existing.isEmpty()) {
                ScoreSyncEngine.applyFullSync(converted)
            } else {
                val list = identities.toMutableList()
                val idx = list.indexOfFirst { it.id == targetId }
                if (idx >= 0 && !list[idx].hasMixedScoreSources) {
                    list[idx] = list[idx].copy(hasMixedScoreSources = true)
                    identities = list
                    persistIdentities(list)
                }
                ScoreSyncEngine.mergeQuickSync(existing, converted)
            }
        } else {
            ScoreSyncEngine.applyFullSync(converted)
        }
        installFetchedRecords(finalRecords, targetId, SyncMode.LXNS, makeActive = makeActive)
        refreshCNCatalogIfUnknownSongsFound(finalRecords, targetId)
        identities.first { it.id == targetId }
    }

    /** 3. Diving-Fish Username + Password Bind */
    suspend fun bindDivingFishPassword(
        username: String,
        password: String,
        targetIdentityId: String? = null
    ): PlayerIdentity = runDetachedRecordSync(identityId = targetIdentityId, operation = SyncOperationKind.BIND) {
        ensureCredentialStoreWritable()
        val trimmedUser = username.trim()
        val cookie = MaimaiAPI.loginDivingFish(trimmedUser, password)
        val nowMs = System.currentTimeMillis()
        val binding = DivingFishBinding(
            authMethod = ScoreAuthMethod.DIVING_FISH_PASSWORD,
            accountKey = trimmedUser,
            username = trimmedUser,
            nickname = "",
            boundAtEpochMs = nowMs
        )
        val targetId = resolveOrCreateIdentity(
            targetIdentityId = targetIdentityId,
            matchingDFKey = trimmedUser,
            defaultLabel = trimmedUser
        ) { cur ->
            val mixed = cur.hasMixedScoreSources || cur.selfHosted != null || cur.lxns != null
            cur.copy(divingFish = binding, hasMixedScoreSources = mixed)
        }

        val savedSecret = withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveDivingFishSecret(
                appContext,
                targetId,
                DivingFishSecret(username = trimmedUser, password = password, cookie = cookie)
            )
        }
        if (!savedSecret) {
            throw MaimaiAPIException.HttpError(-1, "保存水鱼凭据失败")
        }

        val makeActive = (activeIdentityId == null) || (targetIdentityId == null) || (activeIdentityId == targetId)
        val resolvedIdentity = identities.first { it.id == targetId }
        if (resolvedIdentity.selfHosted != null) {
            if (makeActive && activeIdentityId != targetId) {
                setActiveIdentityInternal(targetId, restoreFromCache = true)
            }
            SyncDiagnostics.record(operation = "bind", channel = "divingFish", message = "绑定水鱼账号（保留国服直连成绩）")
            return@runDetachedRecordSync resolvedIdentity
        }
        try {
            performPullDivingFish(
                identityId = targetId,
                mergeExisting = targetIdentityId != null,
                makeActive = makeActive
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (err: Throwable) {
            if (makeActive && activeIdentityId != targetId) {
                setActiveIdentityInternal(targetId, restoreFromCache = true)
            }
            throw MaimaiAPIException.RecordSyncFailedAfterLogin(err.message ?: "成绩拉取失败")
        }

        identities.first { it.id == targetId }
    }

    /** 4. Diving-Fish Import-Token Bind */
    suspend fun bindDivingFishImportToken(
        rawToken: String,
        remarkName: String = "",
        targetIdentityId: String? = null
    ): PlayerIdentity = runDetachedRecordSync(identityId = targetIdentityId, operation = SyncOperationKind.BIND) {
        ensureCredentialStoreWritable()
        val token = rawToken.trim()
        val resp = MaimaiAPI.fetchDivingFishRecordsByImportToken(token)
        val trimmedRemark = remarkName.trim()
        val resolvedUsername = resp.username?.takeIf { it.isNotEmpty() } ?: trimmedRemark
        val accountKey = resp.username?.takeIf { it.isNotEmpty() }
            ?: "df_token_${token.sha256Hex().take(16)}"
        val resolvedNickname = resp.nickname?.takeIf { it.isNotEmpty() } ?: trimmedRemark
        val nowMs = System.currentTimeMillis()

        val binding = DivingFishBinding(
            authMethod = ScoreAuthMethod.DIVING_FISH_IMPORT_TOKEN,
            accountKey = accountKey,
            username = resolvedUsername,
            nickname = resolvedNickname,
            boundAtEpochMs = nowMs
        )
        val targetId = resolveOrCreateIdentity(
            targetIdentityId = targetIdentityId,
            matchingDFKey = accountKey,
            defaultLabel = resolvedNickname.ifEmpty { resolvedUsername }
        ) { cur ->
            val mixed = cur.hasMixedScoreSources || cur.selfHosted != null || cur.lxns != null
            if (cur.selfHosted == null) {
                cur.copy(
                    divingFish = binding,
                    lastSyncMode = SyncMode.DIVING_FISH,
                    lastSyncedAtEpochMs = nowMs,
                    hasMixedScoreSources = mixed
                )
            } else {
                cur.copy(divingFish = binding, hasMixedScoreSources = mixed)
            }
        }

        val savedSecret = withContext(NonCancellable + Dispatchers.IO) {
            AccountCredentialStore.saveDivingFishSecret(
                appContext,
                targetId,
                DivingFishSecret(username = resolvedUsername, importToken = token)
            )
        }
        if (!savedSecret) {
            throw MaimaiAPIException.HttpError(-1, "保存水鱼导入凭据失败")
        }

        val makeActive = (activeIdentityId == null) || (targetIdentityId == null) || (activeIdentityId == targetId)
        val resolvedIdentity = identities.first { it.id == targetId }
        if (resolvedIdentity.selfHosted != null) {
            if (makeActive && activeIdentityId != targetId) {
                setActiveIdentityInternal(targetId, restoreFromCache = true)
            }
            SyncDiagnostics.record(operation = "bind", channel = "divingFish", message = "绑定水鱼导入密钥（保留国服直连成绩）")
            return@runDetachedRecordSync resolvedIdentity
        }

        val fetched = resp.records.orEmpty()
        val finalRecords = if (targetIdentityId != null) {
            val existing = loadRecordsSnapshot(targetId)
            if (existing.isEmpty()) {
                ScoreSyncEngine.applyFullSync(fetched)
            } else {
                val list = identities.toMutableList()
                val idx = list.indexOfFirst { it.id == targetId }
                if (idx >= 0 && !list[idx].hasMixedScoreSources) {
                    list[idx] = list[idx].copy(hasMixedScoreSources = true)
                    identities = list
                    persistIdentities(list)
                }
                ScoreSyncEngine.mergeQuickSync(existing, fetched)
            }
        } else {
            ScoreSyncEngine.applyFullSync(fetched)
        }
        installFetchedRecords(finalRecords, targetId, SyncMode.DIVING_FISH, makeActive = makeActive)
        refreshCNCatalogIfUnknownSongsFound(finalRecords, targetId)
        identities.first { it.id == targetId }
    }

    suspend fun pullFromLXNS(identityId: String? = null) {
        val (targetId, mergeExisting) = withContext(Dispatchers.Main.immediate) {
            ensureCredentialStoreWritable()
            val resolvedId = (identityId ?: activeIdentityId)?.lowercase(Locale.ROOT)
            val identity = resolvedId?.let { id -> identities.firstOrNull { it.id == id } }
            if (resolvedId == null || identity == null || identity.lxns == null) {
                throw MaimaiAPIException.Unauthorized("当前档案未绑定落雪咖啡屋")
            }
            if (identity.selfHosted != null) {
                throw MaimaiAPIException.Unauthorized("已绑定国服直连，请使用快速同步或完整同步")
            }
            val merge = identity.hasMixedScoreSources ||
                (identity.boundChannelCount > 1) ||
                (identity.lastSyncMode != null && identity.lastSyncMode != SyncMode.LXNS)
            resolvedId to merge
        }
        runDetachedRecordSync(identityId = targetId, operation = SyncOperationKind.PULL_LXNS) {
            performPullLXNS(identityId = targetId, mergeExisting = mergeExisting, makeActive = false)
        }
    }

    suspend fun pullFromDivingFish(identityId: String? = null) {
        val (targetId, mergeExisting) = withContext(Dispatchers.Main.immediate) {
            ensureCredentialStoreWritable()
            val resolvedId = (identityId ?: activeIdentityId)?.lowercase(Locale.ROOT)
            val identity = resolvedId?.let { id -> identities.firstOrNull { it.id == id } }
            if (resolvedId == null || identity == null || identity.divingFish == null) {
                throw MaimaiAPIException.Unauthorized("当前档案未绑定水鱼查分器")
            }
            if (identity.selfHosted != null) {
                throw MaimaiAPIException.Unauthorized("已绑定国服直连，请使用快速同步或完整同步")
            }
            val merge = identity.hasMixedScoreSources ||
                (identity.boundChannelCount > 1) ||
                (identity.lastSyncMode != null && identity.lastSyncMode != SyncMode.DIVING_FISH)
            resolvedId to merge
        }
        runDetachedRecordSync(identityId = targetId, operation = SyncOperationKind.PULL_DIVING_FISH) {
            performPullDivingFish(identityId = targetId, mergeExisting = mergeExisting, makeActive = false)
        }
    }

    suspend fun pullMergedProbers(identityId: String? = null): SyncOutcome {
        val targetId = withContext(Dispatchers.Main.immediate) {
            ensureCredentialStoreWritable()
            val resolvedId = (identityId ?: activeIdentityId)?.lowercase(Locale.ROOT)
            val identity = resolvedId?.let { id -> identities.firstOrNull { it.id == id } }
            if (resolvedId == null || identity == null || identity.lxns == null || identity.divingFish == null) {
                throw MaimaiAPIException.Unauthorized("当前档案未同时绑定落雪咖啡屋和水鱼查分器")
            }
            if (identity.selfHosted != null) {
                throw MaimaiAPIException.Unauthorized("已绑定国服直连，请使用快速同步或完整同步")
            }
            resolvedId
        }
        return runDetachedRecordSync(identityId = targetId, operation = SyncOperationKind.PULL_MERGED) {
            val (lxRes, dfRes) = supervisorScope {
                val lxDeferred = async {
                    runCatchingCancellable {
                        withTransientRetry { fetchLXNSRecordsRaw(targetId) }
                    }
                }
                val dfDeferred = async {
                    runCatchingCancellable {
                        withTransientRetry { fetchDivingFishRecordsRaw(targetId) }
                    }
                }
                lxDeferred.await() to dfDeferred.await()
            }

            val nowMs = System.currentTimeMillis()
            val existing = loadRecordsSnapshot(targetId)
            val lxSuccess = lxRes.getOrNull()
            val dfSuccess = dfRes.getOrNull()

            when {
                lxSuccess != null && dfSuccess != null -> {
                    val list = identities.toMutableList()
                    val idx = list.indexOfFirst { it.id == targetId }
                    if (idx >= 0) {
                        val cur = list[idx]
                        val nextLx = cur.lxns?.copy(
                            displayName = lxSuccess.playerName?.takeIf { it.isNotEmpty() } ?: cur.lxns.displayName,
                            friendCode = lxSuccess.friendCode ?: cur.lxns.friendCode
                        )
                        val nextDf = cur.divingFish?.copy(
                            username = dfSuccess.username?.takeIf { it.isNotEmpty() } ?: cur.divingFish.username,
                            nickname = dfSuccess.nickname?.takeIf { it.isNotEmpty() } ?: cur.divingFish.nickname
                        )
                        list[idx] = cur.copy(
                            lxns = nextLx,
                            divingFish = nextDf,
                            hasMixedScoreSources = true,
                            lastSyncMode = SyncMode.LXNS,
                            lastSyncedAtEpochMs = nowMs
                        )
                        identities = list
                        persistIdentities(list)
                    }
                    val withDF = if (existing.isEmpty()) {
                        ScoreSyncEngine.applyFullSync(dfSuccess.records)
                    } else {
                        ScoreSyncEngine.mergeQuickSync(existing, dfSuccess.records)
                    }
                    val finalRecords = ScoreSyncEngine.mergeQuickSync(withDF, lxSuccess.records)
                    installFetchedRecords(finalRecords, targetId, SyncMode.LXNS, makeActive = false)
                    refreshCNCatalogIfUnknownSongsFound(finalRecords, targetId)
                    SyncDiagnostics.record(operation = "pull_merged", message = "双查分器合并同步完成（共 ${finalRecords.size} 条）")
                    SyncOutcome()
                }

                lxSuccess != null -> {
                    val dfErr = dfRes.exceptionOrNull()!!
                    val list = identities.toMutableList()
                    val idx = list.indexOfFirst { it.id == targetId }
                    if (idx >= 0) {
                        val cur = list[idx]
                        val nextLx = cur.lxns?.copy(
                            displayName = lxSuccess.playerName?.takeIf { it.isNotEmpty() } ?: cur.lxns.displayName,
                            friendCode = lxSuccess.friendCode ?: cur.lxns.friendCode
                        )
                        list[idx] = cur.copy(
                            lxns = nextLx,
                            hasMixedScoreSources = true,
                            lastSyncMode = SyncMode.LXNS,
                            lastSyncedAtEpochMs = nowMs
                        )
                        identities = list
                        persistIdentities(list)
                    }
                    val finalRecords = if (existing.isEmpty()) {
                        ScoreSyncEngine.applyFullSync(lxSuccess.records)
                    } else {
                        ScoreSyncEngine.mergeQuickSync(existing, lxSuccess.records)
                    }
                    installFetchedRecords(finalRecords, targetId, SyncMode.LXNS, makeActive = false)
                    refreshCNCatalogIfUnknownSongsFound(finalRecords, targetId)
                    recordDiagnosticFailure(operation = "pull_merged", channel = "divingFish", error = dfErr)
                    val msg = (dfErr as? MaimaiAPIException)?.recoveryMessage ?: (dfErr.message ?: "请求失败")
                    SyncOutcome(warningMessage = "水鱼同步失败（已合并落雪与本地最佳成绩）：$msg")
                }

                dfSuccess != null -> {
                    val lxErr = lxRes.exceptionOrNull()!!
                    val list = identities.toMutableList()
                    val idx = list.indexOfFirst { it.id == targetId }
                    if (idx >= 0) {
                        val cur = list[idx]
                        val nextDf = cur.divingFish?.copy(
                            username = dfSuccess.username?.takeIf { it.isNotEmpty() } ?: cur.divingFish.username,
                            nickname = dfSuccess.nickname?.takeIf { it.isNotEmpty() } ?: cur.divingFish.nickname
                        )
                        list[idx] = cur.copy(
                            divingFish = nextDf,
                            hasMixedScoreSources = true,
                            lastSyncMode = SyncMode.DIVING_FISH,
                            lastSyncedAtEpochMs = nowMs
                        )
                        identities = list
                        persistIdentities(list)
                    }
                    val finalRecords = if (existing.isEmpty()) {
                        ScoreSyncEngine.applyFullSync(dfSuccess.records)
                    } else {
                        ScoreSyncEngine.mergeQuickSync(existing, dfSuccess.records)
                    }
                    installFetchedRecords(finalRecords, targetId, SyncMode.DIVING_FISH, makeActive = false)
                    refreshCNCatalogIfUnknownSongsFound(finalRecords, targetId)
                    recordDiagnosticFailure(operation = "pull_merged", channel = "lxns", error = lxErr)
                    val msg = (lxErr as? MaimaiAPIException)?.recoveryMessage ?: (lxErr.message ?: "请求失败")
                    SyncOutcome(warningMessage = "落雪同步失败（已合并水鱼与本地最佳成绩）：$msg")
                }

                else -> {
                    val lxErr = lxRes.exceptionOrNull()!!
                    val dfErr = dfRes.exceptionOrNull()!!
                    recordDiagnosticFailure(operation = "pull_merged", channel = "lxns", error = lxErr)
                    recordDiagnosticFailure(operation = "pull_merged", channel = "divingFish", error = dfErr)
                    val lxMsg = (lxErr as? MaimaiAPIException)?.recoveryMessage ?: (lxErr.message ?: "请求失败")
                    val dfMsg = (dfErr as? MaimaiAPIException)?.recoveryMessage ?: (dfErr.message ?: "请求失败")
                    throw MaimaiAPIException.HttpError(-1, "落雪与水鱼均同步失败：落雪（$lxMsg）；水鱼（$dfMsg）")
                }
            }
        }
    }

    suspend fun writebackToBoundProbers(identityId: String? = null): WritebackSummary {
        val targetId = withContext(Dispatchers.Main.immediate) {
            ensureCredentialStoreWritable()
            val resolvedId = (identityId ?: activeIdentityId)?.lowercase(Locale.ROOT)
            if (resolvedId == null || identities.none { it.id == resolvedId }) {
                throw MaimaiAPIException.Unauthorized("请先选择要上传的成绩档案")
            }
            resolvedId
        }
        return runDetachedRecordSync(identityId = targetId, operation = SyncOperationKind.WRITEBACK) {
            val localRecords = loadRecordsSnapshot(targetId)
            performWriteback(targetId, localRecords)
        }
    }

    suspend fun syncActiveIdentityDefault(): SyncOutcome = withContext(Dispatchers.Main.immediate) {
        val identity = activeIdentity
            ?: throw MaimaiAPIException.Unauthorized("请先添加成绩档案或绑定账号")
        when (val plan = identity.syncPlan) {
            SyncPlan.CNDirectQuick -> {
                quickSyncSelfHosted(identityId = identity.id, writeback = false)
                SyncOutcome()
            }
            is SyncPlan.SingleProber -> when (plan.channel) {
                SyncChannelKind.LXNS -> {
                    pullFromLXNS(identityId = identity.id)
                    SyncOutcome()
                }
                SyncChannelKind.DIVING_FISH -> {
                    pullFromDivingFish(identityId = identity.id)
                    SyncOutcome()
                }
                else -> throw MaimaiAPIException.Unauthorized("请先绑定账号或查分器")
            }
            SyncPlan.MergedProbers -> pullMergedProbers(identityId = identity.id)
            SyncPlan.None -> throw MaimaiAPIException.Unauthorized("请先绑定账号或查分器")
        }
    }

    // Internal Channel Sync & Writeback Helpers

    private fun isTransientSyncError(error: Throwable): Boolean {
        if (error is CancellationException) return false
        if (error is IOException) return true
        return when (error) {
            is MaimaiAPIException.HttpError -> error.code >= 500 || error.code == -1
            is MaimaiAPIException.BackendError -> error.statusCode >= 500
            else -> false
        }
    }

    private suspend fun <T> withTransientRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (err: Throwable) {
            if (!isTransientSyncError(err)) throw err
            delay(600L)
            block()
        }
    }

    private fun recordDiagnosticFailure(operation: String, channel: String? = null, error: Throwable) {
        if (error is CancellationException) return
        var statusCode: Int? = null
        var errorCode: String? = null
        when (error) {
            is MaimaiAPIException.HttpError -> statusCode = error.code
            is MaimaiAPIException.BackendError -> {
                statusCode = error.statusCode
                errorCode = error.errorCode
            }
            else -> errorCode = error.javaClass.simpleName
        }
        val msg = when (error) {
            is MaimaiAPIException -> error.recoveryMessage
            is SerializationException -> "服务器响应格式异常"
            else -> error.message ?: error.javaClass.simpleName
        }
        SyncDiagnostics.record(
            operation = operation,
            channel = channel,
            statusCode = statusCode,
            errorCode = errorCode,
            message = msg
        )
    }

    private data class DivingFishRawResult(
        val username: String?,
        val nickname: String?,
        val records: List<ProberRecord>
    )

    private data class LXNSRawResult(
        val playerName: String?,
        val friendCode: Long?,
        val records: List<ProberRecord>
    )

    private fun ensureCredentialStoreWritable() {
        if (credentialStoreLoadFailed) {
            throw MaimaiAPIException.Unauthorized("本地安全存储读取异常，已暂停写入以保护档案；请重启应用后重试")
        }
    }

    private suspend fun persistIdentities(snapshot: List<PlayerIdentity>) {
        if (credentialStoreLoadFailed) return
        identityPersistMutex.withLock {
            withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.saveIdentities(appContext, snapshot)
            }
        }
    }

    private suspend fun resolveOrCreateIdentity(
        targetIdentityId: String?,
        matchingSelfHostedId: Long? = null,
        matchingLXNSKey: String? = null,
        matchingDFKey: String? = null,
        defaultLabel: String,
        mutate: (PlayerIdentity) -> PlayerIdentity
    ): String {
        val list = identities.toMutableList()
        val explicitLower = targetIdentityId?.lowercase(Locale.ROOT)
        val explicitIdx = explicitLower?.let { target -> list.indexOfFirst { it.id == target } } ?: -1
        val idx = when {
            explicitIdx >= 0 -> explicitIdx
            matchingSelfHostedId != null -> list.indexOfFirst { it.selfHosted?.externalUserId == matchingSelfHostedId }
            matchingLXNSKey != null -> list.indexOfFirst { it.lxns?.accountKey == matchingLXNSKey }
            matchingDFKey != null -> list.indexOfFirst { it.divingFish?.accountKey == matchingDFKey }
            else -> -1
        }
        val resolvedId: String
        if (idx >= 0) {
            var updated = mutate(list[idx])
            if (updated.label.isEmpty() && defaultLabel.isNotEmpty()) {
                updated = updated.copy(label = defaultLabel)
            }
            list[idx] = updated
            resolvedId = updated.id
        } else {
            val created = mutate(
                PlayerIdentity(
                    id = UUID.randomUUID().toString().lowercase(Locale.ROOT),
                    label = defaultLabel
                )
            )
            list += created
            resolvedId = created.id
        }
        identities = list
        persistIdentities(list)
        return resolvedId
    }

    private suspend fun fetchDivingFishRecordsRaw(identityId: String): DivingFishRawResult {
        val identity = identities.firstOrNull { it.id == identityId }
        val df = identity?.divingFish
            ?: throw MaimaiAPIException.Unauthorized("当前档案未绑定水鱼查分器")
        val secret = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadDivingFishSecret(appContext, identityId)
        }

        val resp: ProberRecordsResponse = when (df.authMethod) {
            ScoreAuthMethod.DIVING_FISH_PASSWORD -> {
                val username = df.username.ifEmpty { secret.username }
                val password = secret.password
                var cookie = secret.cookie
                if (cookie.isEmpty()) {
                    if (username.isEmpty() || password.isEmpty()) {
                        throw MaimaiAPIException.Unauthorized("水鱼登录已过期，请解绑后重新绑定")
                    }
                    cookie = MaimaiAPI.loginDivingFish(username, password)
                }
                val (fetchedResp, usedCookie) = try {
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
                        AccountCredentialStore.saveDivingFishSecret(
                            appContext,
                            identityId,
                            secret.copy(cookie = usedCookie)
                        )
                    }
                }
                fetchedResp
            }

            ScoreAuthMethod.DIVING_FISH_IMPORT_TOKEN -> {
                if (secret.importToken.isEmpty()) {
                    throw MaimaiAPIException.Unauthorized("未找到保存的水鱼导入密钥，请解绑后重新绑定")
                }
                MaimaiAPI.fetchDivingFishRecordsByImportToken(secret.importToken)
            }

            else -> throw MaimaiAPIException.Unauthorized("水鱼认证方式不受支持")
        }

        return DivingFishRawResult(
            username = resp.username,
            nickname = resp.nickname,
            records = resp.records.orEmpty()
        )
    }

    private suspend fun fetchLXNSRecordsRaw(identityId: String): LXNSRawResult {
        val identity = identities.firstOrNull { it.id == identityId }
        val lxns = identity?.lxns
            ?: throw MaimaiAPIException.Unauthorized("当前档案未绑定落雪咖啡屋")
        val secret = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadLXNSSecret(appContext, identityId)
        }

        val playerAndScores: MaimaiAPI.LXNSPlayerAndScores = when (lxns.authMethod) {
            ScoreAuthMethod.LXNS_OAUTH -> {
                val initialToken = validLXNSAccessToken(identityId, forceRefresh = false)
                try {
                    MaimaiAPI.fetchLXNSOAuthPlayerAndScores(initialToken)
                } catch (_: MaimaiAPIException.Unauthorized) {
                    val refreshedToken = validLXNSAccessToken(
                        identityId = identityId,
                        forceRefresh = true,
                        failedAccessToken = initialToken
                    )
                    MaimaiAPI.fetchLXNSOAuthPlayerAndScores(refreshedToken)
                }
            }

            ScoreAuthMethod.LXNS_PERSONAL_TOKEN -> {
                if (secret.personalToken.isEmpty()) {
                    throw MaimaiAPIException.Unauthorized("未找到保存的落雪个人密钥，请解绑后重新绑定")
                }
                MaimaiAPI.fetchLXNSPersonalTokenPlayerAndScores(secret.personalToken)
            }

            else -> throw MaimaiAPIException.Unauthorized("落雪认证方式不受支持")
        }

        val lookup = scoreImportSongLookup()
        val converted = withContext(Dispatchers.Default) {
            LXNSCatalogConverter.convertScores(playerAndScores.scores, lookup)
        }
        return LXNSRawResult(
            playerName = playerAndScores.player?.name,
            friendCode = playerAndScores.player?.friendCode,
            records = converted
        )
    }

    private suspend fun performPullDivingFish(
        identityId: String,
        mergeExisting: Boolean,
        makeActive: Boolean
    ) {
        val fetched = fetchDivingFishRecordsRaw(identityId)

        val nowMs = System.currentTimeMillis()
        val list = identities.toMutableList()
        val idx = list.indexOfFirst { it.id == identityId }
        if (idx >= 0) {
            val cur = list[idx]
            val curDf = cur.divingFish
            val nextDf = curDf?.copy(
                username = fetched.username?.takeIf { it.isNotEmpty() } ?: curDf.username,
                nickname = fetched.nickname?.takeIf { it.isNotEmpty() } ?: curDf.nickname
            )
            list[idx] = cur.copy(
                divingFish = nextDf,
                hasMixedScoreSources = cur.hasMixedScoreSources || mergeExisting,
                lastSyncMode = SyncMode.DIVING_FISH,
                lastSyncedAtEpochMs = nowMs
            )
            identities = list
            persistIdentities(list)
        }

        val finalRecords = if (mergeExisting) {
            val existing = loadRecordsSnapshot(identityId)
            if (existing.isEmpty()) {
                ScoreSyncEngine.applyFullSync(fetched.records)
            } else {
                ScoreSyncEngine.mergeQuickSync(existing, fetched.records)
            }
        } else {
            ScoreSyncEngine.applyFullSync(fetched.records)
        }
        installFetchedRecords(finalRecords, identityId, SyncMode.DIVING_FISH, makeActive = makeActive)
        refreshCNCatalogIfUnknownSongsFound(finalRecords, identityId)
        SyncDiagnostics.record(operation = "pull_df", channel = "divingFish", message = "同步水鱼成绩完成（共 ${finalRecords.size} 条）")
    }

    private suspend fun performPullLXNS(
        identityId: String,
        mergeExisting: Boolean,
        makeActive: Boolean
    ) {
        val fetched = fetchLXNSRecordsRaw(identityId)

        val nowMs = System.currentTimeMillis()
        val list = identities.toMutableList()
        val idx = list.indexOfFirst { it.id == identityId }
        if (idx >= 0) {
            val cur = list[idx]
            val curLxns = cur.lxns
            val nextLxns = curLxns?.copy(
                displayName = fetched.playerName?.takeIf { it.isNotEmpty() } ?: curLxns.displayName,
                friendCode = fetched.friendCode ?: curLxns.friendCode
            )
            list[idx] = cur.copy(
                lxns = nextLxns,
                hasMixedScoreSources = cur.hasMixedScoreSources || mergeExisting,
                lastSyncMode = SyncMode.LXNS,
                lastSyncedAtEpochMs = nowMs
            )
            identities = list
            persistIdentities(list)
        }

        val finalRecords = if (mergeExisting) {
            val existing = loadRecordsSnapshot(identityId)
            if (existing.isEmpty()) {
                ScoreSyncEngine.applyFullSync(fetched.records)
            } else {
                ScoreSyncEngine.mergeQuickSync(existing, fetched.records)
            }
        } else {
            ScoreSyncEngine.applyFullSync(fetched.records)
        }
        installFetchedRecords(finalRecords, identityId, SyncMode.LXNS, makeActive = makeActive)
        refreshCNCatalogIfUnknownSongsFound(finalRecords, identityId)
        SyncDiagnostics.record(operation = "pull_lxns", channel = "lxns", message = "同步落雪成绩完成（共 ${finalRecords.size} 条）")
    }

    private data class WritebackChannelOutcome(
        val uploadedCount: Int,
        val skippedCount: Int,
        val sampleSkippedIds: List<Int>
    )

    private suspend fun performWriteback(
        identityId: String,
        localRecords: List<ProberRecord>
    ): WritebackSummary {
        val identity = identities.firstOrNull { it.id == identityId } ?: return WritebackSummary(emptyList())
        val cnCatalog = pureCNSongsLookup()
        if (cnCatalog.isEmpty()) {
            throw MaimaiAPIException.HttpError(-1, "国服曲库尚未加载完成，请稍后再试")
        }
        val titleLookup = scoreImportSongLookup()
        val results = mutableListOf<WritebackChannelStatus>()

        for (channel in identity.uploadTargets) {
            when (channel) {
                SyncChannelKind.DIVING_FISH -> {
                    val df = identity.divingFish ?: continue
                    val outcome = runCatchingCancellable {
                        withTransientRetry {
                            writebackToDivingFish(
                                identityId = identityId,
                                binding = df,
                                localRecords = localRecords,
                                songsById = titleLookup,
                                cnSongsById = cnCatalog
                            )
                        }
                    }
                    results += outcome.fold(
                        onSuccess = { res ->
                            SyncDiagnostics.record(
                                operation = "writeback",
                                channel = "divingFish",
                                message = if (res.uploadedCount == 0) "水鱼无需更新" else "水鱼上传成功",
                                uploadedCount = res.uploadedCount,
                                skippedCount = res.skippedCount,
                                sampleSkippedIds = res.sampleSkippedIds
                            )
                            WritebackChannelStatus(
                                channel = SyncChannelKind.DIVING_FISH,
                                uploadedCount = res.uploadedCount,
                                skippedCount = res.skippedCount
                            )
                        },
                        onFailure = { err ->
                            recordDiagnosticFailure(operation = "writeback", channel = "divingFish", error = err)
                            WritebackChannelStatus(
                                channel = SyncChannelKind.DIVING_FISH,
                                uploadedCount = 0,
                                skippedCount = 0,
                                errorMessage = (err as? MaimaiAPIException)?.recoveryMessage ?: (err.message ?: "上传失败")
                            )
                        }
                    )
                }

                SyncChannelKind.LXNS -> {
                    val lxns = identity.lxns ?: continue
                    val outcome = runCatchingCancellable {
                        withTransientRetry {
                            writebackToLXNS(
                                identityId = identityId,
                                binding = lxns,
                                localRecords = localRecords,
                                songsById = titleLookup,
                                cnSongsById = cnCatalog
                            )
                        }
                    }
                    results += outcome.fold(
                        onSuccess = { res ->
                            SyncDiagnostics.record(
                                operation = "writeback",
                                channel = "lxns",
                                message = if (res.uploadedCount == 0) "落雪无需更新" else "落雪上传成功",
                                uploadedCount = res.uploadedCount,
                                skippedCount = res.skippedCount,
                                sampleSkippedIds = res.sampleSkippedIds
                            )
                            WritebackChannelStatus(
                                channel = SyncChannelKind.LXNS,
                                uploadedCount = res.uploadedCount,
                                skippedCount = res.skippedCount
                            )
                        },
                        onFailure = { err ->
                            recordDiagnosticFailure(operation = "writeback", channel = "lxns", error = err)
                            WritebackChannelStatus(
                                channel = SyncChannelKind.LXNS,
                                uploadedCount = 0,
                                skippedCount = 0,
                                errorMessage = (err as? MaimaiAPIException)?.recoveryMessage ?: (err.message ?: "上传失败")
                            )
                        }
                    )
                }

                SyncChannelKind.SELF_HOSTED -> Unit
            }
        }

        return WritebackSummary(results = results)
    }

    private suspend fun writebackToDivingFish(
        identityId: String,
        binding: DivingFishBinding,
        localRecords: List<ProberRecord>,
        songsById: Map<Int, SongData>,
        cnSongsById: Map<Int, SongData>
    ): WritebackChannelOutcome {
        var secret = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadDivingFishSecret(appContext, identityId)
        }
        val username = binding.username.ifEmpty { secret.username }
        val password = secret.password
        var cookie = secret.cookie

        val cloudResp: ProberRecordsResponse = when (binding.authMethod) {
            ScoreAuthMethod.DIVING_FISH_PASSWORD -> {
                if (cookie.isEmpty()) {
                    if (username.isEmpty() || password.isEmpty()) {
                        throw MaimaiAPIException.Unauthorized("水鱼凭据已过期，请解绑后重新绑定")
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
                    secret = secret.copy(cookie = usedCookie)
                    withContext(NonCancellable + Dispatchers.IO) {
                        AccountCredentialStore.saveDivingFishSecret(appContext, identityId, secret)
                    }
                }
                resp
            }

            ScoreAuthMethod.DIVING_FISH_IMPORT_TOKEN -> {
                if (secret.importToken.isEmpty()) {
                    throw MaimaiAPIException.Unauthorized("缺少水鱼成绩导入密钥")
                }
                MaimaiAPI.fetchDivingFishRecordsByImportToken(secret.importToken)
            }

            else -> return WritebackChannelOutcome(0, 0, emptyList())
        }

        val cloudRecords = cloudResp.records.orEmpty()
        val needsTitleMap = ScoreSyncEngine.needsDivingFishTitleMap(
            localRecords = localRecords,
            cloudRecords = cloudRecords,
            cnSongsById = cnSongsById
        )
        if (needsTitleMap && divingFishMusicTitles.isEmpty()) {
            val fetchedTitles = runCatchingCancellable { MaimaiAPI.fetchDivingFishMusicTitles() }.getOrNull()
            if (fetchedTitles.isNullOrEmpty()) {
                throw MaimaiAPIException.HttpError(-1, "获取水鱼曲名列表失败，已取消本次上传以避免误匹配曲目")
            }
            divingFishMusicTitles = fetchedTitles
        }

        val result = ScoreSyncEngine.buildDivingFishWriteback(
            localRecords = localRecords,
            cloudRecords = cloudRecords,
            songsById = songsById,
            cnSongsById = cnSongsById,
            musicTitlesById = divingFishMusicTitles
        )
        val uploads = result.uploads
        if (uploads.isEmpty()) {
            return WritebackChannelOutcome(0, result.skippedDeletedCount, result.sampleSkippedSongIds)
        }

        when (binding.authMethod) {
            ScoreAuthMethod.DIVING_FISH_PASSWORD -> {
                val usedCookie = try {
                    MaimaiAPI.uploadDivingFishRecordsByCookie(uploads, secret.cookie)
                    secret.cookie
                } catch (err: Throwable) {
                    if (MaimaiAPI.shouldRetryUploadLogin(err) && username.isNotEmpty() && password.isNotEmpty()) {
                        val freshCookie = MaimaiAPI.loginDivingFish(username, password)
                        MaimaiAPI.uploadDivingFishRecordsByCookie(uploads, freshCookie)
                        freshCookie
                    } else {
                        throw err
                    }
                }
                if (usedCookie != secret.cookie) {
                    withContext(NonCancellable + Dispatchers.IO) {
                        AccountCredentialStore.saveDivingFishSecret(
                            appContext,
                            identityId,
                            secret.copy(cookie = usedCookie)
                        )
                    }
                }
            }

            ScoreAuthMethod.DIVING_FISH_IMPORT_TOKEN -> {
                MaimaiAPI.uploadDivingFishRecordsByImportToken(uploads, secret.importToken)
            }

            else -> Unit
        }
        return WritebackChannelOutcome(uploads.size, result.skippedDeletedCount, result.sampleSkippedSongIds)
    }

    private suspend fun writebackToLXNS(
        identityId: String,
        binding: LXNSBinding,
        localRecords: List<ProberRecord>,
        songsById: Map<Int, SongData>,
        cnSongsById: Map<Int, SongData>
    ): WritebackChannelOutcome {
        val secret = withContext(Dispatchers.IO) {
            AccountCredentialStore.loadLXNSSecret(appContext, identityId)
        }
        val lxScores: List<LXNSScore>
        var resolvedBearer: String? = null

        when (binding.authMethod) {
            ScoreAuthMethod.LXNS_OAUTH -> {
                val initialToken = validLXNSAccessToken(identityId, forceRefresh = false)
                val playerAndScores = try {
                    resolvedBearer = initialToken
                    MaimaiAPI.fetchLXNSOAuthPlayerAndScores(initialToken)
                } catch (_: MaimaiAPIException.Unauthorized) {
                    val refreshed = validLXNSAccessToken(
                        identityId = identityId,
                        forceRefresh = true,
                        failedAccessToken = initialToken
                    )
                    resolvedBearer = refreshed
                    MaimaiAPI.fetchLXNSOAuthPlayerAndScores(refreshed)
                }
                lxScores = playerAndScores.scores
            }

            ScoreAuthMethod.LXNS_PERSONAL_TOKEN -> {
                if (secret.personalToken.isEmpty()) {
                    throw MaimaiAPIException.Unauthorized("缺少落雪个人密钥")
                }
                lxScores = MaimaiAPI.fetchLXNSPersonalTokenPlayerAndScores(secret.personalToken).scores
            }

            else -> return WritebackChannelOutcome(0, 0, emptyList())
        }

        val cloudRecords = withContext(Dispatchers.Default) {
            LXNSCatalogConverter.convertScores(lxScores, songsById)
        }
        val result = ScoreSyncEngine.buildLXNSWriteback(
            localRecords = localRecords,
            cloudRecords = cloudRecords,
            cnSongsById = cnSongsById
        )
        val uploads = result.uploads
        if (uploads.isEmpty()) {
            return WritebackChannelOutcome(0, result.skippedDeletedCount, result.sampleSkippedSongIds)
        }

        try {
            when (binding.authMethod) {
                ScoreAuthMethod.LXNS_OAUTH -> {
                    val bearer = resolvedBearer ?: validLXNSAccessToken(identityId, forceRefresh = false)
                    try {
                        MaimaiAPI.uploadLXNSScoresByBearer(uploads, bearer)
                    } catch (_: MaimaiAPIException.Unauthorized) {
                        val refreshed = validLXNSAccessToken(
                            identityId = identityId,
                            forceRefresh = true,
                            failedAccessToken = bearer
                        )
                        MaimaiAPI.uploadLXNSScoresByBearer(uploads, refreshed)
                    }
                }

                ScoreAuthMethod.LXNS_PERSONAL_TOKEN -> {
                    MaimaiAPI.uploadLXNSScoresByPersonalToken(uploads, secret.personalToken)
                }

                else -> Unit
            }
        } catch (err: MaimaiAPIException.HttpError) {
            if (err.code == 403) {
                val list = identities.toMutableList()
                val idx = list.indexOfFirst { it.id == identityId.lowercase(Locale.ROOT) }
                if (idx >= 0) {
                    val cur = list[idx]
                    cur.lxns?.let { lx ->
                        list[idx] = cur.copy(lxns = lx.copy(hasWriteScope = false))
                        identities = list
                        persistIdentities(list)
                    }
                }
            }
            throw err
        }
        return WritebackChannelOutcome(uploads.size, result.skippedDeletedCount, result.sampleSkippedSongIds)
    }

    /**
     * Single-flight per-identity `Mutex` serialization for LXNS OAuth `refresh_token` rotation.
     * Immediately persists rotated `refresh_token` under `NonCancellable` before releasing the lock.
     */
    private suspend fun validLXNSAccessToken(
        identityId: String,
        forceRefresh: Boolean,
        failedAccessToken: String? = null
    ): String {
        val lower = identityId.lowercase(Locale.ROOT)
        val mutex = tokenRefreshMutexes.getOrPut(lower) { Mutex() }
        return mutex.withLock {
            val currentSecret = withContext(Dispatchers.IO) {
                AccountCredentialStore.loadLXNSSecret(appContext, lower)
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
                throw MaimaiAPIException.Unauthorized("落雪授权已过期，请解绑后重新绑定")
            }

            val refreshed = MaimaiAPI.refreshLXNSToken(currentSecret.refreshToken)
            val stillExists = withContext(Dispatchers.Main.immediate) {
                identities.any { it.id == lower && it.lxns != null }
            }
            if (!stillExists) {
                throw MaimaiAPIException.Unauthorized("该落雪绑定已被移除")
            }
            val rotatedAtSec = System.currentTimeMillis() / 1000L
            val updatedSecret = currentSecret.copy(
                accessToken = refreshed.accessToken,
                refreshToken = refreshed.refreshToken?.takeIf { it.isNotEmpty() } ?: currentSecret.refreshToken,
                accessTokenExpiresAtEpochSec = rotatedAtSec + refreshed.expiresIn,
                grantedScopes = refreshed.scope?.takeIf { it.isNotEmpty() } ?: currentSecret.grantedScopes
            )
            val persisted = withContext(NonCancellable + Dispatchers.IO) {
                AccountCredentialStore.saveLXNSSecret(appContext, lower, updatedSecret) ||
                    AccountCredentialStore.saveLXNSSecret(appContext, lower, updatedSecret)
            }
            if (!persisted) {
                throw MaimaiAPIException.Unauthorized("落雪授权令牌保存失败，请解绑后重新绑定")
            }
            refreshed.accessToken
        }
    }

    private fun pureCNSongsLookup(): Map<Int, SongData> = cnSongsById

    private fun scoreImportSongLookup(): Map<Int, SongData> {
        val merged = HashMap<Int, SongData>(songsById.size + cnSongsById.size)
        merged.putAll(songsById)
        merged.putAll(cnSongsById)
        return merged
    }

    private suspend fun loadRecordsSnapshot(identityId: String): List<ProberRecord> {
        val lower = identityId.lowercase(Locale.ROOT)
        if (lower == activeIdentityId && recordsOwnerIdentityId == lower && records.isNotEmpty()) {
            return records
        }
        val file = AccountCredentialStore.recordsFile(appFilesDir, lower)
        return withContext(Dispatchers.IO) {
            if (!file.exists()) return@withContext emptyList()
            runCatching {
                val decoded = MaimaiAPI.json.decodeFromString<List<ProberRecord>>(file.readText(Charsets.UTF_8))
                ScoreSyncEngine.normalizeCachedRecords(decoded)
            }.getOrDefault(emptyList())
        }
    }

    private suspend fun installFetchedRecords(
        fetched: List<ProberRecord>,
        identityId: String,
        syncMode: SyncMode,
        makeActive: Boolean
    ) {
        val lower = identityId.lowercase(Locale.ROOT)
        val file = AccountCredentialStore.recordsFile(appFilesDir, lower)
        val bytes = MaimaiAPI.json.encodeToString(fetched).toByteArray(Charsets.UTF_8)
        withContext(NonCancellable + Dispatchers.IO) {
            writeBytesAtomically(file, bytes)
        }
        val historyLookup = cnSongsById
        if (historyLookup.isNotEmpty()) {
            storeScope.launch(Dispatchers.IO) {
                runCatchingCancellable {
                    syncHistoryStore.appendSnapshot(
                        identityId = lower,
                        syncMode = syncMode,
                        records = fetched,
                        songsById = historyLookup
                    )
                }
            }
        }
        if (makeActive && activeIdentityId != lower) {
            isRestoringCachedRecords = false
            setActiveIdentityInternal(lower, restoreFromCache = false)
        }
        if (activeIdentityId == lower) {
            recordsEpoch += 1
            isRestoringCachedRecords = false
            setRecordsSnapshot(fetched, ownerIdentityId = lower)
            prefetchBest50Covers()
        }
    }

    /** Triggers at most one 12h-throttled LXNS catalog refresh when newly released CN songs appear in synced records. */
    private suspend fun refreshCNCatalogIfUnknownSongsFound(
        syncedRecords: List<ProberRecord>,
        identityId: String
    ) {
        if (syncedRecords.isEmpty() || isFetchingCNSongs) return
        if (syncedRecords.none { it.songId > 0 && !cnSongsById.containsKey(it.songId) }) return

        val now = System.currentTimeMillis()
        val last = lastUnknownSongCatalogRefreshMs
        if (last != null && now - last < SyncPolicy.UNKNOWN_SONG_CATALOG_COOLDOWN_MS) return

        val (fetchedSongs, _) = performLXNSCatalogSync()
        if (fetchedSongs.isNullOrEmpty()) return
        lastUnknownSongCatalogRefreshMs = now

        val cnSnapshot = storeCNBaseline(fetchedSongs)
        if (serverRegion == MaimaiConstants.ServerRegion.CN) {
            applySnapshot(cnSnapshot, epoch = regionEpoch)
        } else {
            zetarakuCatalog?.let { applyZetarakuCatalog(it, region = serverRegion, epoch = regionEpoch) }
        }

        val normalizedIdentityId = identityId.lowercase(Locale.ROOT)
        if (activeIdentityId == normalizedIdentityId) {
            val lookup = scoreImportSongLookup()
            val reprojected = records.map { rec ->
                lookup[rec.songId]?.let { rec.projected(onto = it, region = serverRegion) } ?: rec
            }
            setRecordsSnapshot(reprojected, ownerIdentityId = normalizedIdentityId)
        }
    }

    private fun setActiveIdentityInternal(identityId: String?, restoreFromCache: Boolean) {
        val previousId = activeIdentityId
        val lower = identityId?.lowercase(Locale.ROOT)
        activeIdentityId = lower
        if (lower != null) {
            prefs.edit().putString(PreferenceKeys.activeIdentityId, lower).apply()
        } else {
            prefs.edit().remove(PreferenceKeys.activeIdentityId).apply()
        }
        if (restoreFromCache) {
            restoreCachedRecords()
        } else if (lower != previousId) {
            recordsEpoch += 1
            isRestoringCachedRecords = false
            setRecordsSnapshot(emptyList(), ownerIdentityId = null)
        }
    }

    private fun restoreCachedRecords() {
        val targetId = activeIdentityId
        recordsEpoch += 1
        val epoch = recordsEpoch
        isRestoringCachedRecords = targetId != null
        setRecordsSnapshot(emptyList(), ownerIdentityId = null)
        if (targetId == null) return

        val file = AccountCredentialStore.recordsFile(appFilesDir, targetId)
        storeScope.launch {
            val cached = withContext(Dispatchers.IO) {
                if (!file.exists()) return@withContext null
                runCatching {
                    val decoded = MaimaiAPI.json.decodeFromString<List<ProberRecord>>(file.readText(Charsets.UTF_8))
                    ScoreSyncEngine.normalizeCachedRecords(decoded)
                }.getOrNull()
            }
            if (activeIdentityId != targetId || recordsEpoch != epoch) return@launch
            isRestoringCachedRecords = false
            setRecordsSnapshot(cached.orEmpty(), ownerIdentityId = targetId)
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

    fun songById(rawId: String): SongData? {
        val numeric = rawId.toIntOrNull()
        if (numeric != null) {
            songsById[numeric]?.let { return it }
        }
        return songs.firstOrNull { it.id == rawId }
    }

    fun record(songId: Int, difficultyIndex: Int): ProberRecord? {
        if (songId <= 0) return null
        recordsByKey["${songId}_$difficultyIndex"]?.let { return it }
        if (difficultyIndex == 11) {
            return recordsByKey["${songId}_10"]
        }
        return null
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
                candidate.isDisabled != true &&
                candidate.title == song.title &&
                candidate.isDX != song.isDX
            ) {
                return candidate
            }
        }
        return songs.firstOrNull { candidate ->
            !candidate.isUtage &&
                candidate.isDisabled != true &&
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
                song.chartArrayIndex(rec.levelIndex) == null ||
                song.basicInfo.isNew != isNewVersion
            ) {
                return@mapNotNull null
            }
            rec.projected(onto = song, region = region)
        }.sortedWith(recordComparator)
    }

    fun best35AndBest15(): Best50Summary {
        val region = serverRegion
        val oldList = ArrayList<ProberRecord>()
        val newList = ArrayList<ProberRecord>()

        for (rec in records) {
            if (rec.type == "utage" || rec.levelIndex >= 10 || rec.songId >= 100_000) {
                continue
            }
            val song = songsById[rec.songId] ?: continue
            if (song.isUtage || song.isDisabled == true || song.chartArrayIndex(rec.levelIndex) == null) continue
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
            b15 = newList.take(15)
        )
    }

    // Internal Utilities

    private fun readOptionalEpochMs(key: String): Long? {
        if (!prefs.contains(key)) return null
        val raw = runCatching { prefs.getLong(key, -1L) }.getOrDefault(-1L)
        if (raw <= 0L) return null
        return if (raw < 100_000_000_000L) raw * 1000L else raw
    }

    private fun writeOptionalString(key: String, value: String?) {
        val editor = prefs.edit()
        if (value == null) {
            editor.remove(key)
        } else {
            editor.putString(key, value)
        }
        editor.apply()
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

        fun migrateLegacyPlateScopeId(legacyIndex: Int, scopes: List<PlateCheckScope>): String? {
            val legacyOrder = scopes.filter { it.id != "覇者" }
            return if (legacyIndex in legacyOrder.indices) {
                legacyOrder[legacyIndex].id
            } else {
                scopes.firstOrNull()?.id
            }
        }

        private val recordComparator = Comparator<ProberRecord> { lhs, rhs ->
            val raCmp = rhs.computedRating.compareTo(lhs.computedRating)
            if (raCmp != 0) return@Comparator raCmp
            val achiCmp = rhs.achievements.compareTo(lhs.achievements)
            if (achiCmp != 0) return@Comparator achiCmp
            val songCmp = lhs.songId.compareTo(rhs.songId)
            if (songCmp != 0) return@Comparator songCmp
            lhs.levelIndex.compareTo(rhs.levelIndex)
        }

        private inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
            return try {
                Result.success(block())
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                Result.failure(t)
            }
        }

        private fun buildSnapshotSync(list: List<SongData>): SongSnapshot {
            val enriched = SongData.mergingCounterpartAliases(list)
            val sorted = enriched.sortedWith(SongData.defaultSortComparator)
            val seenIds = HashSet<String>(sorted.size)
            val ordered = sorted.filter { seenIds.add(it.id) }
            val map = HashMap<Int, SongData>(ordered.size)
            val byLevel = HashMap<String, MutableList<LevelCheckChartItem>>()
            for (song in ordered) {
                if (song.numericId > 0) {
                    map[song.numericId] = song
                }
                if (!song.isUtage && song.isDisabled != true) {
                    for (chart in song.resolvedCharts) {
                        byLevel.getOrPut(chart.level) { mutableListOf() }
                            .add(LevelCheckChartItem(song = song, chart = chart))
                    }
                }
            }
            val levelBuckets = HashMap<String, List<LevelCheckDSBucket>>(byLevel.size)
            for ((level, items) in byLevel) {
                val grouped = items.groupBy { (it.chart.ds * 10.0).roundToInt() }
                levelBuckets[level] = grouped.keys
                    .sortedDescending()
                    .map { tenths ->
                        LevelCheckDSBucket(
                            ds = tenths / 10.0,
                            items = grouped[tenths].orEmpty()
                        )
                    }
            }
            return SongSnapshot(
                songs = ordered,
                songsById = map,
                levelCheckBuckets = levelBuckets,
                maxNotes = MaxNotesStats.compute(ordered)
            )
        }

        private fun writeBytesAtomically(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(bytes)
                    out.fd.sync()
                }
                if (!tmp.renameTo(target)) {
                    FileOutputStream(target).use { out ->
                        out.write(bytes)
                        out.fd.sync()
                    }
                }
            } finally {
                if (tmp.exists()) {
                    tmp.delete()
                }
            }
        }

        private fun formatSyncTimestamp(epochMs: Long): String =
            Instant.ofEpochMilli(epochMs)
                .atZone(ZoneId.systemDefault())
                .format(SYNC_TIME_FORMATTER)
    }
}
