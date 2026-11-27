package dev.sorrowrain.maimaidata.views

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppEmptyState
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppLoadingState
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.ChartTypeBadge
import dev.sorrowrain.maimaidata.components.DifficultyLevelBadge
import dev.sorrowrain.maimaidata.components.FilterCapsuleChip
import dev.sorrowrain.maimaidata.components.GenreCapsuleTag
import dev.sorrowrain.maimaidata.components.SongCoverBadgeView
import dev.sorrowrain.maimaidata.components.SyncStatusBanner
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.models.SongFilterCriteria
import dev.sorrowrain.maimaidata.models.SongSortOption
import dev.sorrowrain.maimaidata.services.DataSyncNotice
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.SongFilter
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import dev.sorrowrain.maimaidata.theme.LocalIsDarkTheme
import kotlinx.coroutines.launch
import java.util.Locale

private val SongFilterCriteriaSaver = listSaver<SongFilterCriteria, Any>(
    save = { c ->
        listOf(
            c.keyword,
            ArrayList(c.selectedGenres),
            ArrayList(c.selectedVersions),
            c.selectedLevel,
            c.useExactDS,
            c.exactDS,
            c.onlyFavorites,
            c.sortOption.name
        )
    },
    restore = { list ->
        @Suppress("UNCHECKED_CAST")
        SongFilterCriteria(
            keyword = list[0] as String,
            selectedGenres = (list[1] as List<String>).toSet(),
            selectedVersions = (list[2] as List<String>).toSet(),
            selectedLevel = list[3] as String,
            useExactDS = list[4] as Boolean,
            exactDS = list[5] as Double,
            onlyFavorites = list[6] as Boolean,
            sortOption = runCatching { SongSortOption.valueOf(list[7] as String) }
                .getOrDefault(SongSortOption.DEFAULT_DESC)
        )
    }
)

