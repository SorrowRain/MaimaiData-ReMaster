package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppCircleIconButton
import dev.sorrowrain.maimaidata.components.AppEmptyState
import dev.sorrowrain.maimaidata.components.AppLoadingState
import dev.sorrowrain.maimaidata.components.AppSectionHeader
import dev.sorrowrain.maimaidata.components.AppSlider
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CheckDisplayMode
import dev.sorrowrain.maimaidata.components.ChecklistSongTile
import dev.sorrowrain.maimaidata.components.ChecklistStatsHeader
import dev.sorrowrain.maimaidata.models.ChecklistStats
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.services.LevelCheckChartItem
import dev.sorrowrain.maimaidata.services.LevelCheckDSBucket
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme
import kotlinx.coroutines.delay
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

private sealed interface LevelLazyRow {
    val stableKey: String

    data class Header(
        val selectedLevel: String,
        val ds: Double,
        val totalInBucket: Int
    ) : LevelLazyRow {
        override val stableKey: String = "hdr_${selectedLevel}_${(ds * 10).roundToInt()}"
    }

    data class TileRow(
        val selectedLevel: String,
        val ds: Double,
        val rowIndex: Int,
        val isLastRow: Boolean,
        val columns: Int,
        val items: List<LevelCheckChartItem>
    ) : LevelLazyRow {
        override val stableKey: String = "row_${selectedLevel}_${(ds * 10).roundToInt()}_$rowIndex"
    }
}

private fun Modifier.sectionSliceCardChrome(
    isTop: Boolean,
    isBottom: Boolean,
    containerColor: Color,
    borderColor: Color,
    cornerRadius: Dp = AppTheme.Radius.card
): Modifier {
    val shape = when {
        isTop && isBottom -> RoundedCornerShape(cornerRadius)
        isTop -> RoundedCornerShape(topStart = cornerRadius, topEnd = cornerRadius)
        isBottom -> RoundedCornerShape(bottomStart = cornerRadius, bottomEnd = cornerRadius)
        else -> RectangleShape
    }
    return this
        .clip(shape)
        .background(containerColor, shape)
        .drawBehind {
            val strokePx = 1.dp.toPx()
            val half = strokePx / 2f
            val r = (cornerRadius.toPx() - half).coerceAtLeast(0f)
            val w = size.width
            val h = size.height
            when {
                isTop -> {
                    val path = Path().apply {
                        moveTo(half, h)
                        lineTo(half, half + r)
                        arcTo(Rect(half, half, half + 2 * r, half + 2 * r), 180f, 90f, false)
                        lineTo(w - half - r, half)
                        arcTo(Rect(w - half - 2 * r, half, w - half, half + 2 * r), 270f, 90f, false)
                        lineTo(w - half, h)
                    }
                    drawPath(path, borderColor, style = Stroke(width = strokePx))
                }
                isBottom -> {
                    val path = Path().apply {
                        moveTo(half, 0f)
                        lineTo(half, h - half - r)
                        arcTo(Rect(half, h - half - 2 * r, half + 2 * r, h - half), 180f, -90f, false)
                        lineTo(w - half - r, h - half)
                        arcTo(Rect(w - half - 2 * r, h - half - 2 * r, w - half, h - half), 90f, -90f, false)
                        lineTo(w - half, 0f)
                    }
                    drawPath(path, borderColor, style = Stroke(width = strokePx))
                }
                else -> {
                    drawLine(borderColor, Offset(half, 0f), Offset(half, h), strokeWidth = strokePx)
                    drawLine(borderColor, Offset(w - half, 0f), Offset(w - half, h), strokeWidth = strokePx)
                }
            }
        }
}

