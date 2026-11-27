package dev.sorrowrain.maimaidata.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.models.brightened
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme

/**
 * Clean Material 3 filled primary button (`48.dp` standard height, `52.dp` input companion height).
 */
@Composable
fun AppPrimaryButton(
    title: String,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    companionHeight: Boolean = false,
    tint: Color = AppTheme.accent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isInteractive = enabled && !isLoading
    val minHeight = if (companionHeight) AppTheme.rowMinHeight else 48.dp
    val shape = RoundedCornerShape(if (companionHeight) AppTheme.Radius.input else AppTheme.Radius.button)

    Button(
        onClick = onClick,
        enabled = isInteractive,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = tint,
            contentColor = Color.White,
            disabledContainerColor = tint.copy(alpha = 0.45f),
            disabledContentColor = Color.White.copy(alpha = 0.80f)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 0.dp,
            pressedElevation = 0.dp,
            disabledElevation = 0.dp
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        modifier = modifier
            .then(if (companionHeight) Modifier else Modifier.fillMaxWidth())
            .defaultMinSize(minHeight = minHeight)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = Color.White
                )
            } else if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = title,
                style = AppTheme.AppTypography.songTitle,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Tonal/outlined Material 3 secondary button (`48.dp`).
 */
@Composable
fun AppSecondaryButton(
    title: String,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    tint: Color = AppTheme.accent,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val shape = RoundedCornerShape(AppTheme.Radius.button)
    val contentColor = if (isDark) {
        tint.brightened(minimumBrightness = 0.90f, maxSaturation = 0.65f)
    } else {
        tint
    }
    val containerColor = tint.copy(alpha = if (isDark) 0.14f else 0.08f)
    val borderColor = tint.copy(alpha = if (isDark) 0.34f else 0.22f)

    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = shape,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.04f),
            disabledContentColor = contentColor.copy(alpha = 0.42f)
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (enabled) borderColor else borderColor.copy(alpha = 0.12f)
        ),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 0.dp),
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 48.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
            }
            Text(
                text = title,
                style = AppTheme.AppTypography.songTitle,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * Compact capsule action button (`inlineAction` 12sp, single-line non-wrapping, dark-mode brightened tint text,
 * `14.dp` spinner when [isLoading]). Pass `tint = Color.Unspecified` or `isNeutral = true` for neutral secondary chrome.
 */
@Composable
fun AppInlineButton(
    title: String,
    icon: ImageVector? = null,
    isLoading: Boolean = false,
    enabled: Boolean = true,
    tint: Color = AppTheme.accent,
    isNeutral: Boolean = false,
    isSolidActive: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TintedActionCapsuleSurface(
        tint = tint,
        isNeutral = isNeutral,
        isSolidActive = isSolidActive,
        enabled = enabled && !isLoading,
        dimmed = !enabled,
        modifier = modifier,
        onClick = onClick
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                strokeWidth = 1.8.dp,
                color = LocalContentColor.current
            )
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = LocalContentColor.current
            )
        }
        Text(
            text = title,
            style = AppTheme.AppTypography.inlineAction,
            color = LocalContentColor.current,
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * Compact capsule dropdown trigger with `Icons.Rounded.UnfoldMore` (`14.dp`), matching [AppInlineButton] chrome.
 */
@Composable
fun AppMenuCapsule(
    title: String,
    tint: Color = AppTheme.accent,
    isNeutral: Boolean = false,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    TintedActionCapsuleSurface(
        tint = tint,
        isNeutral = isNeutral,
        enabled = enabled,
        dimmed = !enabled,
        modifier = modifier,
        onClick = onClick
    ) {
        Text(
            text = title,
            style = AppTheme.AppTypography.inlineAction,
            color = LocalContentColor.current,
            maxLines = 1,
            softWrap = false
        )
        Icon(
            imageVector = Icons.Rounded.UnfoldMore,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = LocalContentColor.current
        )
    }
}

@Composable
private fun TintedActionCapsuleSurface(
    tint: Color,
    isNeutral: Boolean,
    isSolidActive: Boolean = false,
    enabled: Boolean,
    dimmed: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val useNeutral = !isSolidActive && (isNeutral || tint == Color.Unspecified)

    val fgColor = when {
        isSolidActive -> Color.White
        useNeutral -> MaterialTheme.colorScheme.onSurfaceVariant
        isDark -> tint.brightened(minimumBrightness = 0.92f, maxSaturation = 0.65f)
        else -> tint
    }

    val bgColor = when {
        isSolidActive -> tint
        useNeutral -> MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.10f else 0.06f)
        else -> tint.copy(alpha = if (isDark) 0.18f else 0.11f)
    }

    val strokeColor = when {
        isSolidActive -> Color.Transparent
        useNeutral -> MaterialTheme.colorScheme.outline.copy(alpha = if (isDark) 0.42f else 0.35f)
        else -> tint.copy(alpha = if (isDark) 0.35f else 0.22f)
    }

    val interactionSource = remember { MutableInteractionSource() }

    CompositionLocalProvider(LocalContentColor provides fgColor) {
        Row(
            modifier = modifier
                .alpha(if (dimmed) 0.45f else 1f)
                .clip(CircleShape)
                .background(bgColor, CircleShape)
                .then(
                    if (isSolidActive) {
                        Modifier
                    } else {
                        Modifier.border(0.8.dp, strokeColor, CircleShape)
                    }
                )
                .clickable(
                    interactionSource = interactionSource,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick
                )
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs),
            content = content
        )
    }
}

/**
 * 36dp circular tonal icon button inside a 48dp accessible hit target.
 */
@Composable
fun AppCircleIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val borderColor = if (isDark) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .size(36.dp)
                .alpha(if (enabled) 1f else 0.42f)
                .clip(CircleShape)
                .indication(interactionSource, LocalIndication.current),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = tint,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(1.dp, borderColor)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Unified single-choice segmented control with consistent brand container, adaptive border,
 * and `inlineAction` typography across all screens and bottom sheets.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val activeBorder = AppTheme.accentText.copy(alpha = 0.55f)
    val colors = SegmentedButtonDefaults.colors(
        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        activeBorderColor = activeBorder,
        inactiveContainerColor = MaterialTheme.colorScheme.surface,
        inactiveContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        inactiveBorderColor = MaterialTheme.colorScheme.outlineVariant
    )

    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selectedIndex == index,
                onClick = { onSelect(index) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                colors = colors,
                icon = {}
            ) {
                Text(
                    text = label,
                    style = AppTheme.AppTypography.inlineAction,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
