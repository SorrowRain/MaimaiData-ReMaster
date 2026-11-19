package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppEmptyState
import dev.sorrowrain.maimaidata.components.AppLoadingState
import dev.sorrowrain.maimaidata.components.AppMenuCapsule
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CheckDisplayMode
import dev.sorrowrain.maimaidata.components.ChecklistSongGrid
import dev.sorrowrain.maimaidata.components.ChecklistSongTile
import dev.sorrowrain.maimaidata.components.ChecklistStatsHeader
import dev.sorrowrain.maimaidata.models.ChecklistStats
import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.PlateCheckScope
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlin.math.max

private data class PlateLevelGroup(
    val level: String,
    val songs: List<SongData>
)

@Composable
fun VersionCheckScreen(
    onBack: () -> Unit,
    onOpenSong: (songId: String, initialDifficultyIndex: Int?) -> Unit
) {
    val store = LocalDataStore.current

    var displayMode by rememberSaveable { mutableStateOf(CheckDisplayMode.SSS) }
    var onlyUnfinished by rememberSaveable { mutableStateOf(false) }
    var selectedDifficulty by rememberSaveable { mutableStateOf(DifficultyType.MASTER) }
    var showPlateMenu by remember { mutableStateOf(false) }
    var showDifficultyMenu by remember { mutableStateOf(false) }

    val scopes = remember(store.serverRegion, store.catalogRevision, store.plateCatalog, store.songs) {
        store.activePlateScopes
    }

    val clampedScopeIndex = if (scopes.isEmpty()) {
        0
    } else {
        store.activeVersionCheckIndex.coerceIn(0, max(0, scopes.lastIndex))
    }

    val activeScope = remember(scopes, clampedScopeIndex) {
        scopes.getOrElse(clampedScopeIndex) { PlateCheckScope.fallback }
    }

    val availableDifficulties = remember(activeScope.supportsRemaster) {
        if (activeScope.supportsRemaster) {
            listOf(
                DifficultyType.BASIC,
                DifficultyType.ADVANCED,
                DifficultyType.EXPERT,
                DifficultyType.MASTER,
                DifficultyType.REMASTER
            )
        } else {
            listOf(
                DifficultyType.BASIC,
                DifficultyType.ADVANCED,
                DifficultyType.EXPERT,
                DifficultyType.MASTER
            )
        }
    }

    val effectiveDifficulty = if (selectedDifficulty in availableDifficulties) {
        selectedDifficulty
    } else {
        DifficultyType.MASTER
    }

    LaunchedEffect(availableDifficulties, selectedDifficulty) {
        if (selectedDifficulty !in availableDifficulties) {
            selectedDifficulty = DifficultyType.MASTER
        }
    }

    val diffIdx = effectiveDifficulty.rawValue
    val diffTint = effectiveDifficulty.color

    val groupedSongs = remember(store.songs, store.catalogRevision, activeScope, effectiveDifficulty) {
        val filtered = store.songs.filter { activeScope.contains(it, effectiveDifficulty) }
        val sorted = filtered.sortedByDescending { it.ds.getOrElse(diffIdx) { 0.0 } }
        val byLevel = sorted.groupBy { it.level.getOrElse(diffIdx) { "?" } }
        byLevel.keys
            .sortedByDescending(MaimaiConstants::levelSortOrder)
            .map { level ->
                PlateLevelGroup(
                    level = level,
                    songs = byLevel[level].orEmpty()
                )
            }
    }

    val allSongs = remember(groupedSongs) {
        groupedSongs.flatMap { it.songs }
    }

    val stats = remember(allSongs, store.records, diffIdx) {
        val matched = allSongs.mapNotNull { store.record(it.numericId, diffIdx) }
        ChecklistStats(records = matched, totalCount = allSongs.size)
    }

    val visibleGroups = remember(groupedSongs, onlyUnfinished, displayMode, store.records, diffIdx) {
        if (!onlyUnfinished) {
            groupedSongs
        } else {
            groupedSongs.mapNotNull { group ->
                val remaining = group.songs.filter { song ->
                    !displayMode.isCompleted(store.record(song.numericId, diffIdx))
                }
                if (remaining.isEmpty()) null else PlateLevelGroup(group.level, remaining)
            }
        }
    }

    AppPageBackground(tint = diffTint) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "牌子进度表",
                    onBack = onBack
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(
                    horizontal = AppTheme.pagePadding,
                    vertical = AppTheme.Space.m
                ),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                // 1. Single Horizontal Row Plate Scope Card
                item(key = "plate_scope_header") {
                    AppCard(
                        padding = AppTheme.cardPadding,
                        tint = diffTint
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            val versionResId = MaimaiConstants.drawableResId(activeScope.versionAsset)
                            Box(
                                modifier = Modifier.size(width = 92.dp, height = 36.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (versionResId != null) {
                                    Image(
                                        painter = painterResource(versionResId),
                                        contentDescription = activeScope.title,
                                        contentScale = ContentScale.Fit,
                                        alignment = Alignment.Center,
                                        modifier = Modifier.size(width = 92.dp, height = 36.dp)
                                    )
                                }
                            }

                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = activeScope.title,
                                    style = AppTheme.AppTypography.rowTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "共 ${allSongs.size} 首歌曲",
                                    style = AppTheme.AppTypography.caption,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box {
                                AppMenuCapsule(
                                    title = "切换牌子",
                                    onClick = { showPlateMenu = true }
                                )

                                DropdownMenu(
                                    expanded = showPlateMenu,
                                    onDismissRequest = { showPlateMenu = false },
                                    modifier = Modifier.heightIn(max = 380.dp)
                                ) {
                                    scopes.forEachIndexed { idx, item ->
                                        val isSelected = idx == clampedScopeIndex
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = item.title,
                                                    style = AppTheme.AppTypography.body,
                                                    color = if (isSelected) {
                                                        AppTheme.accentText
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    }
                                                )
                                            },
                                            trailingIcon = if (isSelected) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = AppTheme.accentText,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            onClick = {
                                                store.activeVersionCheckIndex = idx
                                                showPlateMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Completion Stats Header with Difficulty Switcher in leadingAccessory
                item(key = "plate_stats_header") {
                    ChecklistStatsHeader(
                        stats = stats,
                        displayMode = displayMode,
                        onDisplayModeChange = { displayMode = it },
                        onlyUnfinished = onlyUnfinished,
                        onOnlyUnfinishedChange = { onlyUnfinished = it },
                        leadingAccessory = {
                            Box {
                                AppMenuCapsule(
                                    title = effectiveDifficulty.shortName,
                                    tint = diffTint,
                                    onClick = { showDifficultyMenu = true }
                                )

                                DropdownMenu(
                                    expanded = showDifficultyMenu,
                                    onDismissRequest = { showDifficultyMenu = false }
                                ) {
                                    availableDifficulties.forEach { diff ->
                                        val isSelected = diff == effectiveDifficulty
                                        DropdownMenuItem(
                                            text = {
                                                Text(
                                                    text = diff.shortName,
                                                    style = AppTheme.AppTypography.body,
                                                    color = if (isSelected) {
                                                        AppTheme.accentText
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    }
                                                )
                                            },
                                            trailingIcon = if (isSelected) {
                                                {
                                                    Icon(
                                                        imageVector = Icons.Rounded.Check,
                                                        contentDescription = null,
                                                        tint = AppTheme.accentText,
                                                        modifier = Modifier.size(18.dp)
                                                    )
                                                }
                                            } else {
                                                null
                                            },
                                            onClick = {
                                                selectedDifficulty = diff
                                                showDifficultyMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    )
                }

                // 3. Syncing / Empty / Level-Grouped Song Grids
                when {
                    store.isSyncingSongs && store.songs.isEmpty() -> {
                        item(key = "plate_syncing_state") {
                            AppLoadingState(message = "正在同步${store.serverRegion.shortName}曲库…")
                        }
                    }

                    store.songs.isEmpty() -> {
                        item(key = "plate_empty_catalog") {
                            AppEmptyState(
                                icon = Icons.Rounded.Sync,
                                title = "暂无${store.serverRegion.shortName}曲库数据",
                                description = "请前往「设置与数据 → 数据同步与存储」点击检查更新"
                            )
                        }
                    }

                    visibleGroups.isEmpty() && allSongs.isNotEmpty() -> {
                        item(key = "plate_all_completed") {
                            AppEmptyState(
                                icon = Icons.Rounded.CheckCircle,
                                title = "已全部达成 ${displayMode.title}",
                                description = "当前筛选下全部谱面均已达成该目标",
                                iconTint = AppTheme.accentText
                            )
                        }
                    }

                    else -> {
                        items(
                            items = visibleGroups,
                            key = { group -> "${activeScope.id}_${effectiveDifficulty.rawValue}_${group.level}" }
                        ) { group ->
                            AppSectionCard(
                                title = "Level ${group.level}",
                                trailing = {
                                    AppCapsuleLabel(
                                        text = "${group.songs.size} 首",
                                        tint = diffTint
                                    )
                                }
                            ) {
                                ChecklistSongGrid(
                                    items = group.songs,
                                    itemKey = { it.id }
                                ) { song ->
                                    ChecklistSongTile(
                                        song = song,
                                        record = store.record(song.numericId, diffIdx),
                                        borderColor = diffTint,
                                        displayMode = displayMode,
                                        region = store.serverRegion,
                                        onClick = { onOpenSong(song.id, diffIdx) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
