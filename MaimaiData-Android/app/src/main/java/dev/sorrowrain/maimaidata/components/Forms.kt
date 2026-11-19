package dev.sorrowrain.maimaidata.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.models.brightened
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Clean Material 3 outlined text input field styled with [AppTheme] tokens (`Radius.input = 12.dp`).
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    monospaced: Boolean = false
) {
    val textStyle = if (monospaced) {
        AppTheme.AppTypography.input.copy(fontFamily = FontFamily.Monospace)
    } else {
        AppTheme.AppTypography.input
    }
    val shape = RoundedCornerShape(AppTheme.Radius.input)
    val colors = appTextFieldColors()

    val fieldContent: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AppTheme.rowMinHeight),
            textStyle = textStyle,
            placeholder = {
                Text(
                    text = placeholder,
                    style = AppTheme.AppTypography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            singleLine = singleLine,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            shape = shape,
            colors = colors
        )
    }

    if (!label.isNullOrEmpty()) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = AppTheme.AppTypography.tag,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            fieldContent(Modifier)
        }
    } else {
        fieldContent(modifier)
    }
}

/**
 * Password / token input field with visibility toggle (`Icons.Rounded.Visibility` / `VisibilityOff`).
 */
@Composable
fun AppSecureField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    keyboardActions: KeyboardActions = KeyboardActions.Default
) {
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val shape = RoundedCornerShape(AppTheme.Radius.input)
    val colors = appTextFieldColors()

    val fieldContent: @Composable (Modifier) -> Unit = { fieldModifier ->
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = fieldModifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = AppTheme.rowMinHeight),
            textStyle = AppTheme.AppTypography.input,
            placeholder = {
                Text(
                    text = placeholder,
                    style = AppTheme.AppTypography.body,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) {
                            Icons.Rounded.VisibilityOff
                        } else {
                            Icons.Rounded.Visibility
                        },
                        contentDescription = if (passwordVisible) "隐藏密码" else "显示密码",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            },
            visualTransformation = if (passwordVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            keyboardActions = keyboardActions,
            shape = shape,
            colors = colors
        )
    }

    if (!label.isNullOrEmpty()) {
        Column(
            modifier = modifier,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            Text(
                text = label,
                style = AppTheme.AppTypography.tag,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            fieldContent(Modifier)
        }
    } else {
        fieldContent(modifier)
    }
}

@Composable
private fun appTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    focusedBorderColor = AppTheme.accentText,
    unfocusedBorderColor = if (LocalIsDarkTheme.current) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    },
    cursorColor = AppTheme.accentText,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.60f),
    focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant
)

/**
 * Minimalist tick-free discrete integer slider with a smooth 6dp capsule track, circular elevated thumb,
 * and subtle haptic step ticks (avoids Material 3's cluttered tick dots and vertical bar thumb).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSlider(
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    modifier: Modifier = Modifier,
    tint: Color = AppTheme.accent,
    enabled: Boolean = true
) {
    val isDark = LocalIsDarkTheme.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()

    val minInt = valueRange.first
    val maxInt = valueRange.last.coerceAtLeast(minInt)
    val hasRange = maxInt > minInt
    val clampedValue = value.coerceIn(minInt, maxInt)
    val safeEndFloat = if (hasRange) maxInt.toFloat() else minInt.toFloat() + 1f

    var isDragging by remember { mutableStateOf(false) }
    var dragRawFloat by remember { mutableFloatStateOf(clampedValue.toFloat()) }
    val settledAnim = remember { Animatable(clampedValue.toFloat()) }

    LaunchedEffect(clampedValue, isDragging) {
        if (!isDragging) {
            settledAnim.animateTo(
                targetValue = clampedValue.toFloat(),
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow)
            )
        }
    }

    val displayedFloat = if (isDragging) dragRawFloat else settledAnim.value
    val activeStart = if (isDark) tint.brightened(minimumBrightness = 0.88f, maxSaturation = 0.78f) else tint
    val activeEnd = tint.brightened(minimumBrightness = 0.96f, maxSaturation = 0.70f)
    val inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (isDark) 0.16f else 0.10f)

    Slider(
        value = displayedFloat.coerceIn(minInt.toFloat(), safeEndFloat),
        onValueChange = { raw ->
            if (!hasRange) return@Slider
            isDragging = true
            dragRawFloat = raw
            val snapped = raw.roundToInt().coerceIn(minInt, maxInt)
            if (snapped != clampedValue) {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onValueChange(snapped)
            }
        },
        onValueChangeFinished = {
            if (!hasRange) return@Slider
            scope.launch {
                settledAnim.snapTo(dragRawFloat)
                isDragging = false
            }
        },
        valueRange = minInt.toFloat()..safeEndFloat,
        steps = 0,
        enabled = enabled && hasRange,
        modifier = modifier.height(32.dp),
        thumb = {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .shadow(elevation = 3.dp, shape = CircleShape, clip = false)
                    .background(Color.White, CircleShape)
                    .border(
                        width = 1.8.dp,
                        color = activeStart.copy(alpha = if (enabled) 0.90f else 0.40f),
                        shape = CircleShape
                    )
            )
        },
        track = { sliderState ->
            val span = (sliderState.valueRange.endInclusive - sliderState.valueRange.start).coerceAtLeast(0.0001f)
            val fraction = ((sliderState.value - sliderState.valueRange.start) / span).coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(inactiveTrackColor)
            ) {
                if (fraction > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .clip(CircleShape)
                            .background(
                                Brush.horizontalGradient(
                                    colors = listOf(activeStart, activeEnd)
                                )
                            )
                    )
                }
            }
        }
    )
}

/**
 * Standard labeled discrete integer slider row (`label` + [AppValueBadge] + [AppSlider]).
 * Shared by `SearchFilterSheet` and `RatingCalculatorView`.
 */
@Composable
fun AppLabeledSliderRow(
    label: String,
    valueText: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    valueRange: IntRange,
    isActive: Boolean = true,
    accessibilityLabel: String = label,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
    ) {
        Text(
            text = label,
            style = AppTheme.AppTypography.inlineAction,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(30.dp)
        )
        AppValueBadge(text = valueText, isActive = isActive)
        AppSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = "$accessibilityLabel：$valueText" }
        )
    }
}