@Composable
fun SongListScreen(
    onOpenSong: (songId: String, initialDifficultyIndex: Int?) -> Unit
) {
    val store = LocalDataStore.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    var criteria by rememberSaveable(stateSaver = SongFilterCriteriaSaver) {
        mutableStateOf(SongFilterCriteria())
    }
    var lastScrollCriteria by rememberSaveable(stateSaver = SongFilterCriteriaSaver) {
        mutableStateOf(criteria)
    }
    var lastScrollRegionName by rememberSaveable {
        mutableStateOf(store.serverRegion.name)
    }
    var searchFieldValue by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(criteria.keyword))
    }
    var isSearchFocused by remember { mutableStateOf(false) }
    var showFilterSheet by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(criteria.keyword) {
        if (searchFieldValue.text != criteria.keyword) {
            searchFieldValue = TextFieldValue(
                text = criteria.keyword,
                selection = TextRange(criteria.keyword.length)
            )
        }
    }

    LaunchedEffect(store.serverRegion) {
        val pruned = SongFilter.prunedCriteria(criteria, store.serverRegion)
        if (pruned != criteria) {
            criteria = pruned
        }
    }

    LaunchedEffect(criteria, store.serverRegion) {
        if (criteria != lastScrollCriteria || store.serverRegion.name != lastScrollRegionName) {
            lastScrollCriteria = criteria
            lastScrollRegionName = store.serverRegion.name
            listState.scrollToItem(0)
        }
    }

    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && isSearchFocused) {
            focusManager.clearFocus()
        }
    }

    val displayedSongs = remember(
        criteria,
        store.catalogRevision,
        store.songs,
        store.serverRegion,
        if (criteria.onlyFavorites) store.favoriteIds else emptySet(),
        store.enableAliasSearch,
        store.enableCharterSearch
    ) {
        store.filteredSongs(criteria)
    }

    AppPageBackground {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "曲目列表",
                    actions = {
                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                criteria = criteria.copy(onlyFavorites = !criteria.onlyFavorites)
                            }
                        ) {
                            Icon(
                                imageVector = if (criteria.onlyFavorites) {
                                    Icons.Rounded.Favorite
                                } else {
                                    Icons.Rounded.FavoriteBorder
                                },
                                contentDescription = if (criteria.onlyFavorites) {
                                    "取消收藏筛选"
                                } else {
                                    "仅显示收藏歌曲"
                                },
                                tint = if (criteria.onlyFavorites) {
                                    AppTheme.favoritePink
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }

                        IconButton(
                            onClick = {
                                focusManager.clearFocus()
                                showFilterSheet = true
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FilterList,
                                contentDescription = "筛选",
                                tint = if (criteria.isActive) {
                                    AppTheme.accentText
                                } else {
                                    MaterialTheme.colorScheme.onSurface
                                }
                            )
                        }
                    }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                SearchPillInputBar(
                    fieldValue = searchFieldValue,
                    isFocused = isSearchFocused,
                    onValueChange = { next ->
                        searchFieldValue = next
                        if (criteria.keyword != next.text) {
                            criteria = criteria.copy(keyword = next.text)
                        }
                    },
                    onFocusChange = { focused -> isSearchFocused = focused },
                    onClear = {
                        searchFieldValue = TextFieldValue("")
                        criteria = criteria.copy(keyword = "")
                    },
                    onSubmit = {
                        store.addSearchHistory(criteria.keyword)
                        focusManager.clearFocus()
                    }
                )

                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = AppTheme.pagePadding,
                            end = AppTheme.pagePadding,
                            top = AppTheme.itemSpacing,
                            bottom = AppTheme.Space.m
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                    ) {
                        if (store.isLoadingLocalData || store.isSyncingSongs || store.syncNotice != null) {
                            item(key = "sync_status_banner") {
                                val notice = store.syncNotice
                                if (store.isLoadingLocalData || store.isSyncingSongs) {
                                    SyncStatusBanner(
                                        message = if (store.isLoadingLocalData) {
                                            "正在读取本地曲库…"
                                        } else {
                                            "正在同步曲库与规则…"
                                        },
                                        isLoading = true
                                    )
                                } else if (notice != null) {
                                    SyncStatusBanner(
                                        message = notice.message,
                                        isLoading = false,
                                        onDismiss = { store.syncNotice = null },
                                        onRetry = {
                                            store.syncNotice = null
                                            scope.launch {
                                                when (notice.kind) {
                                                    DataSyncNotice.Kind.SONGS ->
                                                        store.syncAllCatalogsAndRules(force = true)
                                                    DataSyncNotice.Kind.STATS ->
                                                        store.refreshChartStatsIfStale(force = true)
                                                }
                                            }
                                        }
                                    )
                                }
                            }
                        }

                        if (criteria.isActive) {
                            item(key = "active_filter_summary") {
                                ActiveFilterSummaryCard(
                                    criteria = criteria,
                                    count = displayedSongs.size,
                                    onUpdate = { updated -> criteria = updated },
                                    onReset = {
                                        criteria = SongFilterCriteria(keyword = criteria.keyword)
                                    }
                                )
                            }
                        }

                        when {
                            (store.isLoadingLocalData || store.isSyncingSongs) && store.songs.isEmpty() -> {
                                item(key = "loading_placeholder") {
                                    AppLoadingState(
                                        message = if (store.isLoadingLocalData) {
                                            "正在读取曲库…"
                                        } else {
                                            "正在同步${store.serverRegion.shortName}曲库…"
                                        }
                                    )
                                }
                            }

                            displayedSongs.isEmpty() -> {
                                item(key = "empty_placeholder") {
                                    val emptyFavorites = criteria.onlyFavorites && store.favoriteIds.isEmpty()
                                    AppEmptyState(
                                        icon = if (emptyFavorites) {
                                            Icons.Rounded.FavoriteBorder
                                        } else {
                                            Icons.Rounded.SearchOff
                                        },
                                        title = if (emptyFavorites) "还没有收藏歌曲" else "未找到匹配曲目",
                                        description = if (emptyFavorites) {
                                            "在曲目详情页点击右上角心形按钮即可收藏"
                                        } else {
                                            "试试修改关键词或重置筛选条件"
                                        }
                                    )
                                }
                            }

                            else -> {
                                items(
                                    items = displayedSongs,
                                    key = { it.id }
                                ) { song ->
                                    SongRowCard(
                                        song = song,
                                        isFavorite = store.isFavorite(song),
                                        onClick = {
                                            if (criteria.keyword.isNotBlank()) {
                                                store.addSearchHistory(criteria.keyword)
                                            }
                                            focusManager.clearFocus()
                                            onOpenSong(song.id, null)
                                        }
                                    )
                                }
                            }
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = isSearchFocused && criteria.keyword.isBlank() && store.searchHistory.isNotEmpty(),
                        enter = fadeIn() + slideInVertically { -it / 4 },
                        exit = fadeOut() + slideOutVertically { -it / 4 },
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(
                                start = AppTheme.pagePadding,
                                end = AppTheme.pagePadding,
                                top = AppTheme.Space.s
                            )
                    ) {
                        RecentSearchesCard(
                            history = store.searchHistory.take(12),
                            onSelectTerm = { term ->
                                searchFieldValue = TextFieldValue(
                                    text = term,
                                    selection = TextRange(term.length)
                                )
                                criteria = criteria.copy(keyword = term)
                                store.addSearchHistory(term)
                                focusManager.clearFocus()
                            },
                            onClearAll = {
                                store.clearSearchHistory()
                            }
                        )
                    }
                }
            }
        }
    }

    if (showFilterSheet) {
        SearchFilterSheet(
            criteria = criteria,
            region = store.serverRegion,
            onApply = { updated ->
                criteria = updated
                showFilterSheet = false
            },
            onDismiss = {
                showFilterSheet = false
            }
        )
    }
}

