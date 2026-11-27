package dev.sorrowrain.maimaidata.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.models.brightened
import dev.sorrowrain.maimaidata.theme.AppSurfaceLevel
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme
import dev.sorrowrain.maimaidata.theme.LocalSurfaceLevel

/**
 * Clean Material 3 minimal surface card with 1dp hairline border.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp = AppTheme.cardPadding,
    useRaisedSurface: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val surfaceLevel = LocalSurfaceLevel.current
    val shape = RoundedCornerShape(AppTheme.Radius.card)
    val containerColor = AppTheme.cardSurfaceColor(
        isDark = isDark,
        surfaceLevel = surfaceLevel,
        useRaisedSurface = useRaisedSurface
    )
    val borderStroke = BorderStroke(
        width = 1.dp,
        color = AppTheme.cardBorderColor(isDark)
    )

    val cardBody: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(padding),
            content = content
        )
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = borderStroke,
            content = cardBody
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = containerColor,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = borderStroke,
            content = cardBody
        )
    }
}

/**
 * Section header with a 3.5dp vertical brand gradient bar, title, optional caption subtitle, and trailing slot.
 */
@Composable
fun AppSectionHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val stackTrailing = trailing != null && LocalDensity.current.fontScale >= 1.2f

    if (stackTrailing) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
            ) {
                SectionAccentBar()
                SectionHeaderTitles(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
            }
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.CenterEnd
            ) {
                trailing?.invoke()
            }
        }
    } else {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            SectionAccentBar()
            SectionHeaderTitles(title = title, subtitle = subtitle, modifier = Modifier.weight(1f))
            if (trailing != null) {
                Box(contentAlignment = Alignment.CenterEnd) {
                    trailing()
                }
            }
        }
    }
}

@Composable
private fun SectionAccentBar() {
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(16.dp)
            .clip(CircleShape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(AppTheme.accent, AppTheme.accentBright)
                )
            )
    )
}

@Composable
private fun SectionHeaderTitles(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = title,
            style = AppTheme.AppTypography.cardTitle,
            color = MaterialTheme.colorScheme.onSurface
        )
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                style = AppTheme.AppTypography.caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Titled section card combining [AppCard] and [AppSectionHeader].
 */
@Composable
fun AppSectionCard(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    padding: Dp = AppTheme.cardPadding,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    AppCard(
        modifier = modifier,
        padding = padding
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
        ) {
            AppSectionHeader(
                title = title,
                subtitle = subtitle,
                trailing = trailing
            )
            content()
        }
    }
}

/**
 * Inner rounded container (`Radius.inset = 12.dp`) clipped cleanly so edge-to-edge child rows
 * and rectangular highlights round cleanly at outer corners while keeping crisp hairline borders.
 */
@Composable
fun AppInsetGroup(
    modifier: Modifier = Modifier,
    padding: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val surfaceLevel = LocalSurfaceLevel.current
    val shape = RoundedCornerShape(AppTheme.Radius.inset)
    val bgColor = when {
        isDark && surfaceLevel == AppSurfaceLevel.Sheet -> AppTheme.Palette.sheetInsetDark
        isDark -> AppTheme.Palette.insetDark
        else -> Color.Black.copy(alpha = 0.04f)
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(bgColor, shape)
            .padding(padding),
        content = content
    )
}

/**
 * Full-width inset hairline divider with zero leading indentation so highlighted rows never leave asymmetric gaps.
 */
@Composable
fun AppInsetDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        modifier = modifier.fillMaxWidth(),
        thickness = 0.8.dp,
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

/**
 * Explicit rectangular row highlight inside [AppInsetGroup].
 */
@Composable
fun Modifier.appInsetRowHighlight(
    isHighlighted: Boolean,
    tint: Color = AppTheme.accent
): Modifier {
    if (!isHighlighted) return this
    val isDark = LocalIsDarkTheme.current
    return this.background(
        color = tint.copy(alpha = if (isDark) 0.16f else 0.08f),
        shape = RectangleShape
    )
}

/**
 * Standard 52dp min-height row inside [AppInsetGroup] with optional 30dp tinted icon tile,
 * title/subtitle, custom trailing slot, and optional forward chevron.
 */
@Composable
fun AppInsetRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    iconTint: Color = AppTheme.accent,
    monospacedSubtitle: Boolean = false,
    showsChevron: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val isDark = LocalIsDarkTheme.current
    val iconShape = RoundedCornerShape(AppTheme.Radius.tile)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppTheme.rowMinHeight)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = AppTheme.Space.m, vertical = AppTheme.itemSpacing),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(
                        color = iconTint.copy(alpha = if (isDark) 0.22f else 0.14f),
                        shape = iconShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (isDark) {
                        iconTint.brightened(minimumBrightness = 0.90f, maxSaturation = 0.65f)
                    } else {
                        iconTint
                    },
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                style = AppTheme.AppTypography.rowTitle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = if (monospacedSubtitle) {
                        AppTheme.AppTypography.captionMono
                    } else {
                        AppTheme.AppTypography.caption
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (trailing != null) {
            trailing()
        }

        if (showsChevron) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Standard toggle switch row inside [AppInsetGroup] with full-row toggleable semantics and adaptive accent track.
 */
@Composable
fun AppInsetToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = AppTheme.rowMinHeight)
            .toggleable(
                value = checked,
                role = Role.Switch,
                onValueChange = onCheckedChange
            )
            .padding(horizontal = AppTheme.Space.m, vertical = AppTheme.Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
    ) {
        Text(
            text = title,
            style = AppTheme.AppTypography.rowTitle,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = AppTheme.accentText
            )
        )
    }
}
