package dev.sorrowrain.maimaidata.views

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppEmptyState
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppLoadingState
import dev.sorrowrain.maimaidata.components.AppMetricTile
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppSegmentedControl
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.GenreCapsuleTag
import dev.sorrowrain.maimaidata.components.RatingTierTable
import dev.sorrowrain.maimaidata.components.RecordStatusBadges
import dev.sorrowrain.maimaidata.components.SongCoverBadgeView
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.models.DifficultyType
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.MaxNotesStats
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.RatingCalculator
import dev.sorrowrain.maimaidata.models.ResolvedChart
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme
import java.util.Locale

/**
 * Exact 3-tab layout matching iOS `SongDetailView.swift`:
 * 1. `谱面概览` (Chart Info 3 tiles + 2-row Charter/Record inset + Utage comment + 6-bar Notes + Aliases)
 * 2. `Rating与推分` (3-column RatingTierTable across all 3 server regions when logged in)
 * 3. `容错参考` (4-column Tap/Hold/Slide loss table + 2-3-2 Break cells + SD FiNALE max)
 */
private enum class DetailTab(val title: String) {
    OVERVIEW("谱面概览"),
    RATING("Rating与推分"),
    TOLERANCE("容错参考")
}

@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalLayoutApi::class,
    ExperimentalFoundationApi::class
)
@Composable
fun SongDetailScreen(
    songId: String,
    initialDifficultyIndex: Int?,
    onBack: () -> Unit
) {
    val store = LocalDataStore.current
    val context = LocalContext.current
    val toastState = rememberToastState()

    var activeSongId by rememberSaveable(songId) { mutableStateOf(songId) }
    var lastResolvedSong by remember { mutableStateOf<SongData?>(null) }
    val catalogRevision = store.catalogRevision

    val song: SongData? = remember(activeSongId, catalogRevision, store.songsById, store.songs) {
        val numeric = activeSongId.toIntOrNull() ?: -1
        store.songsById[numeric]
            ?: store.songs.firstOrNull { it.id == activeSongId }
            ?: lastResolvedSong?.let { prev ->
                store.songs.firstOrNull {
                    it.title == prev.title &&
                        it.basicInfo.genre == prev.basicInfo.genre &&
                        it.type == prev.type
                }
            }
    }

    SideEffect {
        if (song != null) {
            lastResolvedSong = song
        }
    }

    var selectedTabOrdinal by rememberSaveable { mutableIntStateOf(0) }
    val selectedTab = DetailTab.entries.getOrElse(selectedTabOrdinal) { DetailTab.OVERVIEW }

    var showCoverZoom by rememberSaveable { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val copyToClipboard: (String, String) -> Unit = { value, label ->
        if (value.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, value))
            toastState.show("已复制$label：$value")
        }
    }

    if (song == null) {
        AppPageBackground {
            Scaffold(
                topBar = { AppTopBar(title = "曲目详情", onBack = onBack) },
                containerColor = Color.Transparent
            ) { innerPadding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    if (store.isLoadingLocalData || store.isSyncingSongs) {
                        AppLoadingState(message = "正在读取曲库…")
                    } else {
                        AppEmptyState(
                            icon = Icons.Rounded.SearchOff,
                            title = "未找到对应曲目数据"
                        )
                    }
                }
            }
        }
        return
    }

    val chartsDesc = remember(song) { song.resolvedCharts.reversed() }

    var selectedRawChartIndex by rememberSaveable(songId, initialDifficultyIndex) {
        val defaultIdx = initialDifficultyIndex?.takeIf { target ->
            song.resolvedCharts.any { it.index == target }
        } ?: chartsDesc.firstOrNull()?.index ?: 0
        mutableIntStateOf(defaultIdx)
    }

    val activeChart: ResolvedChart? = chartsDesc.firstOrNull { it.index == selectedRawChartIndex }
        ?: chartsDesc.firstOrNull()

    val genreColors = MaimaiConstants.genreColors(song.basicInfo.genre)
    val isFav = store.isFavorite(song)
    val counterpart = store.counterpartSong(song)

    AppPageBackground(tint = genreColors.bg) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    AppTopBar(
                        title = "曲目详情",
                        onBack = onBack,
                        actions = {
                            IconButton(onClick = { store.toggleFavorite(song) }) {
                                Icon(
                                    imageVector = if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                    contentDescription = if (isFav) "取消收藏" else "收藏歌曲",
                                    tint = if (isFav) AppTheme.favoritePink else MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    )
                },
                containerColor = Color.Transparent
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = AppTheme.pagePadding,
                        end = AppTheme.pagePadding,
                        top = innerPadding.calculateTopPadding() + AppTheme.Space.m,
                        bottom = innerPadding.calculateBottomPadding() + AppTheme.Space.xl
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
                ) {
                    // 1. Hero Header Card (Cover + Title/Artist + top-right SD/DX badge + 3 Fact Pills: BPM / 歌曲分类 / 歌曲 ID)
                    item(key = "hero_header") {
                        SongHeroHeaderCard(
                            song = song,
                            counterpart = counterpart,
                            onZoomCover = { showCoverZoom = true },
                            onSwitchCounterpart = { target ->
                                val currentDifficulty = activeChart?.difficulty
                                val targetChartsDesc = target.resolvedCharts.reversed()
                                val matchedIdx = currentDifficulty?.let { diff ->
                                    targetChartsDesc.firstOrNull { it.difficulty == diff }?.index
                                } ?: targetChartsDesc.firstOrNull()?.index ?: 0
                                activeSongId = target.id
                                selectedRawChartIndex = matchedIdx
                            },
                            onCopyText = copyToClipboard
                        )
                    }

                    if (chartsDesc.isNotEmpty() && activeChart != null) {
                        // 2. Difficulty Switcher Row
                        item(key = "difficulty_selector") {
                            DifficultySelectorRow(
                                song = song,
                                chartsDesc = chartsDesc,
                                selectedChartIndex = activeChart.index,
                                onSelectChart = { chartIdx -> selectedRawChartIndex = chartIdx }
                            )
                        }

                        if (song.isUtage) {
                            item(key = "utage_overview") {
                                OverviewSection(
                                    song = song,
                                    chart = activeChart,
                                    onCopyText = copyToClipboard
                                )
                            }
                        } else {
                            // 3. 3-Way Segmented Picker: 谱面概览 | Rating与推分 | 容错参考
                            item(key = "detail_segmented_tabs") {
                                AppSegmentedControl(
                                    options = DetailTab.entries.map { it.title },
                                    selectedIndex = selectedTabOrdinal,
                                    onSelect = { selectedTabOrdinal = it }
                                )
                            }

                            when (selectedTab) {
                                DetailTab.OVERVIEW -> {
                                    item(key = "tab_overview") {
                                        OverviewSection(
                                            song = song,
                                            chart = activeChart,
                                            onCopyText = copyToClipboard
                                        )
                                    }
                                }

                                DetailTab.RATING -> {
                                    item(key = "tab_rating") {
                                        RatingSection(
                                            song = song,
                                            chart = activeChart
                                        )
                                    }
                                }

                                DetailTab.TOLERANCE -> {
                                    item(key = "tab_tolerance") {
                                        val metrics = activeChart.toleranceMetrics
                                        if (metrics != null) {
                                            ChartToleranceCard(
                                                metrics = metrics,
                                                finaleTheoreticalMax = activeChart.finaleTheoreticalMax(isDX = song.isDX),
                                                tintColor = MaimaiConstants.genreTextColor(
                                                    genre = song.basicInfo.genre,
                                                    isDark = LocalIsDarkTheme.current
                                                )
                                            )
                                        } else {
                                            AppSectionCard(title = "容错扣分参考") {
                                                Text(
                                                    text = "当前谱面暂无物量数据",
                                                    style = AppTheme.AppTypography.body,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
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

            StatusToastHost(
                toastState = toastState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
            )
        }
    }

    if (showCoverZoom) {
        CoverZoomDialog(
            song = song,
            onDismiss = { showCoverZoom = false },
            onSavedToast = { msg -> toastState.show(msg) },
            onError = { err -> errorMessage = err }
        )
    }

    AppErrorDialog(
        message = errorMessage,
        onDismiss = { errorMessage = null }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SongHeroHeaderCard(
    song: SongData,
    counterpart: SongData?,
    onZoomCover: () -> Unit,
    onSwitchCounterpart: (SongData) -> Unit,
    onCopyText: (String, String) -> Unit
) {
    val genreColors = MaimaiConstants.genreColors(song.basicInfo.genre)
    val cardFill = MaimaiConstants.genreCardFill(song.basicInfo.genre)
    val cardShape = RoundedCornerShape(AppTheme.Radius.card)
    val coverShape = RoundedCornerShape(AppTheme.Radius.button)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(cardFill, cardFill.copy(alpha = 0.84f))
                ),
                shape = cardShape
            )
            .border(1.dp, Color.White.copy(alpha = 0.22f), cardShape)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(AppTheme.cardPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                SongCoverBadgeView(
                    song = song,
                    sizeDp = 100.dp,
                    cornerRadius = AppTheme.Radius.button,
                    borderColor = genreColors.stroke,
                    showCornerTypeBadge = true,
                    modifier = Modifier
                        .clip(coverShape)
                        .semantics { contentDescription = "放大查看《${song.title}》封面" }
                        .clickable(role = Role.Button, onClick = onZoomCover)
                )

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = song.title,
                        style = AppTheme.AppTypography.heroTitle,
                        color = Color.White,
                        modifier = Modifier
                            .padding(end = if (counterpart != null) 56.dp else 0.dp)
                            .combinedClickable(
                                onClick = {},
                                onLongClick = { onCopyText(song.title, "歌曲名") }
                            )
                    )

                    Text(
                        text = song.basicInfo.artist,
                        style = AppTheme.AppTypography.body,
                        color = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = { onCopyText(song.basicInfo.artist, "曲师") }
                        )
                    )
                }
            }

            // 3 Fact Pills matching iOS headerCard: BPM | 歌曲分类 | 歌曲 ID
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
            ) {
                FactPill(
                    label = "BPM",
                    value = if (song.basicInfo.bpm > 0) "${song.basicInfo.bpm}" else "—",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                FactPill(
                    label = "歌曲分类",
                    value = MaimaiConstants.shortGenreDisplayName(song.basicInfo.genre),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                )
                FactPill(
                    label = "歌曲 ID",
                    value = if (song.numericId > 0) song.id else "—",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    onLongPress = if (song.numericId > 0) {
                        { onCopyText(song.id, "歌曲 ID") }
                    } else {
                        null
                    }
                )
            }
        }

        if (counterpart != null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 10.dp, end = 10.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.18f), CircleShape)
                    .clickable(
                        role = Role.Button,
                        onClick = { onSwitchCounterpart(counterpart) }
                    )
                    .padding(horizontal = AppTheme.Space.s, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.SwapHoriz,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (counterpart.isDX) "DX" else "标准",
                    style = AppTheme.AppTypography.caption2Semibold,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FactPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(AppTheme.Radius.inset)
    Column(
        modifier = modifier
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.14f), shape)
            .border(0.8.dp, Color.White.copy(alpha = 0.16f), shape)
            .let { base ->
                if (onLongPress != null) {
                    base.combinedClickable(onClick = {}, onLongClick = onLongPress)
                } else {
                    base
                }
            }
            .padding(vertical = AppTheme.Space.s, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)
    ) {
        Text(
            text = label,
            style = AppTheme.AppTypography.caption2,
            color = Color.White.copy(alpha = 0.80f),
            maxLines = 1
        )
        Text(
            text = value,
            style = AppTheme.AppTypography.rowMetric,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun DifficultySelectorRow(
    song: SongData,
    chartsDesc: List<ResolvedChart>,
    selectedChartIndex: Int,
    onSelectChart: (Int) -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val chipShape = RoundedCornerShape(AppTheme.Radius.chip)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
    ) {
        chartsDesc.forEach { chart ->
            val isSelected = chart.index == selectedChartIndex
            val isRemaster = chart.difficulty == DifficultyType.REMASTER
            val badgeTitle = if (song.isUtage) {
                chart.kanji ?: chart.difficulty.shortName
            } else {
                chart.difficulty.shortName
            }

            val bgColor = when {
                isRemaster && isDark && !isSelected -> chart.difficulty.color.copy(alpha = 0.26f)
                isRemaster && isSelected -> Color.White
                isRemaster -> Color.White.copy(alpha = 0.65f)
                isSelected -> chart.difficulty.solidFill(isDark)
                else -> chart.difficulty.color.copy(alpha = if (isDark) 0.26f else 0.14f)
            }

            val fgColor = when {
                isRemaster && isDark && !isSelected -> chart.difficulty.boardMainColor
                isRemaster -> AppTheme.Palette.chartRemaster
                isSelected -> chart.difficulty.readableForeground
                isDark -> chart.difficulty.boardMainColor
                else -> MaterialTheme.colorScheme.onSurface
            }

            val borderAlpha = when {
                isSelected || isRemaster -> 1f
                isDark -> 0.75f
                else -> 0.45f
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(chipShape)
                    .background(bgColor, chipShape)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = chart.difficulty.color.copy(alpha = borderAlpha),
                        shape = chipShape
                    )
                    .semantics {
                        selected = isSelected
                        contentDescription = "$badgeTitle，等级 ${chart.level}"
                    }
                    .clickable(
                        role = Role.Tab,
                        onClick = { onSelectChart(chart.index) }
                    )
                    .padding(vertical = 9.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = badgeTitle,
                    style = AppTheme.AppTypography.captionBold,
                    color = fgColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = chart.level,
                    style = AppTheme.AppTypography.badge,
                    color = fgColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// MARK: - Tab 1: 谱面概览 (Exact iOS overviewSection parity)

@OptIn(ExperimentalLayoutApi::class, ExperimentalFoundationApi::class)
@Composable
private fun OverviewSection(
    song: SongData,
    chart: ResolvedChart,
    onCopyText: (String, String) -> Unit
) {
    val store = LocalDataStore.current
    val record = store.record(song.numericId, chart.index)
    val fitDiff = store.fitDiff(song.numericId, chart.index)
    val (versionAsset, versionFallback) = MaimaiConstants.activeServerVersionDisplay(
        info = song.basicInfo,
        region = store.serverRegion
    )

    val dsTileTitle = chart.oldDs?.let { old ->
        val prefix = if (store.serverRegion == MaimaiConstants.ServerRegion.CN) "旧" else "国服"
        String.format(Locale.US, "定数 (%s%.1f)", prefix, old)
    } ?: "谱面定数"

    val aliases = remember(song.id, song.alias, store.enableShowAlias) {
        if (!store.enableShowAlias || song.alias.isNullOrEmpty()) {
            emptyList()
        } else {
            val titleKey = song.title.trim()
            song.alias
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.equals(titleKey, ignoreCase = true) }
        }
    }

    val hasCharter = chart.charter.isNotBlank() && chart.charter != "-"

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
    ) {
        // 1. 谱面信息 Card (3 Metric Tiles + 2-Row Inset Group)
        AppSectionCard(title = "谱面信息") {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                ) {
                    AppMetricTile(
                        title = dsTileTitle,
                        valueSlotHeight = 28.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        val old = chart.oldDs
                        if (old != null) {
                            val isUp = old < chart.ds
                            val deltaColor = if (isUp) AppTheme.Palette.deltaUp else AppTheme.Palette.deltaDown
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = String.format(Locale.US, "%.1f", chart.ds),
                                    style = AppTheme.AppTypography.metricDisplay,
                                    color = deltaColor,
                                    maxLines = 1
                                )
                                Text(
                                    text = if (isUp) "↑" else "↓",
                                    style = AppTheme.AppTypography.captionBold,
                                    color = deltaColor,
                                    modifier = Modifier
                                        .align(Alignment.CenterEnd)
                                        .offset(x = 11.dp)
                                )
                            }
                        } else {
                            Text(
                                text = String.format(Locale.US, "%.1f", chart.ds),
                                style = AppTheme.AppTypography.metricDisplay,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1
                            )
                        }
                    }

                    AppMetricTile(
                        title = "水鱼拟合定数",
                        valueSlotHeight = 28.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = fitDiff?.let { String.format(Locale.US, "%.2f", it) } ?: "—",
                            style = AppTheme.AppTypography.metricDisplay,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1
                        )
                    }

                    AppMetricTile(
                        title = "收录版本",
                        valueSlotHeight = 28.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        val resId = versionAsset?.let { MaimaiConstants.drawableResId(it) }
                        if (resId != null) {
                            Image(
                                painter = painterResource(resId),
                                contentDescription = versionFallback,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier
                                    .widthIn(max = 88.dp)
                                    .height(28.dp)
                            )
                        } else {
                            Text(
                                text = versionFallback,
                                style = AppTheme.AppTypography.inlineAction,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                if (song.isUtage && !hasCharter) {
                    AppInsetGroup {
                        DetailRow(title = "我的成绩") {
                            RecordSummarySlot(record = record, region = store.serverRegion)
                        }
                    }
                } else {
                    val displayCharter = if (hasCharter) chart.charter else "—"
                    AppInsetGroup {
                        DetailRow(
                            title = "谱面作者",
                            onLongPress = if (hasCharter) {
                                { onCopyText(chart.charter, "谱面作者") }
                            } else {
                                null
                            }
                        ) {
                            Text(
                                text = displayCharter,
                                style = AppTheme.AppTypography.rowTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.End
                            )
                        }

                        AppInsetDivider()

                        DetailRow(title = "我的成绩") {
                            RecordSummarySlot(record = record, region = store.serverRegion)
                        }
                    }
                }
            }
        }

        // 2. Utage 谱面说明 Card
        if (song.isUtage && !hasCharter) {
            val desc = song.comment?.takeIf { it.isNotBlank() }
            AppSectionCard(title = "谱面说明") {
                Text(
                    text = desc ?: "暂无说明",
                    style = AppTheme.AppTypography.rowTitle,
                    color = if (desc == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .let { base ->
                            if (desc != null) {
                                base.combinedClickable(
                                    onClick = {},
                                    onLongClick = { onCopyText(desc, "谱面说明") }
                                )
                            } else {
                                base
                            }
                        }
                )
            }
        }

        // 3. 物量分布 Card (6 bars: Total, Tap, Hold, Slide, Touch, Break)
        if (!song.isUtage) {
            ChartNotesBarCard(
                chart = chart,
                maxStats = store.maxNotesStats,
                barColor = chart.difficulty.color
            )
        }

        // 4. 歌曲别名 Card
        if (aliases.isNotEmpty()) {
            AppSectionCard(title = "歌曲别名") {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    aliases.forEach { alias ->
                        GenreCapsuleTag(
                            genre = song.basicInfo.genre,
                            regularSize = true,
                            text = alias,
                            modifier = Modifier
                                .clip(CircleShape)
                                .combinedClickable(
                                    onClick = {},
                                    onLongClick = { onCopyText(alias, "别名") }
                                )
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailRow(
    title: String,
    onLongPress: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 42.dp)
            .let { base ->
                if (onLongPress != null) {
                    base.combinedClickable(onClick = {}, onLongClick = onLongPress)
                } else {
                    base
                }
            }
            .padding(horizontal = AppTheme.Space.m, vertical = AppTheme.Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
    ) {
        Text(
            text = title,
            style = AppTheme.AppTypography.inlineAction,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun RecordSummarySlot(
    record: ProberRecord?,
    region: MaimaiConstants.ServerRegion
) {
    if (record != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Text(
                text = String.format(Locale.US, "%.4f%%", record.achievements),
                style = AppTheme.AppTypography.rowMetric,
                color = MaterialTheme.colorScheme.onSurface
            )
            RecordStatusBadges(
                record = record,
                regularSize = true,
                region = region
            )
        }
    } else {
        Text(
            text = "暂无成绩",
            style = AppTheme.AppTypography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ChartNotesBarCard(
    chart: ResolvedChart,
    maxStats: MaxNotesStats,
    barColor: Color
) {
    val rows = remember(chart, maxStats) {
        listOf(
            Triple("Total", chart.totalNotes, maxStats.total),
            Triple("Tap", chart.tap, maxStats.tap),
            Triple("Hold", chart.hold, maxStats.hold),
            Triple("Slide", chart.slide, maxStats.slide),
            Triple("Touch", chart.touch, maxStats.touch),
            Triple("Break", chart.breakCount, maxStats.breakCount)
        )
    }

    AppSectionCard(
        title = "物量分布",
        trailing = {
            Text(
                text = "共 ${chart.totalNotes} Notes",
                style = AppTheme.AppTypography.captionMeta,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            rows.forEach { (label, value, maxValue) ->
                val targetRatio = if (maxValue > 0 && value > 0) {
                    (value.toFloat() / maxValue.toFloat()).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val animatedRatio by animateFloatAsState(
                    targetValue = targetRatio,
                    animationSpec = tween(durationMillis = 260),
                    label = "note_bar_$label"
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                ) {
                    Text(
                        text = label,
                        style = AppTheme.AppTypography.captionMeta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(44.dp)
                    )

                    BoxWithConstraints(
                        modifier = Modifier
                            .weight(1f)
                            .height(12.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    ) {
                        if (value > 0) {
                            val barW = (maxWidth * animatedRatio).coerceAtLeast(10.dp)
                            Box(
                                modifier = Modifier
                                    .width(barW)
                                    .fillMaxHeight()
                                    .clip(CircleShape)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(barColor, barColor.copy(alpha = 0.80f))
                                        )
                                    )
                            )
                        }
                    }

                    Text(
                        text = "$value",
                        style = AppTheme.AppTypography.captionMeta,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.width(40.dp)
                    )
                }
            }
        }
    }
}

// MARK: - Tab 2: Rating与推分 (3-column RatingTierTable across all 3 server regions)

@Composable
private fun RatingSection(
    song: SongData,
    chart: ResolvedChart
) {
    val store = LocalDataStore.current

    if (song.isUtage || chart.ds <= 0.0) {
        AppSectionCard(title = "分段 Rating 对照") {
            Text(
                text = "宴会场谱面不计入 Rating",
                style = AppTheme.AppTypography.body,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val record = store.record(song.numericId, chart.index)
    val showB50 = store.isLoggedIn
    val baseline = if (showB50) store.b50BaselineRating(song, chart.index) else null
    val rows = remember(chart.ds, record, baseline, store.serverRegion) {
        RatingCalculator.buildTierRows(
            ds = chart.ds,
            currentAchievement = record?.achievements,
            currentIsAP = record?.isAllPerfect == true,
            b50BaselineRating = baseline,
            includeAPBonusRow = store.serverRegion.usesAPBonus
        )
    }

    AppSectionCard(
        title = "分段 Rating 对照",
        trailing = {
            AppCapsuleLabel(text = String.format(Locale.US, "定数 %.1f", chart.ds))
        }
    ) {
        RatingTierTable(
            rows = rows,
            showB50Column = showB50,
            region = store.serverRegion
        )
    }
}

// MARK: - Tab 3: 容错参考 (Exact iOS ChartToleranceCard parity)

@Composable
private fun ChartToleranceCard(
    metrics: ResolvedChart.ToleranceMetrics,
    finaleTheoreticalMax: Double?,
    tintColor: Color
) {
    fun pct(ratio: Double): String = String.format(Locale.US, "%.4f%%", ratio * 100.0)

    AppSectionCard(
        title = "容错扣分参考",
        subtitle = "各判定单项损失达成率"
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
        ) {
            AppInsetGroup {
                ToleranceRow(label = "音符", values = listOf("Great", "Good", "Miss"), isHeader = true)
                AppInsetDivider()
                ToleranceRow(
                    label = "Tap",
                    values = listOf(pct(metrics.tapGreat), pct(metrics.tapGood), pct(metrics.tapMiss))
                )
                AppInsetDivider()
                ToleranceRow(
                    label = "Hold",
                    values = listOf(pct(metrics.holdGreat), pct(metrics.holdGood), pct(metrics.holdMiss))
                )
                AppInsetDivider()
                ToleranceRow(
                    label = "Slide",
                    values = listOf(pct(metrics.slideGreat), pct(metrics.slideGood), pct(metrics.slideMiss))
                )
            }

            val p50 = metrics.breakPerfect50
            val p100 = metrics.breakPerfect100
            val g4x = metrics.breakGreat4x
            val g3x = metrics.breakGreat3x
            val g25x = metrics.breakGreat25x
            val bGood = metrics.breakGood
            val bMiss = metrics.breakMiss

            if (p50 != null && p100 != null && g4x != null && g3x != null && g25x != null && bGood != null && bMiss != null) {
                Text(
                    text = "Break（绝赞）单项扣分",
                    style = AppTheme.AppTypography.inlineAction,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                ) {
                    // Row 1 (2 cells): Perfect -50 | Perfect -100
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        BreakLossCell(
                            label = "Perfect",
                            badge = "-50",
                            value = pct(p50),
                            tint = AppTheme.Palette.judgePerfect,
                            modifier = Modifier.weight(1f)
                        )
                        BreakLossCell(
                            label = "Perfect",
                            badge = "-100",
                            value = pct(p100),
                            tint = AppTheme.Palette.judgePerfect,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Row 2 (3 cells): Great 4x | 3x | 2.5x
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        BreakLossCell(
                            label = "Great",
                            badge = "4x",
                            value = pct(g4x),
                            tint = AppTheme.Palette.judgeGreat,
                            modifier = Modifier.weight(1f)
                        )
                        BreakLossCell(
                            label = "Great",
                            badge = "3x",
                            value = pct(g3x),
                            tint = AppTheme.Palette.judgeGreat,
                            modifier = Modifier.weight(1f)
                        )
                        BreakLossCell(
                            label = "Great",
                            badge = "2.5x",
                            value = pct(g25x),
                            tint = AppTheme.Palette.judgeGreat,
                            modifier = Modifier.weight(1f)
                        )
                    }

                    // Row 3 (2 cells): Good | Miss
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        BreakLossCell(
                            label = "Good",
                            value = pct(bGood),
                            modifier = Modifier.weight(1f)
                        )
                        BreakLossCell(
                            label = "Miss",
                            value = pct(bMiss),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (finaleTheoreticalMax != null) {
                Row(
                    modifier = Modifier.padding(top = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.AutoAwesome,
                        contentDescription = null,
                        tint = tintColor,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = String.format(
                            Locale.US,
                            "FiNALE 旧框理论值（全 2600）：%.2f%%",
                            finaleTheoreticalMax
                        ),
                        style = AppTheme.AppTypography.captionMeta,
                        color = tintColor
                    )
                }
            }
        }
    }
}

@Composable
private fun BreakLossCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
    badge: String? = null
) {
    AppInsetGroup(
        modifier = modifier,
        padding = AppTheme.itemSpacing
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
            ) {
                Text(
                    text = label,
                    style = AppTheme.AppTypography.caption2Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (badge != null) {
                    AppCapsuleLabel(
                        text = badge,
                        tint = tint,
                        style = CapsuleStyle.Compact
                    )
                }
            }
            Text(
                text = "-$value",
                style = AppTheme.AppTypography.captionMeta,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun ToleranceRow(
    label: String,
    values: List<String>,
    isHeader: Boolean = false
) {
    val textStyle = if (isHeader) AppTheme.AppTypography.captionBold else AppTheme.AppTypography.captionMono
    val textColor = if (isHeader) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = AppTheme.Space.m, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
    ) {
        Text(
            text = label,
            style = textStyle,
            color = textColor,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp)
        )
        values.forEach { v ->
            Text(
                text = if (isHeader) v else "-$v",
                style = textStyle,
                color = textColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
