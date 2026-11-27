package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppLabeledSliderRow
import dev.sorrowrain.maimaidata.components.AppMenuCapsule
import dev.sorrowrain.maimaidata.components.AppPrimaryButton
import dev.sorrowrain.maimaidata.components.AppSecondaryButton
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppSheetHeader
import dev.sorrowrain.maimaidata.components.FilterCapsuleChip
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import dev.sorrowrain.maimaidata.models.SongSortOption
import dev.sorrowrain.maimaidata.services.SongFilter
import dev.sorrowrain.maimaidata.theme.AppSurfaceLevel
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalSurfaceLevel
import java.util.Locale

/**
 * Exact 1:1 structural parity with iOS `SearchFilterSheet.swift`:
 * - Card 1: Coupled 2-slider (`等级` ALL..15 + `小数` OFF.. .9, 15 locks .0/OFF) with sort `AppMenuCapsule` dropdown
 * - Card 2: `歌曲分类` multi-select FlowRow
 * - Card 3: `收录版本` multi-select FlowRow
 * - Bottom bar: `重置选项` + `应用筛选`
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SearchFilterSheet(
    criteria: SongFilterCriteria,
    region: MaimaiConstants.ServerRegion,
    onApply: (SongFilterCriteria) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val initialPruned = remember(criteria, region) { SongFilter.prunedCriteria(criteria, region) }
    var draft by remember(initialPruned) { mutableStateOf(initialPruned) }
    var sortMenuExpanded by remember { mutableStateOf(false) }
    val isLargeFont = LocalDensity.current.fontScale >= 1.2f

    var levelSliderIndex by remember(initialPruned) {
        val initial = if (initialPruned.useExactDS) {
            val (intPart, _) = MaimaiConstants.splitExactDS(initialPruned.exactDS)
            MaimaiConstants.allLevels.indexOf("$intPart").takeIf { it >= 0 } ?: 1
        } else {
            MaimaiConstants.allLevels.indexOf(initialPruned.selectedLevel).coerceAtLeast(0)
        }
        mutableIntStateOf(initial)
    }

    var preferredDecIdx by remember(initialPruned) {
        val initial = if (initialPruned.useExactDS) {
            val (_, decPart) = MaimaiConstants.splitExactDS(initialPruned.exactDS)
            decPart + 1
        } else {
            0
        }
        mutableIntStateOf(initial)
    }

    val currentLevelIndex = levelSliderIndex.coerceIn(0, MaimaiConstants.allLevels.lastIndex)
    val baseIntegerLevel = MaimaiConstants.baseIntegerLevel(currentLevelIndex)
    val isLevel15 = currentLevelIndex > 0 && baseIntegerLevel >= MaimaiConstants.MAX_LEVEL_INTEGER
    val currentDecIndex = MaimaiConstants.effectiveFilterDecimalIndex(
        levelIndex = currentLevelIndex,
        baseIntLevel = baseIntegerLevel,
        preferredDecIdx = preferredDecIdx
    )

    fun syncFromSliders(newLvIdx: Int, newDecIdx: Int, changedDecimal: Boolean) {
        var lvIdx = newLvIdx.coerceIn(0, MaimaiConstants.allLevels.lastIndex)
        val intLvl = MaimaiConstants.baseIntegerLevel(lvIdx)

        if (changedDecimal) {
            if (!(lvIdx > 0 && intLvl >= MaimaiConstants.MAX_LEVEL_INTEGER)) {
                val clampedDec = newDecIdx.coerceIn(0, 10)
                preferredDecIdx = clampedDec
                if (clampedDec > 0 && lvIdx == 0) {
                    lvIdx = 1
                }
            }
        } else if (lvIdx == 0) {
            preferredDecIdx = 0
        }

        levelSliderIndex = lvIdx
        val resolvedInt = MaimaiConstants.baseIntegerLevel(lvIdx)
        val decIdx = MaimaiConstants.effectiveFilterDecimalIndex(
            levelIndex = lvIdx,
            baseIntLevel = resolvedInt,
            preferredDecIdx = preferredDecIdx
        )

        draft = when {
            lvIdx == 0 -> draft.copy(useExactDS = false, selectedLevel = "ALL")
            decIdx == 0 -> draft.copy(useExactDS = false, selectedLevel = MaimaiConstants.allLevels[lvIdx])
            else -> draft.copy(
                useExactDS = true,
                exactDS = MaimaiConstants.composeExactDS(resolvedInt, decIdx - 1),
                selectedLevel = "$resolvedInt"
            )
        }
    }

    val firstSliderLabelText = when {
        currentLevelIndex == 0 -> "ALL"
        currentDecIndex > 0 -> "$baseIntegerLevel"
        else -> MaimaiConstants.allLevels[currentLevelIndex]
    }
    val secondSliderLabelText = when {
        currentDecIndex == 0 -> "OFF"
        isLevel15 -> ".0"
        else -> ".${currentDecIndex - 1}"
    }

    val levelHeaderText = when {
        draft.useExactDS -> String.format(Locale.US, "定数筛选：%.1f", draft.exactDS)
        draft.selectedLevel == "ALL" -> "等级与定数筛选"
        else -> "等级筛选：LEVEL ${draft.selectedLevel}"
    }

    val versionChips = remember(region) {
        MaimaiConstants.searchVersionChips(region)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp
    ) {
        CompositionLocalProvider(LocalSurfaceLevel provides AppSurfaceLevel.Sheet) {
            Column(modifier = Modifier.fillMaxWidth()) {
                AppSheetHeader(
                    title = "筛选与排序",
                    onClose = onDismiss,
                    modifier = Modifier.padding(horizontal = AppTheme.Space.s)
                )

                AppInsetDivider()

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
                ) {
                    // 1. 等级 / 定数双滑块 + 排序胶囊菜单
                    AppSectionCard(
                        title = levelHeaderText,
                        trailing = {
                            Box {
                                AppMenuCapsule(
                                    title = draft.sortOption.label,
                                    onClick = { sortMenuExpanded = true }
                                )
                                DropdownMenu(
                                    expanded = sortMenuExpanded,
                                    onDismissRequest = { sortMenuExpanded = false }
                                ) {
                                    SongSortOption.entries.forEach { opt ->
                                        DropdownMenuItem(
                                            text = { Text(opt.label) },
                                            trailingIcon = if (draft.sortOption == opt) {
                                                { Icon(Icons.Rounded.Check, contentDescription = null, tint = AppTheme.accentText) }
                                            } else {
                                                null
                                            },
                                            onClick = {
                                                draft = draft.copy(sortOption = opt)
                                                sortMenuExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        AppInsetGroup(padding = AppTheme.Space.m) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                            ) {
                                AppLabeledSliderRow(
                                    label = "等级",
                                    valueText = firstSliderLabelText,
                                    value = currentLevelIndex,
                                    onValueChange = { syncFromSliders(it, currentDecIndex, changedDecimal = false) },
                                    valueRange = 0..MaimaiConstants.allLevels.lastIndex,
                                    isActive = currentLevelIndex > 0
                                )

                                AppLabeledSliderRow(
                                    label = "小数",
                                    valueText = secondSliderLabelText,
                                    value = currentDecIndex,
                                    onValueChange = { syncFromSliders(currentLevelIndex, it, changedDecimal = true) },
                                    valueRange = 0..10,
                                    isActive = currentDecIndex > 0,
                                    enabled = !isLevel15
                                )
                            }
                        }
                    }

                    // 2. 歌曲分类
                    AppSectionCard(
                        title = "歌曲分类",
                        subtitle = if (draft.selectedGenres.isEmpty()) {
                            "默认包含除宴会场外的全部分类"
                        } else {
                            "已选 ${draft.selectedGenres.size} 项"
                        },
                        trailing = if (draft.selectedGenres.isNotEmpty()) {
                            {
                                AppInlineButton(
                                    title = "清空",
                                    isNeutral = true,
                                    onClick = { draft = draft.copy(selectedGenres = emptySet()) }
                                )
                            }
                        } else {
                            null
                        }
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                        ) {
                            MaimaiConstants.allGenres.forEach { genre ->
                                val selected = genre in draft.selectedGenres
                                FilterCapsuleChip(
                                    title = genre,
                                    isSelected = selected,
                                    tint = MaimaiConstants.genreColors(genre).bg,
                                    onClick = {
                                        val next = if (selected) draft.selectedGenres - genre else draft.selectedGenres + genre
                                        draft = draft.copy(selectedGenres = next)
                                    }
                                )
                            }
                        }
                    }

                    // 3. 收录版本
                    AppSectionCard(
                        title = "收录版本",
                        subtitle = if (draft.selectedVersions.isEmpty()) {
                            "全部版本"
                        } else {
                            "已选 ${draft.selectedVersions.size} 个版本"
                        },
                        trailing = if (draft.selectedVersions.isNotEmpty()) {
                            {
                                AppInlineButton(
                                    title = "清空",
                                    isNeutral = true,
                                    onClick = { draft = draft.copy(selectedVersions = emptySet()) }
                                )
                            }
                        } else {
                            null
                        }
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                        ) {
                            versionChips.forEach { ver ->
                                val selected = ver in draft.selectedVersions
                                FilterCapsuleChip(
                                    title = ver,
                                    isSelected = selected,
                                    tint = AppTheme.accent,
                                    onClick = {
                                        val next = if (selected) draft.selectedVersions - ver else draft.selectedVersions + ver
                                        draft = draft.copy(selectedVersions = next)
                                    }
                                )
                            }
                        }
                    }
                }

                AppInsetDivider()

                val resetAction = {
                    draft = SongFilterCriteria(
                        keyword = draft.keyword,
                        onlyFavorites = draft.onlyFavorites
                    )
                    levelSliderIndex = 0
                    preferredDecIdx = 0
                }
                val applyAction = {
                    onApply(SongFilter.prunedCriteria(draft, region))
                }

                if (isLargeFont) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.itemSpacing),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        AppPrimaryButton(
                            title = "应用筛选",
                            icon = Icons.Rounded.Check,
                            onClick = applyAction
                        )
                        AppSecondaryButton(
                            title = "重置选项",
                            onClick = resetAction
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.itemSpacing),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        AppSecondaryButton(
                            title = "重置选项",
                            modifier = Modifier.weight(1f),
                            onClick = resetAction
                        )
                        AppPrimaryButton(
                            title = "应用筛选",
                            icon = Icons.Rounded.Check,
                            modifier = Modifier.weight(1f),
                            onClick = applyAction
                        )
                    }
                }
            }
        }
    }
}
