package dev.sorrowrain.maimaidata.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.R
import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.brightened
import dev.sorrowrain.maimaidata.services.CoverImageView
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme
import kotlin.math.max

/**
 * Semantic presets for [AppCapsuleLabel].
 */
sealed class CapsuleStyle {
    /** Section header count pill (e.g. `"24 首"`). */
    data object Count : CapsuleStyle()

    /** Active filter summary pill with subtle border. */
    data object Filter : CapsuleStyle()

    /** Compact inline status/rank badge (e.g. `"#1"`, `"水鱼"`, `"4x"`). */
    data object Compact : CapsuleStyle()

    /** Prominent metric pill with a prefix label (e.g. `"Ra 333"`). */
    data class Metric(val prefix: String) : CapsuleStyle()
}

private data class CapsuleSpec(
    val textStyle: TextStyle,
    val hPadding: Dp,
    val vPadding: Dp,
    val bgAlpha: Float,
    val strokeAlpha: Float,
    val prefix: String? = null
)

/**
 * Standardized tinted capsule label with semantic presets and optional dismiss button.
 */
@Composable
fun AppCapsuleLabel(
    text: String,
    tint: Color = AppTheme.accent,
    style: CapsuleStyle = CapsuleStyle.Count,
    modifier: Modifier = Modifier,
    onRemove: (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    val isNeutral = tint == Color.Unspecified

    val spec = when (style) {
        CapsuleStyle.Count -> CapsuleSpec(
            textStyle = AppTheme.AppTypography.captionMeta,
            hPadding = 8.dp,
            vPadding = 3.dp,
            bgAlpha = 0.12f,
            strokeAlpha = 0f
        )
        CapsuleStyle.Filter -> CapsuleSpec(
            textStyle = AppTheme.AppTypography.caption2Semibold,
            hPadding = 9.dp,
            vPadding = 4.dp,
            bgAlpha = 0.14f,
            strokeAlpha = 0.28f
        )
        CapsuleStyle.Compact -> CapsuleSpec(
            textStyle = AppTheme.AppTypography.caption2Bold,
            hPadding = 6.dp,
            vPadding = 2.dp,
            bgAlpha = 0.14f,
            strokeAlpha = 0f
        )
        is CapsuleStyle.Metric -> CapsuleSpec(
            textStyle = AppTheme.AppTypography.heroTitle,
            hPadding = 10.dp,
            vPadding = 4.dp,
            bgAlpha = 0.12f,
            strokeAlpha = 0f,
            prefix = style.prefix
        )
    }

    val fgColor = if (isNeutral) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else if (isDark) {
        tint.brightened(minimumBrightness = 1.0f, maxSaturation = 0.50f)
    } else {
        tint
    }

    val bgColor = if (isNeutral) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    } else {
        tint.copy(alpha = if (isDark) max(spec.bgAlpha, 0.22f) else spec.bgAlpha)
    }

    val effectiveStrokeAlpha = if (isNeutral) {
        if (spec.strokeAlpha > 0f) 0.30f else 0f
    } else if (isDark && spec.strokeAlpha > 0f) {
        max(spec.strokeAlpha, 0.42f)
    } else {
        spec.strokeAlpha
    }

    val strokeColor = if (isNeutral) {
        MaterialTheme.colorScheme.outline.copy(alpha = effectiveStrokeAlpha)
    } else {
        tint.copy(alpha = effectiveStrokeAlpha)
    }

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bgColor, CircleShape)
            .then(
                if (effectiveStrokeAlpha > 0f) {
                    Modifier.border(0.8.dp, strokeColor, CircleShape)
                } else {
                    Modifier
                }
            )
            .then(
                if (onRemove != null) {
                    Modifier
                        .semantics { contentDescription = "移除筛选：$text" }
                        .clickable(role = Role.Button, onClick = onRemove)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = spec.hPadding, vertical = spec.vPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
    ) {
        if (spec.prefix != null) {
            Text(
                text = spec.prefix,
                style = AppTheme.AppTypography.captionBold,
                color = fgColor,
                maxLines = 1,
                softWrap = false
            )
        }
        Text(
            text = text,
            style = spec.textStyle,
            color = fgColor,
            maxLines = 1,
            softWrap = false
        )
        if (onRemove != null) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = null,
                tint = fgColor.copy(alpha = 0.78f),
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

/**
 * Inset metric tile displaying a caption [title] above a string [value].
 */
@Composable
fun AppMetricTile(
    title: String,
    value: String,
    highlight: Boolean = false,
    modifier: Modifier = Modifier
) {
    AppMetricTile(
        title = title,
        modifier = modifier,
        highlight = highlight
    ) {
        Text(
            text = value,
            style = AppTheme.AppTypography.sectionMetric,
            color = if (highlight) AppTheme.accentText else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Inset metric tile displaying a caption [title] above custom [content].
 */
@Composable
fun AppMetricTile(
    title: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    valueSlotHeight: Dp? = null,
    content: @Composable () -> Unit
) {
    AppInsetGroup(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = AppTheme.Space.s, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = title,
                style = AppTheme.AppTypography.caption2Semibold,
                color = if (highlight) AppTheme.accentText else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Box(
                modifier = if (valueSlotHeight != null) Modifier.height(valueSlotHeight) else Modifier,
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }
    }
}

/**
 * Fixed-width value badge paired with sliders (e.g. `"ALL"`, `"13+"`, `".8"`).
 */
@Composable
fun AppValueBadge(
    text: String,
    isActive: Boolean = true,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(AppTheme.Radius.badge)
    val bgColor = if (isActive) {
        AppTheme.accent.copy(alpha = if (isDark) 0.22f else 0.13f)
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f)
    }
    val fgColor = if (isActive) {
        AppTheme.accentText
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(width = 44.dp, height = 24.dp)
            .background(bgColor, shape),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppTheme.AppTypography.captionMetaBold,
            color = fgColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Tinted statistic tile with caption [title] above [value] and active selection border state.
 */
@Composable
fun AppStatTile(
    title: String,
    value: String,
    tint: Color,
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(AppTheme.Radius.tile)
    val fillAlpha = if (isSelected) {
        if (isDark) 0.26f else 0.18f
    } else {
        if (isDark) 0.11f else 0.08f
    }
    val strokeAlpha = if (isSelected) {
        if (isDark) 0.90f else 0.78f
    } else {
        0.22f
    }
    val strokeWidth = if (isSelected) 1.8.dp else 1.dp
    val titleColor = if (isDark) {
        tint.brightened(minimumBrightness = 0.95f, maxSaturation = 0.60f)
    } else {
        tint
    }

    Column(
        modifier = modifier
            .clip(shape)
            .background(tint.copy(alpha = fillAlpha), shape)
            .border(strokeWidth, tint.copy(alpha = strokeAlpha), shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .semantics { selected = isSelected }
                        .clickable(role = Role.Tab, onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(vertical = AppTheme.Space.s, horizontal = AppTheme.Space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Text(
            text = title,
            style = AppTheme.AppTypography.caption2Bold,
            color = titleColor,
            maxLines = 1,
            softWrap = false
        )
        Text(
            text = value,
            style = AppTheme.AppTypography.captionMeta,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Rounded `Radius.badge` (`6.dp`) chart level/constant badge.
 * Handles `REMASTER` white fill + purple border (`chartRemaster` text in light mode, dark-adapted in dark mode)
 * and high-contrast `readableForeground` on `BASIC` / `ADVANCED` / `EXPERT`.
 */
@Composable
fun DifficultyLevelBadge(
    difficulty: DifficultyType,
    text: String,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val isRemaster = difficulty == DifficultyType.REMASTER
    val shape = RoundedCornerShape(AppTheme.Radius.badge)

    val bgColor = when {
        isRemaster && isDark -> difficulty.color.copy(alpha = 0.22f)
        isRemaster -> Color.White
        else -> difficulty.solidFill(isDark)
    }
    val textColor = when {
        isRemaster && isDark -> AppTheme.Palette.boardMainRemaster
        isRemaster -> AppTheme.Palette.chartRemaster
        else -> difficulty.readableForeground
    }
    val borderColor = if (isRemaster) difficulty.color else Color.Black.copy(alpha = 0.08f)
    val borderWidth = if (isRemaster) 1.5.dp else 0.5.dp

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 36.dp, minHeight = 22.dp)
            .background(bgColor, shape)
            .border(borderWidth, borderColor, shape)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppTheme.AppTypography.badge,
            color = textColor,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Colored genre capsule tag using [MaimaiConstants.shortGenreDisplayName] and [MaimaiConstants.genreTextColor].
 */
@Composable
fun GenreCapsuleTag(
    genre: String,
    regularSize: Boolean = false,
    text: String? = null,
    modifier: Modifier = Modifier
) {
    val isDark = LocalIsDarkTheme.current
    val bg = MaimaiConstants.genreColors(genre).bg
    val displayText = text ?: MaimaiConstants.shortGenreDisplayName(genre)

    Box(
        modifier = modifier
            .background(bg.copy(alpha = if (isDark) 0.22f else 0.15f), CircleShape)
            .border(0.8.dp, bg.copy(alpha = if (isDark) 0.45f else 0.30f), CircleShape)
            .padding(
                horizontal = if (regularSize) AppTheme.itemSpacing else AppTheme.Space.s,
                vertical = if (regularSize) 5.dp else 3.dp
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            style = if (regularSize) AppTheme.AppTypography.tag else AppTheme.AppTypography.caption2Semibold,
            color = MaimaiConstants.genreTextColor(genre, isDark),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Renders `ic_deluxe` or `ic_standard` chart type sprite.
 */
@Composable
fun ChartTypeBadge(
    isDX: Boolean,
    compact: Boolean = false,
    modifier: Modifier = Modifier
) {
    Image(
        painter = painterResource(if (isDX) R.drawable.ic_deluxe else R.drawable.ic_standard),
        contentDescription = if (isDX) "DX 谱面" else "标准谱面",
        contentScale = ContentScale.Fit,
        modifier = modifier.height(if (compact) 13.dp else 15.dp)
    )
}

/**
 * Renders rank sprite (`PRiSM` / `CiRCLE`) plus optional FC and FS sprites with accessibility labels.
 */
@Composable
fun RecordStatusBadges(
    record: ProberRecord,
    regularSize: Boolean = false,
    region: MaimaiConstants.ServerRegion = MaimaiConstants.ServerRegion.CN,
    modifier: Modifier = Modifier
) {
    val rankHeight = if (regularSize) 20.dp else 16.dp
    val markHeight = if (regularSize) 22.dp else 18.dp

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
    ) {
        MaimaiConstants.drawableResId(record.rankAssetName(region))?.let { rankRes ->
            Image(
                painter = painterResource(rankRes),
                contentDescription = record.rankAccessibilityLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(rankHeight)
            )
        }

        record.fcAssetName?.let { MaimaiConstants.drawableResId(it) }?.let { fcRes ->
            Image(
                painter = painterResource(fcRes),
                contentDescription = record.fcAccessibilityLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(markHeight)
            )
        }

        record.fsAssetName?.let { MaimaiConstants.drawableResId(it) }?.let { fsRes ->
            Image(
                painter = painterResource(fsRes),
                contentDescription = record.fsAccessibilityLabel,
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(markHeight)
            )
        }
    }
}

/**
 * Framed song jacket calling [CoverImageView] with rounded border and optional bottom-end [ChartTypeBadge].
 */
@Composable
fun SongCoverBadgeView(
    song: SongData?,
    sizeDp: Dp = 64.dp,
    cornerRadius: Dp = AppTheme.Radius.inset,
    borderColor: Color = AppTheme.accent,
    showCornerTypeBadge: Boolean = false,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(cornerRadius)
    val strokeWidth = if (sizeDp >= 90.dp) 3.dp else 2.dp

    Box(modifier = modifier.size(sizeDp)) {
        CoverImageView(
            song = song,
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .border(strokeWidth, borderColor, shape),
            contentScale = ContentScale.Crop
        )

        if (showCornerTypeBadge && song != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(AppTheme.Space.xs)
                    .background(Color.Black.copy(alpha = 0.58f), CircleShape)
                    .padding(horizontal = AppTheme.Space.xs, vertical = 2.dp)
            ) {
                ChartTypeBadge(
                    isDX = song.isDX,
                    compact = sizeDp < 90.dp
                )
            }
        }
    }
}

/**
 * Selectable filter capsule chip for genre/version/recent-search lists.
 */
@Composable
fun FilterCapsuleChip(
    title: String,
    isSelected: Boolean,
    tint: Color = AppTheme.accent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val borderColor = if (isSelected) {
        Color.White.copy(alpha = 0.28f)
    } else if (isDark) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = modifier
            .clip(CircleShape)
            .then(
                if (isSelected) {
                    Modifier.background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                tint,
                                tint.brightened(minimumBrightness = 0.85f, maxSaturation = 0.90f)
                            )
                        ),
                        shape = CircleShape
                    )
                } else {
                    Modifier.background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = CircleShape
                    )
                }
            )
            .border(1.dp, borderColor, CircleShape)
            .toggleable(
                value = isSelected,
                role = Role.Checkbox,
                onValueChange = { onClick() }
            )
            .padding(horizontal = AppTheme.Space.m, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            style = AppTheme.AppTypography.tag,
            color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            softWrap = false
        )
    }
}
