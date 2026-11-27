package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
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

private data class PlateLevelGroup(
    val level: String,
    val songs: List<SongData>
)

private data class PlateScopeCache(
    val songsByDifficulty: Map<DifficultyType, List<SongData>>,
    val groupedByDifficulty: Map<DifficultyType, List<PlateLevelGroup>>
)

@Composable
fun VersionCheckScreen(
    onBack: () -> Unit,
    onOpenSong: (songId: String, initialDifficultyIndex: Int?) -> Unit
) {
    val store = LocalDataStore.current
    var showPlateMenu by remember { mutableStateOf(false) }
    var showDifficultyMenu by remember { mutableStateOf(false) }

    val scopes = remember(store.serverRegion, store.catalogRevision, store.plateCatalog, store.songs) {
        store.activePlateScopes
    }

    val savedScopeId = store.activeVersionCheckScopeId
    val activeScope = remember(scopes, savedScopeId) {
        if (savedScopeId != null) {
            scopes.firstOrNull { it.id == savedScopeId } ?: scopes.firstOrNull() ?: PlateCheckScope.fallback
        } else {
            scopes.firstOrNull() ?: PlateCheckScope.fallback
        }
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

    val effectiveDifficulty = if (store.versionCheckDifficulty in availableDifficulties) {
        store.versionCheckDifficulty
    } else {
        DifficultyType.MASTER
    }

    LaunchedEffect(availableDifficulties) {
        if (store.versionCheckDifficulty !in availableDifficulties) {
            store.versionCheckDifficulty = DifficultyType.MASTER
        }
    }

    val effectiveMode = if (activeScope.goal == PlateCheckScope.Goal.CLEAR) {
        CheckDisplayMode.CLEAR
    } else if (store.checklistDisplayMode == CheckDisplayMode.CLEAR) {
        CheckDisplayMode.SSS
    } else {
        store.checklistDisplayMode
    }

    val scopeCache = remember(store.songs, store.catalogRevision, activeScope, availableDifficulties) {
        val songsByDiff = HashMap<DifficultyType, List<SongData>>(availableDifficulties.size)
        val groupedByDiff = HashMap<DifficultyType, List<PlateLevelGroup>>(availableDifficulties.size)

        for (diff in availableDifficulties) {
            val idx = diff.rawValue
            val filtered = store.songs.filter { activeScope.contains(it, diff) }
            val sorted = filtered.sortedByDescending { it.ds.getOrElse(idx) { 0.0 } }
            songsByDiff[diff] = sorted

            val byLevel = sorted.groupBy { it.level.getOrElse(idx) { "?" } }
            groupedByDiff[diff] = byLevel.keys
                .sortedByDescending(MaimaiConstants::levelSortOrder)
                .map { level ->
                    PlateLevelGroup(
                        level = level,
                        songs = byLevel[level].orEmpty()
                    )
                }
        }

        PlateScopeCache(
            songsByDifficulty = songsByDiff,
            groupedByDifficulty = groupedByDiff
        )
    }

    val diffIdx = effectiveDifficulty.rawValue
    val diffTint = effectiveDifficulty.color
    val activeSongs = scopeCache.songsByDifficulty[effectiveDifficulty].orEmpty()
    val activeGroups = scopeCache.groupedByDifficulty[effectiveDifficulty].orEmpty()

    val activeStats = remember(activeSongs, store.records, diffIdx) {
        val matched = activeSongs.mapNotNull { store.record(it.numericId, diffIdx) }
        ChecklistStats(
            records = matched,
            totalCount = activeSongs.size
        )
    }

    val visibleGroups = remember(activeGroups, store.checklistOnlyUnfinished, effectiveMode, store.records, diffIdx) {
        if (!store.checklistOnlyUnfinished) {
            activeGroups
        } else {
            activeGroups.mapNotNull { group ->
                val remaining = group.songs.filter { song ->
                    !effectiveMode.isCompleted(store.record(song.numericId, diffIdx))
                }
                if (remaining.isEmpty()) null else PlateLevelGroup(group.level, remaining)
            }
        }
    }

    val countSummary = "共 ${activeSongs.size} 首"
    val footnoteText = if (!activeScope.hasJiangBadge && activeScope.goal == PlateCheckScope.Goal.STANDARD) {
        "真代无「将」牌，SSS 仅作统计"
    } else {
        null
    }

    AppPageBackground {
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
                // 1. Plate Scope Card (single-row balanced layout, stacks at large font scale)
                item(key = "plate_scope_header") {
                    val isLargeFont = LocalDensity.current.fontScale >= 1.2f
                    val versionResId = MaimaiConstants.drawableResId(activeScope.versionAsset)

                    AppCard(
                        padding = AppTheme.cardPadding
                    ) {
                        val plateMenuButton: @Composable () -> Unit = {
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
                                    scopes.forEach { item ->
                                        val isSelected = item.id == activeScope.id
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
                                                store.activeVersionCheckScopeId = item.id
                                                showPlateMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (isLargeFont) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                                ) {
                                    Box(
                                        modifier = Modifier.size(width = 76.dp, height = 34.dp),
                                        contentAlignment = Alignment.CenterStart
                                    ) {
                                        if (versionResId != null) {
                                            Image(
                                                painter = painterResource(versionResId),
                                                contentDescription = activeScope.title,
                                                contentScale = ContentScale.Fit,
                                                alignment = Alignment.CenterStart,
                                                modifier = Modifier.size(width = 76.dp, height = 34.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.weight(1f))
                                    plateMenuButton()
                                }

                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = activeScope.title,
                                        style = AppTheme.AppTypography.rowTitle,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = countSummary,
                                        style = AppTheme.AppTypography.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                            ) {
                                Box(
                                    modifier = Modifier.size(width = 76.dp, height = 34.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (versionResId != null) {
                                        Image(
                                            painter = painterResource(versionResId),
                                            contentDescription = activeScope.title,
                                            contentScale = ContentScale.Fit,
                                            alignment = Alignment.Center,
                                            modifier = Modifier.size(width = 76.dp, height = 34.dp)
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
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = countSummary,
                                        style = AppTheme.AppTypography.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                plateMenuButton()
                            }
                        }
                    }
                }

                // 2. Completion Stats + Difficulty Switcher
                item(key = "plate_stats_header") {
                    ChecklistStatsHeader(
                        stats = activeStats,
                        displayMode = effectiveMode,
                        onDisplayModeChange = { next ->
                            if (activeScope.goal != PlateCheckScope.Goal.CLEAR) {
                                store.checklistDisplayMode = next
                            }
                        },
                        onlyUnfinished = store.checklistOnlyUnfinished,
                        onOnlyUnfinishedChange = { store.checklistOnlyUnfinished = it },
                        isClearGoal = activeScope.goal == PlateCheckScope.Goal.CLEAR,
                        footnote = footnoteText,
                        extraTrailing = {
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
                                                store.versionCheckDifficulty = diff
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

                    visibleGroups.isEmpty() && activeGroups.isNotEmpty() -> {
                        item(key = "plate_all_completed") {
                            AppEmptyState(
                                icon = Icons.Rounded.CheckCircle,
                                title = "已全部达成 ${effectiveMode.title}",
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
                                        tint = diffTint,
                                        isNeutral = false
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
                                        difficulty = effectiveDifficulty,
                                        displayMode = effectiveMode,
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
