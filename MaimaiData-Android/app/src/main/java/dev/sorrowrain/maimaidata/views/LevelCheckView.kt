package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppCircleIconButton
import dev.sorrowrain.maimaidata.components.AppEmptyState
import dev.sorrowrain.maimaidata.components.AppLoadingState
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppSlider
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CheckDisplayMode
import dev.sorrowrain.maimaidata.components.ChecklistSongGrid
import dev.sorrowrain.maimaidata.components.ChecklistSongTile
import dev.sorrowrain.maimaidata.components.ChecklistStatsHeader
import dev.sorrowrain.maimaidata.models.ChecklistStats
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ResolvedChart
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private data class LevelChartItem(
    val song: SongData,
    val chart: ResolvedChart
) {
    val key: String get() = "${song.id}_${chart.index}"
}

private data class DSChartGroup(
    val ds: Double,
    val items: List<LevelChartItem>
)

@Composable
fun LevelCheckScreen(
    onBack: () -> Unit,
    onOpenSong: (songId: String, initialDifficultyIndex: Int?) -> Unit
) {
    val store = LocalDataStore.current

    var displayMode by rememberSaveable { mutableStateOf(CheckDisplayMode.SSS) }
    var onlyUnfinished by rememberSaveable { mutableStateOf(false) }

    val levels = remember { MaimaiConstants.allLevels.drop(1) }
    val currentLevelIndex = store.lastLevelCheckIndex.coerceIn(0, max(0, levels.lastIndex))
    val selectedLevel = levels.getOrElse(currentLevelIndex) { "1" }

    val (dsGroups, totalChartCount) = remember(store.songs, store.catalogRevision, selectedLevel) {
        val chartItems = store.songs
            .asSequence()
            .filter { !it.isUtage && it.isDisabled != true }
            .flatMap { song ->
                song.resolvedCharts
                    .asSequence()
                    .filter { it.level == selectedLevel }
                    .map { chart -> LevelChartItem(song = song, chart = chart) }
            }
            .sortedByDescending { it.chart.ds }
            .toList()

        val grouped = chartItems.groupBy { (it.chart.ds * 10.0).roundToInt() }
        val sortedGroups = grouped.keys
            .sortedDescending()
            .map { tenths ->
                val list = grouped[tenths].orEmpty()
                DSChartGroup(
                    ds = tenths / 10.0,
                    items = list
                )
            }
        sortedGroups to chartItems.size
    }

    val stats = remember(dsGroups, totalChartCount, store.records) {
        val matched = dsGroups
            .asSequence()
            .flatMap { it.items.asSequence() }
            .mapNotNull { item -> store.record(item.song.numericId, item.chart.index) }
            .toList()
        ChecklistStats(records = matched, totalCount = totalChartCount)
    }

    val visibleGroups = remember(dsGroups, onlyUnfinished, displayMode, store.records) {
        if (!onlyUnfinished) {
            dsGroups
        } else {
            dsGroups.mapNotNull { group ->
                val remaining = group.items.filter { item ->
                    !displayMode.isCompleted(
                        store.record(item.song.numericId, item.chart.index)
                    )
                }
                if (remaining.isEmpty()) null else DSChartGroup(ds = group.ds, items = remaining)
            }
        }
    }

    AppPageBackground(tint = AppTheme.Palette.progressAccent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "等级进度表",
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
                // 1. Level Stepper + Slider Card
                item(key = "level_selector_card") {
                    AppCard(
                        padding = AppTheme.cardPadding,
                        tint = AppTheme.Palette.progressAccent
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppCircleIconButton(
                                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
                                    contentDescription = "上一个等级",
                                    enabled = currentLevelIndex > 0,
                                    onClick = {
                                        if (currentLevelIndex > 0) {
                                            store.lastLevelCheckIndex = currentLevelIndex - 1
                                        }
                                    }
                                )

                                Column(
                                    modifier = Modifier.weight(1f),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "LEVEL $selectedLevel",
                                        style = AppTheme.AppTypography.sectionMetric,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = "共 $totalChartCount 张谱面",
                                        style = AppTheme.AppTypography.caption,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                AppCircleIconButton(
                                    icon = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = "下一个等级",
                                    enabled = currentLevelIndex < levels.lastIndex,
                                    onClick = {
                                        if (currentLevelIndex < levels.lastIndex) {
                                            store.lastLevelCheckIndex = currentLevelIndex + 1
                                        }
                                    }
                                )
                            }

                            if (levels.size > 1) {
                                AppSlider(
                                    value = currentLevelIndex,
                                    onValueChange = { next ->
                                        if (next != store.lastLevelCheckIndex) {
                                            store.lastLevelCheckIndex = next
                                        }
                                    },
                                    valueRange = 0..levels.lastIndex
                                )
                            }
                        }
                    }
                }

                // 2. Completion Stats Header
                item(key = "level_stats_header") {
                    ChecklistStatsHeader(
                        stats = stats,
                        displayMode = displayMode,
                        onDisplayModeChange = { displayMode = it },
                        onlyUnfinished = onlyUnfinished,
                        onOnlyUnfinishedChange = { onlyUnfinished = it }
                    )
                }

                // 3. Syncing / Empty / DS-Grouped Chart Grids
                when {
                    store.isSyncingSongs && store.songs.isEmpty() -> {
                        item(key = "level_syncing_state") {
                            AppLoadingState(message = "正在同步${store.serverRegion.shortName}曲库…")
                        }
                    }

                    store.songs.isEmpty() -> {
                        item(key = "level_empty_catalog") {
                            AppEmptyState(
                                icon = Icons.Rounded.Sync,
                                title = "暂无${store.serverRegion.shortName}曲库数据",
                                description = "请前往「设置与数据 → 数据同步与存储」点击检查更新"
                            )
                        }
                    }

                    visibleGroups.isEmpty() && totalChartCount > 0 -> {
                        item(key = "level_all_completed") {
                            AppEmptyState(
                                icon = Icons.Rounded.CheckCircle,
                                title = "已全部达成 ${displayMode.title}",
                                description = "本等级全部谱面均已达成该目标",
                                iconTint = AppTheme.accentText
                            )
                        }
                    }

                    else -> {
                        items(
                            items = visibleGroups,
                            key = { group -> "${selectedLevel}_${(group.ds * 10).roundToInt()}" }
                        ) { group ->
                            AppSectionCard(
                                title = String.format(Locale.US, "定数 %.1f", group.ds),
                                trailing = {
                                    AppCapsuleLabel(text = "${group.items.size} 首")
                                }
                            ) {
                                ChecklistSongGrid(
                                    items = group.items,
                                    itemKey = { it.key }
                                ) { item ->
                                    ChecklistSongTile(
                                        song = item.song,
                                        record = store.record(
                                            item.song.numericId,
                                            item.chart.index
                                        ),
                                        borderColor = item.chart.difficulty.color,
                                        displayMode = displayMode,
                                        showTypeBadge = true,
                                        region = store.serverRegion,
                                        onClick = {
                                            onOpenSong(item.song.id, item.chart.index)
                                        }
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