@Composable
fun LevelCheckScreen(
    onBack: () -> Unit,
    onOpenSong: (songId: String, initialDifficultyIndex: Int?) -> Unit
) {
    val store = LocalDataStore.current
    val isDark = LocalIsDarkTheme.current
    val effectiveDisplayMode = if (store.checklistDisplayMode == CheckDisplayMode.CLEAR) {
        CheckDisplayMode.SSS
    } else {
        store.checklistDisplayMode
    }

    val levels = remember { MaimaiConstants.allLevels.drop(1) }
    var sliderLevelIndex by remember {
        mutableIntStateOf(store.lastLevelCheckIndex.coerceIn(0, max(0, levels.lastIndex)))
    }

    LaunchedEffect(store.lastLevelCheckIndex) {
        val clamped = store.lastLevelCheckIndex.coerceIn(0, max(0, levels.lastIndex))
        if (sliderLevelIndex != clamped) {
            sliderLevelIndex = clamped
        }
    }

    LaunchedEffect(sliderLevelIndex) {
        delay(120L)
        if (sliderLevelIndex != store.lastLevelCheckIndex) {
            store.lastLevelCheckIndex = sliderLevelIndex
        }
    }

    val currentLevelIndex = sliderLevelIndex.coerceIn(0, max(0, levels.lastIndex))
    val selectedLevel = levels.getOrElse(currentLevelIndex) { "1" }

    val dsGroups = store.levelCheckBuckets[selectedLevel].orEmpty()
    val totalChartCount = remember(dsGroups) {
        dsGroups.sumOf { it.items.size }
    }

    val stats = remember(dsGroups, totalChartCount, store.records) {
        val matched = dsGroups
            .asSequence()
            .flatMap { it.items.asSequence() }
            .mapNotNull { item -> store.record(item.song.numericId, item.chart.index) }
            .toList()
        ChecklistStats(records = matched, totalCount = totalChartCount)
    }

    val visibleGroups = remember(dsGroups, store.checklistOnlyUnfinished, effectiveDisplayMode, store.records) {
        if (!store.checklistOnlyUnfinished) {
            dsGroups
        } else {
            dsGroups.mapNotNull { group ->
                val remaining = group.items.filter { item ->
                    !effectiveDisplayMode.isCompleted(
                        store.record(item.song.numericId, item.chart.index)
                    )
                }
                if (remaining.isEmpty()) null else LevelCheckDSBucket(ds = group.ds, items = remaining)
            }
        }
    }

    val sliceContainerColor = AppTheme.cardSurfaceColor(isDark = isDark)
    val sliceBorderColor = AppTheme.cardBorderColor(isDark = isDark)

    AppPageBackground {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "等级进度表",
                    onBack = onBack
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                val availableGridWidth = maxWidth - AppTheme.pagePadding * 2 - AppTheme.cardPadding * 2
                val gridColumns = ((availableGridWidth + AppTheme.Space.s) / (72.dp + AppTheme.Space.s))
                    .toInt()
                    .coerceAtLeast(1)

                val lazyRows = remember(visibleGroups, selectedLevel, gridColumns) {
                    buildList {
                        visibleGroups.forEach { group ->
                            add(
                                LevelLazyRow.Header(
                                    selectedLevel = selectedLevel,
                                    ds = group.ds,
                                    totalInBucket = group.items.size
                                )
                            )
                            val chunked = group.items.chunked(gridColumns)
                            chunked.forEachIndexed { rowIdx, rowItems ->
                                add(
                                    LevelLazyRow.TileRow(
                                        selectedLevel = selectedLevel,
                                        ds = group.ds,
                                        rowIndex = rowIdx,
                                        isLastRow = rowIdx == chunked.lastIndex,
                                        columns = gridColumns,
                                        items = rowItems
                                    )
                                )
                            }
                        }
                    }
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = AppTheme.pagePadding,
                        vertical = AppTheme.Space.m
                    ),
                    verticalArrangement = Arrangement.Top
                ) {
                    // 1. Level Stepper + Slider Card
                    item(key = "level_selector_card") {
                        AppCard(
                            modifier = Modifier.padding(bottom = AppTheme.cardSpacing),
                            padding = AppTheme.cardPadding
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
                                                val next = currentLevelIndex - 1
                                                sliderLevelIndex = next
                                                store.lastLevelCheckIndex = next
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
                                                val next = currentLevelIndex + 1
                                                sliderLevelIndex = next
                                                store.lastLevelCheckIndex = next
                                            }
                                        }
                                    )
                                }

                                if (levels.size > 1) {
                                    AppSlider(
                                        value = currentLevelIndex,
                                        onValueChange = { next ->
                                            sliderLevelIndex = next
                                        },
                                        onValueChangeFinished = {
                                            if (sliderLevelIndex != store.lastLevelCheckIndex) {
                                                store.lastLevelCheckIndex = sliderLevelIndex
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
                            displayMode = effectiveDisplayMode,
                            onDisplayModeChange = { store.checklistDisplayMode = it },
                            onlyUnfinished = store.checklistOnlyUnfinished,
                            onOnlyUnfinishedChange = { store.checklistOnlyUnfinished = it },
                            modifier = Modifier.padding(bottom = AppTheme.cardSpacing)
                        )
                    }

                    // 3. Syncing / Empty / Flattened Lazy Chart Rows
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
                                    title = "已全部达成 ${effectiveDisplayMode.title}",
                                    description = "本等级全部谱面均已达成该目标",
                                    iconTint = AppTheme.accentText
                                )
                            }
                        }

                        else -> {
                            items(
                                items = lazyRows,
                                key = { it.stableKey }
                            ) { row ->
                                when (row) {
                                    is LevelLazyRow.Header -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .sectionSliceCardChrome(
                                                    isTop = true,
                                                    isBottom = false,
                                                    containerColor = sliceContainerColor,
                                                    borderColor = sliceBorderColor
                                                )
                                                .padding(
                                                    start = AppTheme.cardPadding,
                                                    top = AppTheme.cardPadding,
                                                    end = AppTheme.cardPadding,
                                                    bottom = AppTheme.Space.m
                                                )
                                        ) {
                                            AppSectionHeader(
                                                title = String.format(Locale.US, "定数 %.1f", row.ds),
                                                trailing = {
                                                    AppCapsuleLabel(text = "${row.totalInBucket} 张")
                                                }
                                            )
                                        }
                                    }

                                    is LevelLazyRow.TileRow -> {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    bottom = if (row.isLastRow) AppTheme.cardSpacing else 0.dp
                                                )
                                                .sectionSliceCardChrome(
                                                    isTop = false,
                                                    isBottom = row.isLastRow,
                                                    containerColor = sliceContainerColor,
                                                    borderColor = sliceBorderColor
                                                )
                                                .padding(
                                                    start = AppTheme.cardPadding,
                                                    end = AppTheme.cardPadding,
                                                    bottom = if (row.isLastRow) AppTheme.cardPadding else AppTheme.Space.s
                                                )
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = if (row.columns > 1) {
                                                    Arrangement.SpaceBetween
                                                } else {
                                                    Arrangement.Start
                                                }
                                            ) {
                                                row.items.forEach { item ->
                                                    ChecklistSongTile(
                                                        song = item.song,
                                                        record = store.record(
                                                            item.song.numericId,
                                                            item.chart.index
                                                        ),
                                                        difficulty = item.chart.difficulty,
                                                        displayMode = effectiveDisplayMode,
                                                        showTypeBadge = true,
                                                        region = store.serverRegion,
                                                        onClick = {
                                                            onOpenSong(item.song.id, item.chart.index)
                                                        }
                                                    )
                                                }
                                                repeat(row.columns - row.items.size) {
                                                    Spacer(modifier = Modifier.size(72.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
