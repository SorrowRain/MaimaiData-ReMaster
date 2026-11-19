package dev.sorrowrain.maimaidata.views

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.bridges.PhotoPreviewDialog
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppEmptyState
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppSegmentedControl
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.ChartTypeBadge
import dev.sorrowrain.maimaidata.components.DifficultyLevelBadge
import dev.sorrowrain.maimaidata.components.RecordStatusBadges
import dev.sorrowrain.maimaidata.components.SongCoverBadgeView
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.models.ProberRecord
import dev.sorrowrain.maimaidata.models.SongData
import dev.sorrowrain.maimaidata.services.Best50Renderer
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProberScreen(
    onBack: () -> Unit,
    onOpenSong: (songId: String, initialDifficultyIndex: Int?) -> Unit
) {
    val store = LocalDataStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastState = rememberToastState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) } // 0 = B35, 1 = B15
    var showLoginSheet by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isPullRefreshing by remember { mutableStateOf(false) }
    var isRenderingB50 by remember { mutableStateOf(false) }
    var b50PreviewBitmap by remember { mutableStateOf<Bitmap?>(null) }

    val summary = remember(store.records, store.songsById, store.serverRegion) {
        store.best35AndBest15()
    }
    val targetList = remember(store.records, store.songsById, store.serverRegion, selectedTab) {
        store.sortedRecords(isNewVersion = selectedTab == 1)
    }
    val cutoff = if (selectedTab == 0) 35 else 15

    suspend fun refreshRecords(showToastOnSuccess: Boolean = false) {
        try {
            store.syncActiveProfileRecords()
            if (showToastOnSuccess) {
                toastState.show("成绩已同步")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: MaimaiAPIException.Unauthorized) {
            showLoginSheet = true
        } catch (err: MaimaiAPIException) {
            errorMessage = "成绩刷新失败：${err.recoveryMessage}\n已有成绩已保留，请稍后重试。"
        } catch (err: Throwable) {
            errorMessage = "成绩刷新失败：${err.message ?: "网络请求异常"}\n已有成绩已保留，请稍后重试。"
        }
    }

    LaunchedEffect(store.isLoggedIn, store.isLoadingLocalData) {
        if (!store.isLoadingLocalData && store.isLoggedIn && store.records.isEmpty()) {
            refreshRecords(showToastOnSuccess = false)
        }
    }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "总 Rating ${summary.totalRating}",
                    onBack = onBack,
                    actions = {
                        AppInlineButton(
                            title = if (isRenderingB50) "生成中…" else "生成 B50",
                            icon = Icons.Rounded.Photo,
                            isLoading = isRenderingB50,
                            enabled = summary.b35.isNotEmpty() || summary.b15.isNotEmpty(),
                            modifier = Modifier.padding(end = AppTheme.Space.s),
                            onClick = {
                                if (isRenderingB50) return@AppInlineButton
                                isRenderingB50 = true
                                scope.launch {
                                    try {
                                        val rendered = Best50Renderer.render(
                                            context = context,
                                            playerName = store.displayPlayerName,
                                            songsById = store.songsById,
                                            b35 = summary.b35,
                                            b15 = summary.b15,
                                            region = store.serverRegion
                                        )
                                        if (rendered != null) {
                                            b50PreviewBitmap = rendered
                                        } else {
                                            errorMessage = "无法生成 Best50 图片，请先拉取成绩"
                                        }
                                    } catch (ce: CancellationException) {
                                        throw ce
                                    } catch (err: Throwable) {
                                        errorMessage = "生成 Best50 图片失败：${err.message ?: "未知错误"}"
                                    } finally {
                                        isRenderingB50 = false
                                    }
                                }
                            }
                        )
                    }
                )
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                PullToRefreshBox(
                    isRefreshing = isPullRefreshing || store.isSyncingRecords,
                    onRefresh = {
                        if (!store.isLoggedIn) {
                            showLoginSheet = true
                        } else if (!isPullRefreshing && !store.isSyncingRecords) {
                            isPullRefreshing = true
                            scope.launch {
                                try {
                                    refreshRecords(showToastOnSuccess = true)
                                } finally {
                                    isPullRefreshing = false
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = AppTheme.pagePadding,
                            vertical = AppTheme.Space.m
                        ),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                    ) {
                        // 1. Header Picker Card (exact iOS ProberView parity)
                        item(key = "b50_header_picker_card") {
                            AppCard(
                                padding = AppTheme.compactCardPadding,
                                tint = AppTheme.accent
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
                                ) {
                                    AppSegmentedControl(
                                        options = listOf(
                                            "旧版本 B35 (${summary.b35Rating})",
                                            "现行版本 B15 (${summary.b15Rating})"
                                        ),
                                        selectedIndex = selectedTab,
                                        onSelect = { selectedTab = it }
                                    )

                                    if (!summary.isMatching && store.records.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Rounded.Warning,
                                                contentDescription = null,
                                                tint = AppTheme.warningText,
                                                modifier = Modifier.size(15.dp)
                                            )
                                            Text(
                                                text = "部分成绩未匹配到当前曲库，建议在「设置与数据」中更新曲库",
                                                style = AppTheme.AppTypography.caption,
                                                color = AppTheme.warningText
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. Empty States or Record Rows
                        if (targetList.isEmpty()) {
                            item(key = "b50_empty_state") {
                                AppEmptyState(
                                    icon = if (store.isLoggedIn) Icons.Rounded.Inbox else Icons.Rounded.PersonAdd,
                                    title = if (store.isLoggedIn) "暂无成绩数据" else "尚未添加成绩档案",
                                    description = if (store.isLoggedIn) {
                                        "下拉刷新从当前查分器拉取最新成绩"
                                    } else {
                                        "连接落雪咖啡屋或水鱼查分器后即可查看 B35 / B15 并生成 Best50 图"
                                    },
                                    action = if (!store.isLoggedIn) {
                                        {
                                            AppInlineButton(
                                                title = "添加成绩档案",
                                                icon = Icons.Rounded.PersonAdd,
                                                onClick = { showLoginSheet = true }
                                            )
                                        }
                                    } else {
                                        null
                                    }
                                )
                            }
                        } else {
                            itemsIndexed(
                                items = targetList,
                                key = { _, record -> "${selectedTab}_${record.id}" }
                            ) { idx, record ->
                                if (idx == cutoff) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 4.dp, bottom = 14.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                                    ) {
                                        AppInsetDivider(modifier = Modifier.weight(1f))
                                        Text(
                                            text = "以下不计入 Best$cutoff",
                                            style = AppTheme.AppTypography.inlineAction,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        AppInsetDivider(modifier = Modifier.weight(1f))
                                    }
                                }

                                val song = store.songsById[record.songId]
                                ProberRecordCard(
                                    rank = idx + 1,
                                    record = record,
                                    song = song,
                                    region = store.serverRegion,
                                    onClick = if (song != null) {
                                        { onOpenSong(song.id, record.levelIndex) }
                                    } else {
                                        null
                                    }
                                )
                            }
                        }
                    }
                }

                StatusToastHost(
                    toastState = toastState,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }
    }

    if (showLoginSheet) {
        LoginSheet(
            onDismiss = { showLoginSheet = false },
            onProfileAdded = { profile ->
                toastState.show("已连接 ${profile.displayTitle}")
            },
            onWarningMessage = { msg ->
                errorMessage = msg
            }
        )
    }

    if (b50PreviewBitmap != null) {
        PhotoPreviewDialog(
            title = "Best 50 预览",
            bitmap = b50PreviewBitmap,
            onDismiss = { b50PreviewBitmap = null },
            onSavedToast = { msg -> toastState.show(msg) },
            onError = { err -> errorMessage = err }
        )
    }

    AppErrorDialog(
        title = "提示",
        message = errorMessage,
        onDismiss = { errorMessage = null }
    )
}

@Composable
private fun ProberRecordCard(
    rank: Int,
    record: ProberRecord,
    song: SongData?,
    region: MaimaiConstants.ServerRegion,
    onClick: (() -> Unit)?
) {
    val diff = record.difficulty

    AppCard(
        padding = AppTheme.compactCardPadding,
        tint = diff.color,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
        ) {
            SongCoverBadgeView(
                song = song,
                sizeDp = 56.dp,
                cornerRadius = AppTheme.Radius.tile,
                borderColor = diff.boardDarkColor
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    AppCapsuleLabel(
                        text = "#$rank",
                        tint = Color.Unspecified,
                        style = CapsuleStyle.Compact
                    )

                    Text(
                        text = record.title,
                        style = AppTheme.AppTypography.songTitle,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )

                    ChartTypeBadge(
                        isDX = record.type == MaimaiConstants.CHART_TYPE_DX,
                        compact = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    DifficultyLevelBadge(
                        difficulty = diff,
                        text = String.format(Locale.US, "%.1f", record.ds)
                    )

                    Text(
                        text = String.format(Locale.US, "%.4f%%", record.achievements),
                        style = AppTheme.AppTypography.captionMetaBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        softWrap = false
                    )

                    RecordStatusBadges(
                        record = record,
                        regularSize = false,
                        region = region
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    AppCapsuleLabel(
                        text = "Ra ${record.computedRating}",
                        tint = AppTheme.accent,
                        style = CapsuleStyle.Count
                    )
                }
            }
        }
    }
}
