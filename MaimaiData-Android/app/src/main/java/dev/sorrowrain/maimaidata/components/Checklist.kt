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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.models.ChecklistStats
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.CoverImageView
import dev.sorrowrain.maimaidata.theme.AppTheme

enum class CheckDisplayMode(val title: String) {
    SSS("SSS (将)"),
    FC("FC (极)"),
    AP("AP (神)"),
    FSD("FSD (舞舞)");

    fun isCompleted(record: ProberRecord?): Boolean {
        if (record == null) return false
        return when (this) {
            SSS -> record.achievements >= 100.0
            FC -> record.fc.isNotEmpty()
            AP -> record.fc == "ap" || record.fc == "app"
            FSD -> record.fs == "fsd" || record.fs == "fsdp"
        }
    }
}

/**
 * Shared checklist completion statistics card used by `VersionCheckView` and `LevelCheckView`.
 * Places [leadingAccessory] (e.g. difficulty switcher capsule) immediately to the left of `"仅看未达成"`.
 */
@Composable
fun ChecklistStatsHeader(
    stats: ChecklistStats,
    displayMode: CheckDisplayMode,
    onDisplayModeChange: (CheckDisplayMode) -> Unit,
    onlyUnfinished: Boolean,
    onOnlyUnfinishedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    leadingAccessory: (@Composable () -> Unit)? = null
) {
    AppSectionCard(
        title = "完成度统计",
        modifier = modifier,
        trailing = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
            ) {
                if (leadingAccessory != null) {
                    leadingAccessory()
                }
                AppInlineButton(
                    title = "仅看未达成",
                    icon = Icons.Rounded.FilterList,
                    tint = AppTheme.accent,
                    isNeutral = !onlyUnfinished,
                    onClick = { onOnlyUnfinishedChange(!onlyUnfinished) }
                )
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            StatModeTile(
                mode = CheckDisplayMode.SSS,
                shortLabel = "SSS",
                count = stats.sssCount,
                total = stats.totalCount,
                tint = AppTheme.Palette.statSSS,
                isSelected = displayMode == CheckDisplayMode.SSS,
                modifier = Modifier.weight(1f),
                onSelect = { onDisplayModeChange(CheckDisplayMode.SSS) }
            )
            StatModeTile(
                mode = CheckDisplayMode.FC,
                shortLabel = "FC",
                count = stats.fcCount,
                total = stats.totalCount,
                tint = AppTheme.Palette.statFC,
                isSelected = displayMode == CheckDisplayMode.FC,
                modifier = Modifier.weight(1f),
                onSelect = { onDisplayModeChange(CheckDisplayMode.FC) }
            )
            StatModeTile(
                mode = CheckDisplayMode.AP,
                shortLabel = "AP",
                count = stats.apCount,
                total = stats.totalCount,
                tint = AppTheme.Palette.statAP,
                isSelected = displayMode == CheckDisplayMode.AP,
                modifier = Modifier.weight(1f),
                onSelect = { onDisplayModeChange(CheckDisplayMode.AP) }
            )
            StatModeTile(
                mode = CheckDisplayMode.FSD,
                shortLabel = "FSD",
                count = stats.fsdCount,
                total = stats.totalCount,
                tint = AppTheme.Palette.statFSD,
                isSelected = displayMode == CheckDisplayMode.FSD,
                modifier = Modifier.weight(1f),
                onSelect = { onDisplayModeChange(CheckDisplayMode.FSD) }
            )
        }
    }
}

@Composable
private fun StatModeTile(
    mode: CheckDisplayMode,
    shortLabel: String,
    count: Int,
    total: Int,
    tint: Color,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onSelect: () -> Unit
) {
    AppStatTile(
        title = shortLabel,
        value = "$count/$total",
        tint = tint,
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
        val spacing = AppTheme.itemSpacing
        val columns = ((maxWidth + spacing) / (tileSize + spacing)).toInt().coerceAtLeast(3)
        val rows = items.chunked(columns)

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(spacing)
        ) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
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
 * 72dp rounded checklist jacket tile with dark scrim + rank/FC/FS sprite overlay when [record] is present,
 * colored difficulty border, and optional top-end [ChartTypeBadge].
 */
@Composable
fun ChecklistSongTile(
    song: SongData,
    record: ProberRecord?,
    borderColor: Color,
    displayMode: CheckDisplayMode,
    showTypeBadge: Boolean = false,
    region: MaimaiConstants.ServerRegion = MaimaiConstants.ServerRegion.CN,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(AppTheme.Radius.tile)

    Box(
        modifier = modifier
            .size(72.dp)
            .clip(shape)
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
                    .background(Color.Black.copy(alpha = 0.46f)),
                contentAlignment = Alignment.Center
            ) {
                ChecklistOverlayMark(
                    record = record,
                    displayMode = displayMode,
                    region = region
                )
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .border(2.5.dp, borderColor, shape)
        )

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
    region: MaimaiConstants.ServerRegion
) {
    when (displayMode) {
        CheckDisplayMode.SSS -> {
            MaimaiConstants.drawableResId(record.rankAssetName(region))?.let { resId ->
                Image(
                    painter = painterResource(resId),
                    contentDescription = record.rankAccessibilityLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(width = 52.dp, height = 24.dp)
                )
            }
        }
        CheckDisplayMode.FC, CheckDisplayMode.AP -> {
            record.fcAssetName?.let { MaimaiConstants.drawableResId(it) }?.let { resId ->
                Image(
                    painter = painterResource(resId),
                    contentDescription = record.fcAccessibilityLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
        CheckDisplayMode.FSD -> {
            record.fsAssetName?.let { MaimaiConstants.drawableResId(it) }?.let { resId ->
                Image(
                    painter = painterResource(resId),
                    contentDescription = record.fsAccessibilityLabel,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