@Composable
private fun SearchPillInputBar(
    fieldValue: TextFieldValue,
    isFocused: Boolean,
    onValueChange: (TextFieldValue) -> Unit,
    onFocusChange: (Boolean) -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    val borderColor = if (isFocused) {
        AppTheme.accentText
    } else if (isDark) {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.50f)
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = AppTheme.pagePadding,
                end = AppTheme.pagePadding,
                top = AppTheme.itemSpacing,
                bottom = 0.dp
            ),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 44.dp)
                .padding(start = 14.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (fieldValue.text.isEmpty()) {
                    Text(
                        text = "搜索曲名、别名、谱师或 ID",
                        style = AppTheme.AppTypography.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                BasicTextField(
                    value = fieldValue,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = AppTheme.AppTypography.body.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(AppTheme.accentText),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { state -> onFocusChange(state.isFocused) }
                )
            }

            if (fieldValue.text.isNotEmpty()) {
                IconButton(
                    onClick = onClear,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Cancel,
                        contentDescription = "清除搜索词",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentSearchesCard(
    history: List<String>,
    onSelectTerm: (String) -> Unit,
    onClearAll: () -> Unit
) {
    AppCard(
        padding = AppTheme.compactCardPadding,
        useRaisedSurface = true
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "最近搜索",
                    style = AppTheme.AppTypography.inlineAction,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                AppInlineButton(
                    title = "清空",
                    isNeutral = true,
                    onClick = onClearAll
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                history.forEach { term ->
                    FilterCapsuleChip(
                        title = term,
                        isSelected = false,
                        onClick = { onSelectTerm(term) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ActiveFilterSummaryCard(
    criteria: SongFilterCriteria,
    count: Int,
    onUpdate: (SongFilterCriteria) -> Unit,
    onReset: () -> Unit
) {
    AppCard(
        padding = AppTheme.compactCardPadding
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "已筛选 $count 首曲目",
                    style = AppTheme.AppTypography.inlineAction,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                AppInlineButton(
                    title = "重置",
                    icon = Icons.Rounded.Refresh,
                    onClick = onReset
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (criteria.onlyFavorites) {
                    AppCapsuleLabel(
                        text = "仅看收藏",
                        tint = AppTheme.favoritePink,
                        style = CapsuleStyle.Filter,
                        onRemove = { onUpdate(criteria.copy(onlyFavorites = false)) }
                    )
                }
                if (criteria.sortOption != SongSortOption.DEFAULT_DESC) {
                    AppCapsuleLabel(
                        text = criteria.sortOption.label,
                        style = CapsuleStyle.Filter,
                        onRemove = { onUpdate(criteria.copy(sortOption = SongSortOption.DEFAULT_DESC)) }
                    )
                }
                if (criteria.useExactDS) {
                    AppCapsuleLabel(
                        text = String.format(Locale.US, "定数 %.1f", criteria.exactDS),
                        style = CapsuleStyle.Filter,
                        onRemove = {
                            onUpdate(criteria.copy(useExactDS = false, selectedLevel = "ALL"))
                        }
                    )
                } else if (criteria.selectedLevel != "ALL") {
                    AppCapsuleLabel(
                        text = "等级 ${criteria.selectedLevel}",
                        style = CapsuleStyle.Filter,
                        onRemove = { onUpdate(criteria.copy(selectedLevel = "ALL")) }
                    )
                }
                criteria.selectedGenres.sorted().forEach { genre ->
                    AppCapsuleLabel(
                        text = genre,
                        tint = MaimaiConstants.genreColors(genre).bg,
                        style = CapsuleStyle.Filter,
                        onRemove = {
                            onUpdate(criteria.copy(selectedGenres = criteria.selectedGenres - genre))
                        }
                    )
                }
                criteria.selectedVersions.sorted().forEach { ver ->
                    AppCapsuleLabel(
                        text = ver,
                        style = CapsuleStyle.Filter,
                        onRemove = {
                            onUpdate(criteria.copy(selectedVersions = criteria.selectedVersions - ver))
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SongRowCard(
    song: SongData,
    isFavorite: Boolean,
    onClick: () -> Unit
) {
    val colors = MaimaiConstants.genreColors(song.basicInfo.genre)

    AppCard(
        padding = AppTheme.compactCardPadding,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
        ) {
            SongCoverBadgeView(
                song = song,
                sizeDp = 64.dp,
                borderColor = colors.stroke
            )

            Column(
                modifier = Modifier.weight(1f)
            ) {
                // Line 1: Title + Favorite Heart + ChartTypeBadge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = song.title,
                            style = AppTheme.AppTypography.songTitle,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )

                        if (isFavorite) {
                            Icon(
                                imageVector = Icons.Rounded.Favorite,
                                contentDescription = "已收藏",
                                tint = AppTheme.favoritePink,
                                modifier = Modifier
                                    .padding(top = 3.dp)
                                    .size(13.dp)
                            )
                        }
                    }

                    ChartTypeBadge(
                        isDX = song.isDX,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Spacer(modifier = Modifier.height(5.dp))

                // Line 2: GenreCapsuleTag
                GenreCapsuleTag(genre = song.basicInfo.genre)

                Spacer(modifier = Modifier.height(7.dp))

                // Line 3: DifficultyLevelBadge for each resolved chart
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    song.resolvedCharts.forEach { chart ->
                        DifficultyLevelBadge(
                            difficulty = chart.difficulty,
                            text = if (song.isUtage) {
                                chart.level
                            } else {
                                String.format(Locale.US, "%.1f", chart.ds)
                            }
                        )
                    }
                }
            }
        }
    }
}
