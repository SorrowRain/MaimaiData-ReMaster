package dev.sorrowrain.maimaidata.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.models.ChecklistStats
import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.CoverImageView
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme

enum class CheckDisplayMode(val title: String) {
    SSS("SSS"),
    FC("FC"),
    AP("AP"),
    FSD("FSD"),
    CLEAR("CLEAR");

    fun isCompleted(record: ProberRecord?): Boolean {
        if (record == null) return false
        return when (this) {
            SSS -> record.achievements >= 100.0
            FC -> record.fc.isNotEmpty()
            AP -> record.fc == "ap" || record.fc == "app"
            FSD -> record.fs == "fsd" || record.fs == "fsdp"
            CLEAR -> record.achievements >= 80.0
        }
    }

    companion object {
        val standardModes: List<CheckDisplayMode> = listOf(SSS, FC, AP, FSD)
    }
}

/**
 * Shared checklist completion statistics card used by `VersionCheckView` and `LevelCheckView`.
 */
@Composable
fun ChecklistStatsHeader(
    stats: ChecklistStats,
    displayMode: CheckDisplayMode,
    onDisplayModeChange: (CheckDisplayMode) -> Unit,
    onlyUnfinished: Boolean,
    onOnlyUnfinishedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    isClearGoal: Boolean = false,
    footnote: String? = null,
    extraTrailing: (@Composable () -> Unit)? = null
) {
    val useTwoByTwo = LocalDensity.current.fontScale >= 1.2f

    AppSectionCard(
        title = "完成度统计",
        modifier = modifier,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
            ) {
                extraTrailing?.invoke()
                AppInlineButton(
                    title = "仅看未达成",
                    icon = Icons.Rounded.FilterList,
                    tint = AppTheme.accent,
                    isNeutral = !onlyUnfinished,
                    isSolidActive = onlyUnfinished,
                    onClick = { onOnlyUnfinishedChange(!onlyUnfinished) }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
        ) {
            if (isClearGoal) {
                AppStatTile(
                    title = "CLEAR (≥80%)",
                    value = "${stats.clearCount}/${stats.totalCount}",
                    tint = AppTheme.accent,
                    fillTint = AppTheme.accent,
                    isSelected = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            contentDescription = "CLEAR 进度 ${stats.clearCount} / ${stats.totalCount}"
                        }
                )
            } else {
                val modeRows = CheckDisplayMode.standardModes.chunked(if (useTwoByTwo) 2 else 4)
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                ) {
                    modeRows.forEach { rowModes ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                        ) {
                            rowModes.forEach { mode ->
                                val (count, tint, fillTint) = statAppearance(mode, stats)
                                StatModeTile(
                                    mode = mode,
                                    shortLabel = mode.title,
                                    count = count,
                                    total = stats.totalCount,
                                    tint = tint,
                                    fillTint = fillTint,
                                    isSelected = displayMode == mode,
                                    modifier = Modifier.weight(1f),
                                    onSelect = { onDisplayModeChange(mode) }
                                )
                            }
                        }
                    }
                }
            }

            if (!footnote.isNullOrEmpty()) {
                Text(
                    text = footnote,
                    style = AppTheme.AppTypography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun statAppearance(
    mode: CheckDisplayMode,
    stats: ChecklistStats
): Triple<Int, Color, Color?> = when (mode) {
    CheckDisplayMode.SSS -> Triple(stats.sssCount, AppTheme.Palette.statSSS, AppTheme.Palette.statSSSFill)
    CheckDisplayMode.FC -> Triple(stats.fcCount, AppTheme.Palette.statFC, null)
    CheckDisplayMode.AP -> Triple(stats.apCount, AppTheme.Palette.statAP, AppTheme.Palette.statAPFill)
    CheckDisplayMode.FSD -> Triple(stats.fsdCount, AppTheme.Palette.statFSD, null)
    CheckDisplayMode.CLEAR -> Triple(stats.clearCount, AppTheme.accent, AppTheme.accent)
}

@Composable
private fun StatModeTile(
    mode: CheckDisplayMode,
    shortLabel: String,
    count: Int,
    total: Int,
    tint: Color,
    fillTint: Color? = null,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    AppStatTile(
        title = shortLabel,
        value = "$count/$total",
        tint = tint,
        fillTint = fillTint,
        isSelected = isSelected,
        modifier = modifier.semantics {
            contentDescription = "${mode.title} 进度 $count / $total"
        },
        onClick = onSelect
    )
}

/**
 * Responsive 72dp tile grid for checklist groups (1:1 counterpart of iOS `ChecklistSongGrid`),
 * avoiding nested scrollable grids inside `LazyColumn`.
 */
@Composable
fun <T> ChecklistSongGrid(
    items: List<T>,
    itemKey: (T) -> Any,
    modifier: Modifier = Modifier,
    tileContent: @Composable (T) -> Unit
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val tileSize = 72.dp
        val spacing = AppTheme.Space.s
        val columns = ((maxWidth + spacing) / (tileSize + spacing)).toInt().coerceAtLeast(1)
        val rows = items.chunked(columns)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (columns > 1) {
                        Arrangement.SpaceBetween
                    } else {
                        Arrangement.Start
                    }
                ) {
                    rowItems.forEach { item ->
                        key(itemKey(item)) {
                            tileContent(item)
                        }
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(modifier = Modifier.size(tileSize))
                    }
                }
            }
        }
    }
}

