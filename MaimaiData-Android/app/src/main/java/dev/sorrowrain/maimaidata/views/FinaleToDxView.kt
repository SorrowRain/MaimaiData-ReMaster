package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTextField
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme

@Composable
fun FinaleToDxScreen(
    onBack: () -> Unit
) {
    val focusManager = LocalFocusManager.current

    var tapP by rememberSaveable { mutableStateOf("") }
    var tapGr by rememberSaveable { mutableStateOf("") }
    var tapGd by rememberSaveable { mutableStateOf("") }
    var tapM by rememberSaveable { mutableStateOf("") }

    var holdP by rememberSaveable { mutableStateOf("") }
    var holdGr by rememberSaveable { mutableStateOf("") }
    var holdGd by rememberSaveable { mutableStateOf("") }
    var holdM by rememberSaveable { mutableStateOf("") }

    var slideP by rememberSaveable { mutableStateOf("") }
    var slideGr by rememberSaveable { mutableStateOf("") }
    var slideGd by rememberSaveable { mutableStateOf("") }
    var slideM by rememberSaveable { mutableStateOf("") }

    var breakP by rememberSaveable { mutableStateOf("") }
    var breakGr by rememberSaveable { mutableStateOf("") }
    var breakGd by rememberSaveable { mutableStateOf("") }
    var breakM by rememberSaveable { mutableStateOf("") }

    var breakScore by rememberSaveable { mutableStateOf("") }

    fun parseIntField(raw: String): Int = raw.trim().toIntOrNull() ?: 0
    fun sanitizeNoteCount(raw: String): String = raw.filter(Char::isDigit).take(4)

    val hasAnyInput = listOf(
        tapP, tapGr, tapGd, tapM,
        holdP, holdGr, holdGd, holdM,
        slideP, slideGr, slideGd, slideM,
        breakP, breakGr, breakGd, breakM,
        breakScore
    ).any { it.isNotBlank() }

    val (liveResultText, liveHintText, isErrorHint) = remember(
        tapP, tapGr, tapGd, tapM,
        holdP, holdGr, holdGd, holdM,
        slideP, slideGr, slideGd, slideM,
        breakP, breakGr, breakGd, breakM,
        breakScore
    ) {
        if (!hasAnyInput) {
            Triple("—", null as String?, false)
        } else {
            val counts = RatingCalculator.FinaleNoteCounts(
                tapPerfect = parseIntField(tapP),
                tapGreat = parseIntField(tapGr),
                tapGood = parseIntField(tapGd),
                tapMiss = parseIntField(tapM),
                holdPerfect = parseIntField(holdP),
                holdGreat = parseIntField(holdGr),
                holdGood = parseIntField(holdGd),
                holdMiss = parseIntField(holdM),
                slidePerfect = parseIntField(slideP),
                slideGreat = parseIntField(slideGr),
                slideGood = parseIntField(slideGd),
                slideMiss = parseIntField(slideM),
                breakPerfect = parseIntField(breakP),
                breakGreat = parseIntField(breakGr),
                breakGood = parseIntField(breakGd),
                breakMiss = parseIntField(breakM),
                breakScore = parseIntField(breakScore)
            )
            try {
                val converted = RatingCalculator.convertFinaleToDx(counts)
                Triple(converted, null as String?, false)
            } catch (_: RatingCalculator.FinaleConversionException.MissingNotesOrBreak) {
                Triple("—", "请至少填写 Break 判定数量以换算 DX 达成率", false)
            } catch (err: RatingCalculator.FinaleConversionException) {
                Triple("—", err.message, true)
            } catch (err: Throwable) {
                Triple("—", err.message ?: "输入数据有误", true)
            }
        }
    }

    fun clearAll() {
        focusManager.clearFocus()
        tapP = ""; tapGr = ""; tapGd = ""; tapM = ""
        holdP = ""; holdGr = ""; holdGd = ""; holdM = ""
        slideP = ""; slideGr = ""; slideGd = ""; slideM = ""
        breakP = ""; breakGr = ""; breakGd = ""; breakM = ""
        breakScore = ""
    }

    AppPageBackground {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "旧框达成率换算",
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
                // 1. Live Converted DX Achievement Result Card
                AppSectionCard(
                    title = "换算结果",
                    subtitle = "根据旧框判定实时换算对应的 DX 达成率区间或精确值"
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        AppInsetGroup(padding = AppTheme.Space.m) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "DX 达成率",
                                    style = AppTheme.AppTypography.inlineAction,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = liveResultText,
                                    style = AppTheme.AppTypography.heroTitle,
                                    color = AppTheme.accentText,
                                    textAlign = TextAlign.End
                                )
                            }
                        }

                        if (!liveHintText.isNullOrEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                            ) {
                                if (isErrorHint) {
                                    Icon(
                                        imageVector = Icons.Rounded.ErrorOutline,
                                        contentDescription = null,
                                        tint = AppTheme.destructiveText,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                                Text(
                                    text = liveHintText,
                                    style = AppTheme.AppTypography.caption,
                                    color = if (isErrorHint) {
                                        AppTheme.destructiveText
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                    }
                }

                // 2. 4x4 Note Judgment Matrix Card
                AppSectionCard(
                    title = "各音符判定数",
                    subtitle = "未填写的项默认按 0 计算",
                    trailing = {
                        AppInlineButton(
                            title = "清空重填",
                            icon = Icons.Rounded.Refresh,
                            isNeutral = true,
                            enabled = hasAnyInput,
                            onClick = { clearAll() }
                        )
                    }
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                    ) {
                        FinaleNoteJudgmentRow(
                            title = "TAP",
                            dotColor = AppTheme.Palette.noteTap,
                            perfect = tapP,
                            onPerfectChange = { tapP = sanitizeNoteCount(it) },
                            great = tapGr,
                            onGreatChange = { tapGr = sanitizeNoteCount(it) },
                            good = tapGd,
                            onGoodChange = { tapGd = sanitizeNoteCount(it) },
                            miss = tapM,
                            onMissChange = { tapM = sanitizeNoteCount(it) }
                        )

                        AppInsetDivider()

                        FinaleNoteJudgmentRow(
                            title = "HOLD",
                            dotColor = AppTheme.Palette.noteHold,
                            perfect = holdP,
                            onPerfectChange = { holdP = sanitizeNoteCount(it) },
                            great = holdGr,
                            onGreatChange = { holdGr = sanitizeNoteCount(it) },
                            good = holdGd,
                            onGoodChange = { holdGd = sanitizeNoteCount(it) },
                            miss = holdM,
                            onMissChange = { holdM = sanitizeNoteCount(it) }
                        )

                        AppInsetDivider()

                        FinaleNoteJudgmentRow(
                            title = "SLIDE",
                            dotColor = AppTheme.Palette.noteSlide,
                            perfect = slideP,
                            onPerfectChange = { slideP = sanitizeNoteCount(it) },
                            great = slideGr,
                            onGreatChange = { slideGr = sanitizeNoteCount(it) },
                            good = slideGd,
                            onGoodChange = { slideGd = sanitizeNoteCount(it) },
                            miss = slideM,
                            onMissChange = { slideM = sanitizeNoteCount(it) }
                        )

                        AppInsetDivider()

                        FinaleNoteJudgmentRow(
                            title = "BREAK",
                            dotColor = AppTheme.Palette.noteBreak,
                            perfect = breakP,
                            onPerfectChange = { breakP = sanitizeNoteCount(it) },
                            great = breakGr,
                            onGreatChange = { breakGr = sanitizeNoteCount(it) },
                            good = breakGd,
                            onGoodChange = { breakGd = sanitizeNoteCount(it) },
                            miss = breakM,
                            onMissChange = { breakM = sanitizeNoteCount(it) }
                        )
                    }
                }

                // 3. Optional Break Total Score Card
                AppSectionCard(
                    title = "Break 绝赞总分（可选）",
                    subtitle = "Break 全 Perfect 时填写旧框绝赞总分可计算精确值"
                ) {
                    AppTextField(
                        value = breakScore,
                        onValueChange = { breakScore = it.filter(Char::isDigit).take(7) },
                        placeholder = "Break 总分（可留空或填 0）",
                        monospaced = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        )
                    )
                }
            }
        }
    }
}

@Composable
private fun FinaleNoteJudgmentRow(
    title: String,
    dotColor: Color,
    perfect: String,
    onPerfectChange: (String) -> Unit,
    great: String,
    onGreatChange: (String) -> Unit,
    good: String,
    onGoodChange: (String) -> Unit,
    miss: String,
    onMissChange: (String) -> Unit
) {
    val keyboardOptions = KeyboardOptions(
        keyboardType = KeyboardType.Number,
        imeAction = ImeAction.Next
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(dotColor, CircleShape)
            )
            Text(
                text = title,
                style = AppTheme.AppTypography.subheadlineBold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            AppTextField(
                value = perfect,
                onValueChange = onPerfectChange,
                label = "Perfect",
                placeholder = "0",
                monospaced = true,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = great,
                onValueChange = onGreatChange,
                label = "Great",
                placeholder = "0",
                monospaced = true,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = good,
                onValueChange = onGoodChange,
                label = "Good",
                placeholder = "0",
                monospaced = true,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.weight(1f)
            )
            AppTextField(
                value = miss,
                onValueChange = onMissChange,
                label = "Miss",
                placeholder = "0",
                monospaced = true,
                keyboardOptions = keyboardOptions,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
