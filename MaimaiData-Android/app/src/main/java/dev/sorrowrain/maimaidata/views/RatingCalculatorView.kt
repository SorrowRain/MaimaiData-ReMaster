package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppLabeledSliderRow
import dev.sorrowrain.maimaidata.components.AppPrimaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppSegmentedControl
import dev.sorrowrain.maimaidata.components.AppTextField
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.RatingTierTable
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import java.util.Locale

@Composable
fun RatingCalculatorScreen(
    onBack: () -> Unit
) {
    val store = LocalDataStore.current
    val focusManager = LocalFocusManager.current

    var selectedMode by rememberSaveable { mutableIntStateOf(0) } // 0 = 单曲定数算分, 1 = 目标总分反推

    // Single-song calculator state (1..15 + .0 .. .9, default 14.8)
    var intPart by rememberSaveable { mutableIntStateOf(14) }
    var decPart by rememberSaveable { mutableIntStateOf(8) }
    var customAchiInput by rememberSaveable { mutableStateOf("") }

    // Target total rating reverse state
    var targetRatingInput by rememberSaveable { mutableStateOf("15000") }
    var targetRows by remember {
        mutableStateOf(RatingCalculator.reverseCalculate(targetTotalRating = 15000))
    }
    var targetError by remember { mutableStateOf<String?>(null) }

    val selectedDS = intPart.coerceIn(1, 15) + decPart.coerceIn(0, 9) / 10.0

    val trimmedCustom = customAchiInput.trim()
    val parsedCustomAchi = trimmedCustom.toDoubleOrNull()?.takeIf { it >= 0.0 }
    val customRatingResult = parsedCustomAchi?.let { achi ->
        RatingCalculator.singleSongRating(
            ds = selectedDS,
            achievement = achi
        )
    }
    val customInputError = if (trimmedCustom.isNotEmpty() && parsedCustomAchi == null) {
        "请输入有效的达成率数值"
    } else {
        null
    }

    val tierRows = remember(selectedDS, parsedCustomAchi, store.serverRegion) {
        RatingCalculator.buildTierRows(
            ds = selectedDS,
            currentAchievement = parsedCustomAchi,
            includeAPBonusRow = store.serverRegion.usesAPBonus
        )
    }

    fun runReverseCalculation() {
        focusManager.clearFocus()
        val target = targetRatingInput.trim().toIntOrNull() ?: 0
        val rows = RatingCalculator.reverseCalculate(targetTotalRating = target)
        targetRows = rows
        targetError = when {
            target <= 0 -> "请输入大于 0 的目标 Rating"
            rows.isEmpty() -> "当前目标无法生成达成率建议，请检查数值范围"
            else -> null
        }
    }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "Rating 计算器",
                    onBack = onBack
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                AppSegmentedControl(
                    options = listOf("单曲算分", "目标反推"),
                    selectedIndex = selectedMode,
                    onSelect = { index ->
                        focusManager.clearFocus()
                        selectedMode = index
                    }
                )

                if (selectedMode == 0) {
                    // Mode 1: Dual-Slider Constant Picker + Tier Reference Table
                    AppSectionCard(
                        title = String.format(Locale.US, "谱面定数：%.1f", selectedDS),
                        subtitle = "拖动滑块选择定数，下方即时展示各档得分",
                        trailing = if (customRatingResult != null) {
                            {
                                AppCapsuleLabel(
                                    text = "$customRatingResult",
                                    style = CapsuleStyle.Metric(prefix = "Ra")
                                )
                            }
                        } else {
                            null
                        }
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            AppInsetGroup(padding = AppTheme.Space.m) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                                ) {
                                    AppLabeledSliderRow(
                                        label = "整数",
                                        valueText = "$intPart",
                                        value = intPart,
                                        onValueChange = { intPart = it },
                                        valueRange = 1..15,
                                        accessibilityLabel = "定数整数部分"
                                    )

                                    AppLabeledSliderRow(
                                        label = "小数",
                                        valueText = ".$decPart",
                                        value = decPart,
                                        onValueChange = { decPart = it },
                                        valueRange = 0..9,
                                        accessibilityLabel = "定数小数部分"
                                    )
                                }
                            }

                            AppTextField(
                                value = customAchiInput,
                                onValueChange = { customAchiInput = it },
                                label = "精确达成率试算（可选）",
                                placeholder = "输入任意达成率，如 100.2350",
                                monospaced = true,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Decimal,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { focusManager.clearFocus() }
                                )
                            )

                            if (customInputError != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = AppTheme.destructiveText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = customInputError,
                                        style = AppTheme.AppTypography.caption,
                                        color = AppTheme.destructiveText
                                    )
                                }
                            }
                        }
                    }

                    AppSectionCard(title = "分段得分对照") {
                        RatingTierTable(
                            rows = tierRows,
                            showB50Column = false,
                            region = store.serverRegion
                        )
                    }
                } else {
                    // Mode 2: Target Total Rating Reverse Lookup
                    AppSectionCard(
                        title = "目标 Rating 达成率反推",
                        subtitle = "按 50 首均分推算所需最低定数与达成率"
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                            ) {
                                AppTextField(
                                    value = targetRatingInput,
                                    onValueChange = { targetRatingInput = it.filter(Char::isDigit).take(6) },
                                    label = "目标总 Rating",
                                    placeholder = "如 15000",
                                    monospaced = true,
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Number,
                                        imeAction = ImeAction.Done
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onDone = { runReverseCalculation() }
                                    ),
                                    modifier = Modifier.weight(1f)
                                )

                                AppPrimaryButton(
                                    title = "推算",
                                    companionHeight = true,
                                    onClick = { runReverseCalculation() }
                                )
                            }

                            if (targetError != null) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = AppTheme.destructiveText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Text(
                                        text = targetError.orEmpty(),
                                        style = AppTheme.AppTypography.caption,
                                        color = AppTheme.destructiveText
                                    )
                                }
                            }

                            if (targetRows.isNotEmpty()) {
                                AppInsetGroup {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                horizontal = AppTheme.Space.m,
                                                vertical = AppTheme.Space.s
                                            ),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "定数",
                                            style = AppTheme.AppTypography.caption2Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.width(48.dp)
                                        )
                                        Text(
                                            text = "所需达成率",
                                            style = AppTheme.AppTypography.caption2Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = "单曲 Ra",
                                            style = AppTheme.AppTypography.caption2Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.width(64.dp)
                                        )
                                        Text(
                                            text = "50首总计",
                                            style = AppTheme.AppTypography.caption2Bold,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            textAlign = TextAlign.End,
                                            modifier = Modifier.width(72.dp)
                                        )
                                    }

                                    targetRows.forEach { row ->
                                        AppInsetDivider()
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(
                                                    horizontal = AppTheme.Space.m,
                                                    vertical = AppTheme.Space.s
                                                ),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = String.format(Locale.US, "%.1f", row.level),
                                                style = AppTheme.AppTypography.captionMeta,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.width(48.dp)
                                            )
                                            Text(
                                                text = row.achievementText,
                                                style = AppTheme.AppTypography.captionMono,
                                                color = MaterialTheme.colorScheme.onSurface,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = "${row.singleRating}",
                                                style = AppTheme.AppTypography.captionMeta,
                                                color = AppTheme.accentText,
                                                textAlign = TextAlign.End,
                                                modifier = Modifier.width(64.dp)
                                            )
                                            Text(
                                                text = "${row.totalRating}",
                                                style = AppTheme.AppTypography.captionMono,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                textAlign = TextAlign.End,
                                                modifier = Modifier.width(72.dp)
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
    }
}