/**
 * 72dp rounded checklist jacket tile with 3-state encoding,
 * double-layer Re:MASTER border, and optional top-end [ChartTypeBadge].
 */
@Composable
fun ChecklistSongTile(
    song: SongData,
    record: ProberRecord?,
    difficulty: DifficultyType,
    displayMode: CheckDisplayMode,
    showTypeBadge: Boolean = false,
    region: MaimaiConstants.ServerRegion = MaimaiConstants.ServerRegion.CN,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(AppTheme.Radius.tile)
    val isRemaster = difficulty == DifficultyType.REMASTER
    val isCompleted = record != null && displayMode.isCompleted(record)
    val scrimAlpha = when {
        record == null -> 0f
        isCompleted -> 0.60f
        else -> 0.34f
    }
    val accessibilityText = when {
        record == null -> "${song.title}，未游玩"
        else -> {
            val completionText = if (isCompleted) "已达成 ${displayMode.title}" else "未达成 ${displayMode.title}"
            when (displayMode) {
                CheckDisplayMode.SSS, CheckDisplayMode.CLEAR -> "${song.title}，$completionText，${record.rankAccessibilityLabel}"
                CheckDisplayMode.FC, CheckDisplayMode.AP -> {
                    if (isCompleted && record.fcAssetName != null) {
                        "${song.title}，$completionText，${record.fcAccessibilityLabel}"
                    } else {
                        "${song.title}，$completionText"
                    }
                }
                CheckDisplayMode.FSD -> {
                    if (isCompleted && record.fsAssetName != null) {
                        "${song.title}，$completionText，${record.fsAccessibilityLabel}"
                    } else {
                        "${song.title}，$completionText"
                    }
                }
            }
        }
    }

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(shape)
            .semantics(mergeDescendants = true) {
                contentDescription = accessibilityText
            }
            .clickable(role = Role.Button, onClick = onClick)
    ) {
        CoverImageView(
            song = song,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop
        )

        if (record != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = scrimAlpha)),
                contentAlignment = Alignment.Center
            ) {
                ChecklistOverlayMark(
                    record = record,
                    displayMode = displayMode,
                    isCompleted = isCompleted,
                    region = region
                )
            }
        }

        if (isRemaster) {
            val outerColor = if (isDark) AppTheme.Palette.diffRemaster else AppTheme.Palette.chartRemaster
            val innerShape = RoundedCornerShape(AppTheme.Radius.tile - 1.dp)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(1.dp, outerColor, shape)
                    .padding(1.dp)
                    .border(2.dp, AppTheme.Palette.boardMainRemaster, innerShape)
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(2.5.dp, difficulty.color, shape)
            )
        }

        if (isCompleted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .size(16.dp)
                    .background(AppTheme.Palette.statFC, CircleShape)
                    .border(1.dp, Color.White.copy(alpha = 0.90f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(10.dp)
                )
            }
        }

        if (showTypeBadge) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp)
            ) {
                ChartTypeBadge(isDX = song.isDX, compact = true)
            }
        }
    }
}

@Composable
private fun ChecklistOverlayMark(
    record: ProberRecord,
    displayMode: CheckDisplayMode,
    isCompleted: Boolean,
    region: MaimaiConstants.ServerRegion
) {
    when (displayMode) {
        CheckDisplayMode.SSS, CheckDisplayMode.CLEAR -> {
            MaimaiConstants.drawableResId(record.rankAssetName(region))?.let { resId ->
                Image(
                    painter = painterResource(resId),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 52.dp, height = 24.dp)
                )
            }
        }
        CheckDisplayMode.FC, CheckDisplayMode.AP -> {
            if (isCompleted) {
                record.fcAssetName?.let { MaimaiConstants.drawableResId(it) }?.let { resId ->
                    Image(
                        painter = painterResource(resId),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
        CheckDisplayMode.FSD -> {
            if (isCompleted) {
                record.fsAssetName?.let { MaimaiConstants.drawableResId(it) }?.let { resId ->
                    Image(
                        painter = painterResource(resId),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}
