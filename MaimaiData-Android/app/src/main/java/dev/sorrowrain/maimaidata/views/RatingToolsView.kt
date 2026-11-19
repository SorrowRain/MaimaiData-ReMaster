package dev.sorrowrain.maimaidata.views

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppInsetRow
import dev.sorrowrain.maimaidata.components.AppMetricTile
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.services.MaimaiAPIException
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun RatingToolsScreen(
    onNavigateProber: () -> Unit,
    onNavigateVersionCheck: () -> Unit,
    onNavigateLevelCheck: () -> Unit,
    onNavigateCalculator: () -> Unit,
    onNavigateFinaleToDx: () -> Unit,
    onNavigateAccountManagement: () -> Unit
) {
    val store = LocalDataStore.current
    val scope = rememberCoroutineScope()
    val toastState = rememberToastState()
    var syncErrorMessage by remember { mutableStateOf<String?>(null) }

    val summary = remember(store.records, store.songsById, store.serverRegion) {
        store.best35AndBest15()
    }
    val isLoggedIn = store.isLoggedIn

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(title = "Rating 与查分")
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                    verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
                ) {
                    // 1. Player & Rating Summary Card
                    AppCard(
                        padding = AppTheme.cardPadding,
                        tint = AppTheme.accent
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(AppTheme.Space.m)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                            ) {
                                Text(
                                    text = if (store.activeProfile != null) {
                                        store.displayPlayerName
                                    } else {
                                        "未添加档案"
                                    },
                                    style = AppTheme.AppTypography.cardTitle,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )

                                if (isLoggedIn) {
                                    AppInlineButton(
                                        title = "同步成绩",
                                        icon = Icons.Rounded.Refresh,
                                        isLoading = store.isSyncingRecords,
                                        onClick = {
                                            scope.launch {
                                                try {
                                                    store.syncActiveProfileRecords()
                                                    toastState.show("成绩已同步")
                                                } catch (ce: CancellationException) {
                                                    throw ce
                                                } catch (e: MaimaiAPIException) {
                                                    syncErrorMessage = e.recoveryMessage
                                                } catch (e: Throwable) {
                                                    syncErrorMessage = e.message ?: "同步失败，请稍后重试。"
                                                }
                                            }
                                        }
                                    )
                                } else {
                                    AppInlineButton(
                                        title = "添加档案",
                                        icon = Icons.Rounded.PersonAdd,
                                        onClick = onNavigateAccountManagement
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(AppTheme.Radius.inset))
                                    .semantics {
                                        contentDescription = if (isLoggedIn) {
                                            "查看 Best 50 成绩详情"
                                        } else {
                                            "前往添加成绩档案"
                                        }
                                    }
                                    .clickable(role = Role.Button) {
                                        if (isLoggedIn) {
                                            onNavigateProber()
                                        } else {
                                            onNavigateAccountManagement()
                                        }
                                    },
                                horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                            ) {
                                AppMetricTile(
                                    title = "总 Rating",
                                    value = if (isLoggedIn) "${summary.totalRating}" else "—",
                                    highlight = isLoggedIn,
                                    modifier = Modifier.weight(1f)
                                )
                                AppMetricTile(
                                    title = "旧版本 B35",
                                    value = if (isLoggedIn) "${summary.b35Rating}" else "—",
                                    modifier = Modifier.weight(1f)
                                )
                                AppMetricTile(
                                    title = "现行版本 B15",
                                    value = if (isLoggedIn) "${summary.b15Rating}" else "—",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 2. Records & Plate Progress Section
                    AppSectionCard(title = "成绩与牌子进度") {
                        AppInsetGroup {
                            AppInsetRow(
                                title = "Best 50",
                                icon = Icons.Rounded.EmojiEvents,
                                iconTint = AppTheme.accent,
                                showsChevron = true,
                                onClick = onNavigateProber
                            )
                            AppInsetDivider()
                            AppInsetRow(
                                title = "牌子进度表",
                                icon = Icons.Rounded.GridView,
                                iconTint = AppTheme.Palette.ratingPurple,
                                showsChevron = true,
                                onClick = onNavigateVersionCheck
                            )
                            AppInsetDivider()
                            AppInsetRow(
                                title = "等级进度表",
                                icon = Icons.Rounded.Tune,
                                iconTint = AppTheme.Palette.ratingOrange,
                                showsChevron = true,
                                onClick = onNavigateLevelCheck
                            )
                        }
                    }

                    // 3. Calculators Section
                    AppSectionCard(title = "算分与推分工具") {
                        AppInsetGroup {
                            AppInsetRow(
                                title = "Rating 计算器",
                                icon = Icons.Rounded.Calculate,
                                iconTint = AppTheme.Palette.ratingBlue,
                                showsChevron = true,
                                onClick = onNavigateCalculator
                            )
                            AppInsetDivider()
                            AppInsetRow(
                                title = "旧框达成率换算",
                                icon = Icons.Rounded.SwapHoriz,
                                iconTint = AppTheme.Palette.noteSlide,
                                showsChevron = true,
                                onClick = onNavigateFinaleToDx
                            )
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

    AppErrorDialog(
        title = "同步失败",
        message = syncErrorMessage,
        onDismiss = { syncErrorMessage = null }
    )
}
