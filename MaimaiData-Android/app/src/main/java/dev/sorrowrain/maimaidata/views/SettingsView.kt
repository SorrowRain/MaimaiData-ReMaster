package dev.sorrowrain.maimaidata.views

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sorrowrain.maimaidata.BuildConfig
import dev.sorrowrain.maimaidata.R
import dev.sorrowrain.maimaidata.bridges.ExternalLinkLauncher
import dev.sorrowrain.maimaidata.components.AppCapsuleLabel
import dev.sorrowrain.maimaidata.components.AppCard
import dev.sorrowrain.maimaidata.components.AppErrorDialog
import dev.sorrowrain.maimaidata.components.AppInlineButton
import dev.sorrowrain.maimaidata.components.AppInsetDivider
import dev.sorrowrain.maimaidata.components.AppInsetGroup
import dev.sorrowrain.maimaidata.components.AppInsetRow
import dev.sorrowrain.maimaidata.components.AppInsetToggleRow
import dev.sorrowrain.maimaidata.components.AppSectionCard
import dev.sorrowrain.maimaidata.components.AppTopBar
import dev.sorrowrain.maimaidata.components.CapsuleStyle
import dev.sorrowrain.maimaidata.components.StatusToastHost
import dev.sorrowrain.maimaidata.components.appInsetRowHighlight
import dev.sorrowrain.maimaidata.components.rememberToastState
import dev.sorrowrain.maimaidata.models.MaimaiConstants
import dev.sorrowrain.maimaidata.services.CoverImageManager
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppPageBackground
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(
    onNavigateAccounts: () -> Unit,
    onNavigateRegion: () -> Unit,
    onNavigatePreferences: () -> Unit,
    onNavigateDataStorage: () -> Unit,
    onNavigateAbout: () -> Unit,
    onNavigateAcknowledgments: () -> Unit
) {
    val store = LocalDataStore.current
    val activeProfileSummary = store.activeProfile?.displayTitle ?: "未添加"
    val shortVersionText = "v${BuildConfig.VERSION_NAME}"

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(title = "设置与数据")
            },
            containerColor = Color.Transparent
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                // 1. 账号与区服
                AppSectionCard(title = "账号与区服") {
                    AppInsetGroup {
                        AppInsetRow(
                            title = "成绩档案",
                            showsChevron = true,
                            onClick = onNavigateAccounts,
                            trailing = {
                                Text(
                                    text = activeProfileSummary,
                                    style = AppTheme.AppTypography.body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        AppInsetDivider()
                        AppInsetRow(
                            title = "游戏区服",
                            showsChevron = true,
                            onClick = onNavigateRegion,
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                                ) {
                                    if (store.isSyncingSongs &&
                                        store.serverRegion != MaimaiConstants.ServerRegion.CN
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = AppTheme.accentText
                                        )
                                    }
                                    Text(
                                        text = store.serverRegion.shortName,
                                        style = AppTheme.AppTypography.body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        )
                    }
                }

                // 2. 偏好与数据
                AppSectionCard(title = "偏好与数据") {
                    AppInsetGroup {
                        AppInsetRow(
                            title = "检索与显示偏好",
                            showsChevron = true,
                            onClick = onNavigatePreferences
                        )
                        AppInsetDivider()
                        AppInsetRow(
                            title = "数据同步与存储",
                            showsChevron = true,
                            onClick = onNavigateDataStorage,
                            trailing = if (store.isSyncingSongs || store.isSyncingStats) {
                                {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = AppTheme.accentText
                                    )
                                }
                            } else {
                                null
                            }
                        )
                    }
                }

                // 3. 关于
                AppSectionCard(title = "关于") {
                    AppInsetGroup {
                        AppInsetRow(
                            title = "关于 Re:MaiData",
                            showsChevron = true,
                            onClick = onNavigateAbout,
                            trailing = {
                                Text(
                                    text = shortVersionText,
                                    style = AppTheme.AppTypography.body,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1
                                )
                            }
                        )
                        AppInsetDivider()
                        AppInsetRow(
                            title = "致谢与参考",
                            showsChevron = true,
                            onClick = onNavigateAcknowledgments
                        )
                    }
                }
            }
        }
    }

    AppErrorDialog(
        title = "同步结果",
        message = store.statusMessage,
        onDismiss = { store.statusMessage = null }
    )
}

@Composable
fun ServerRegionSettingsScreen(onBack: () -> Unit) {
    val store = LocalDataStore.current
    val scope = rememberCoroutineScope()
    val regions = MaimaiConstants.ServerRegion.entries

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "游戏区服",
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
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                AppSectionCard(title = "曲库区服") {
                    AppInsetGroup {
                        regions.forEachIndexed { idx, region ->
                            if (idx > 0) {
                                AppInsetDivider()
                            }
                            val isSelected = store.serverRegion == region
                            AppInsetRow(
                                title = region.displayName,
                                modifier = Modifier.appInsetRowHighlight(isSelected),
                                onClick = {
                                    if (!isSelected) {
                                        scope.launch {
                                            store.switchServerRegion(region)
                                        }
                                    }
                                },
                                trailing = {
                                    if (store.isSyncingSongs &&
                                        isSelected &&
                                        region != MaimaiConstants.ServerRegion.CN
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = AppTheme.accentText
                                        )
                                    } else if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Rounded.Check,
                                            contentDescription = "已选中",
                                            tint = AppTheme.accentText,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PreferencesSettingsScreen(onBack: () -> Unit) {
    val store = LocalDataStore.current

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "检索与显示偏好",
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
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                AppSectionCard(title = "曲库与搜索") {
                    AppInsetGroup {
                        AppInsetToggleRow(
                            title = "曲目详情页显示别名",
                            checked = store.enableShowAlias,
                            onCheckedChange = { store.enableShowAlias = it }
                        )
                        AppInsetDivider()
                        AppInsetToggleRow(
                            title = "搜索时匹配曲目别名",
                            checked = store.enableAliasSearch,
                            onCheckedChange = { store.enableAliasSearch = it }
                        )
                        AppInsetDivider()
                        AppInsetToggleRow(
                            title = "搜索时匹配谱师名称",
                            checked = store.enableCharterSearch,
                            onCheckedChange = { store.enableCharterSearch = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DataAndStorageSettingsScreen(onBack: () -> Unit) {
    val store = LocalDataStore.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toastState = rememberToastState()

    var cacheBytes by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(context) {
        cacheBytes = withContext(Dispatchers.IO) {
            CoverImageManager.cacheSizeBytes(context)
        }
    }

    val formattedCacheSize = remember(context, cacheBytes) {
        cacheBytes?.let { Formatter.formatFileSize(context, it) } ?: "计算中..."
    }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "数据同步与存储",
                    onBack = onBack
                )
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
                    AppSectionCard(title = "数据在线更新") {
                        AppInsetGroup {
                            AppInsetRow(
                                title = "曲库与规则",
                                subtitle = store.lastCatalogSyncText,
                                trailing = {
                                    AppInlineButton(
                                        title = "检查更新",
                                        icon = Icons.Rounded.CloudDownload,
                                        isLoading = store.isSyncingSongs,
                                        onClick = {
                                            scope.launch {
                                                store.syncAllCatalogsAndRules(force = true)
                                            }
                                        }
                                    )
                                }
                            )
                            AppInsetDivider()
                            AppInsetRow(
                                title = "水鱼拟合定数",
                                subtitle = store.lastChartStatsSyncText,
                                trailing = {
                                    AppInlineButton(
                                        title = "立即刷新",
                                        icon = Icons.Rounded.Refresh,
                                        isLoading = store.isSyncingStats,
                                        onClick = {
                                            scope.launch {
                                                store.refreshChartStatsIfStale(force = true)
                                            }
                                        }
                                    )
                                }
                            )
                        }
                    }

                    AppSectionCard(title = "本地存储") {
                        AppInsetGroup {
                            AppInsetRow(
                                title = "封面磁盘缓存",
                                subtitle = "当前占用 $formattedCacheSize",
                                monospacedSubtitle = true,
                                trailing = {
                                    AppInlineButton(
                                        title = "清除缓存",
                                        icon = Icons.Rounded.DeleteOutline,
                                        tint = AppTheme.destructive,
                                        onClick = {
                                            scope.launch {
                                                withContext(Dispatchers.IO) {
                                                    CoverImageManager.clearCache(context)
                                                }
                                                cacheBytes = withContext(Dispatchers.IO) {
                                                    CoverImageManager.cacheSizeBytes(context)
                                                }
                                                toastState.show("封面缓存已清除")
                                            }
                                        }
                                    )
                                }
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
        title = "同步结果",
        message = store.statusMessage,
        onDismiss = { store.statusMessage = null }
    )
}

private data class ReferenceEntry(
    val name: String,
    val tag: String,
    val summary: String,
    val urlString: String,
    val displayHost: String
)

private val proberReferences = listOf(
    ReferenceEntry(
        name = "LXNS（落雪咖啡屋）",
        tag = "查分与国服曲库",
        summary = "提供落雪账号成绩同步接口、国服曲库与别名公共数据、官方版本牌子曲目规则以及国内高速高清曲绘 CDN 镜像服务。",
        urlString = "https://maimai.lxns.net",
        displayHost = "maimai.lxns.net"
    ),
    ReferenceEntry(
        name = "Diving-Fish（水鱼查分器）",
        tag = "查分与拟合统计",
        summary = "提供水鱼账号成绩同步接口、全服大数据拟合定数统计以及曲绘封面兜底服务。",
        urlString = "https://github.com/Diving-Fish/maimaidx-prober",
        displayHost = "diving-fish.com"
    )
)

private val multiRegionReferences = listOf(
    ReferenceEntry(
        name = "zetaraku / arcade-songs-fetch",
        tag = "多区服曲库",
        summary = "提供日服与国际服完整曲库、版本归属、区服定数覆写、外服曲绘镜像及宴会场谱面说明数据。",
        urlString = "https://github.com/zetaraku/arcade-songs-fetch",
        displayHost = "github.com/zetaraku/arcade-songs-fetch"
    ),
    ReferenceEntry(
        name = "DXRating (gekichumai)",
        tag = "ID 与新规参考",
        summary = "提供日服与国际服曲目底层数字 ID 实时映射数据，以及 CiRCLE 起实行的两代 B15 与 AP+1 新规参考。",
        urlString = "https://github.com/gekichumai/dxrating",
        displayHost = "dxrating.net"
    ),
    ReferenceEntry(
        name = "Yuri-YuzuChaN & 社区资源库",
        tag = "别名与贴图",
        summary = "提供社区共同维护的舞萌 DX 曲目中文别名库，以及各世代版本与评级贴图静态素材整理。",
        urlString = "https://github.com/Yuri-YuzuChaN/maimaiDX",
        displayHost = "github.com/Yuri-YuzuChaN/maimaiDX"
    )
)

private val designReferences = listOf(
    ReferenceEntry(
        name = "PaperPig / MaimaiData",
        tag = "早期客户端参考",
        summary = "Android 端开源舞萌 DX 查询工具，为本项目早期提供了功能形态与部分静态贴图素材参考。",
        urlString = "https://github.com/PaperPig/MaimaiData",
        displayHost = "github.com/PaperPig/MaimaiData"
    )
)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val store = LocalDataStore.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val versionText = "v${BuildConfig.VERSION_NAME}"
    val iconShape = RoundedCornerShape(AppTheme.Radius.card)

    fun openExternalUrl(url: String) {
        ExternalLinkLauncher.open(context, url)
    }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "关于",
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
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                // 1. Minimalist App Identity Card
                AppCard(
                    padding = AppTheme.cardPadding,
                    tint = AppTheme.accent
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppTheme.Space.xs),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground_art),
                            contentDescription = "Re:MaiData",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(iconShape)
                                .border(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
                                    shape = iconShape
                                )
                        )

                        Text(
                            text = "Re:MaiData",
                            style = AppTheme.AppTypography.heroTitle,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        AppCapsuleLabel(
                            text = versionText,
                            style = CapsuleStyle.Count
                        )

                        Text(
                            text = "舞萌 DX 多区服曲库检索与成绩查询工具",
                            style = AppTheme.AppTypography.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }
                }

                // 2. 项目与反馈
                AppSectionCard(title = "项目与反馈") {
                    AppInsetGroup {
                        AppInsetRow(
                            title = "检查版本更新",
                            trailing = {
                                AppInlineButton(
                                    title = "检查更新",
                                    icon = Icons.Rounded.Refresh,
                                    isLoading = store.isCheckingAppUpdate,
                                    onClick = {
                                        scope.launch {
                                            store.checkAppUpdateIfNeeded(manual = true)
                                        }
                                    }
                                )
                            }
                        )

                        AppInsetDivider()

                        AppInsetRow(
                            title = "GitHub 开源仓库",
                            subtitle = "github.com/SorrowRain/MaimaiData-ReMaster",
                            monospacedSubtitle = true,
                            onClick = { openExternalUrl("https://github.com/SorrowRain/MaimaiData-ReMaster") },
                            trailing = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    tint = AppTheme.accentText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        AppInsetDivider()

                        AppInsetRow(
                            title = "版本发布与下载",
                            subtitle = "GitHub Releases",
                            monospacedSubtitle = true,
                            onClick = { openExternalUrl("https://github.com/SorrowRain/MaimaiData-ReMaster/releases") },
                            trailing = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    tint = AppTheme.accentText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )

                        AppInsetDivider()

                        AppInsetRow(
                            title = "问题反馈与建议",
                            subtitle = "GitHub Issues",
                            monospacedSubtitle = true,
                            onClick = { openExternalUrl("https://github.com/SorrowRain/MaimaiData-ReMaster/issues") },
                            trailing = {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                    contentDescription = null,
                                    tint = AppTheme.accentText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                    }
                }

                // 3. 开源信息
                AppSectionCard(title = "开源信息") {
                    AppInsetGroup {
                        AppInsetRow(
                            title = "开源协议",
                            onClick = { openExternalUrl("https://github.com/SorrowRain/MaimaiData-ReMaster/blob/main/LICENSE") },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "Apache-2.0",
                                        style = AppTheme.AppTypography.body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        tint = AppTheme.accentText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )

                        AppInsetDivider()

                        AppInsetRow(
                            title = "开发者",
                            onClick = { openExternalUrl("https://github.com/SorrowRain") },
                            trailing = {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "SorrowRain",
                                        style = AppTheme.AppTypography.body,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Icon(
                                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                        contentDescription = null,
                                        tint = AppTheme.accentText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    AppErrorDialog(
        title = "版本检查",
        message = store.statusMessage,
        onDismiss = { store.statusMessage = null }
    )
}

@Composable
fun AcknowledgmentsScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    fun openExternalUrl(url: String) {
        ExternalLinkLauncher.open(context, url)
    }

    AppPageBackground(tint = AppTheme.accent) {
        Scaffold(
            topBar = {
                AppTopBar(
                    title = "致谢与参考",
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
                    .padding(horizontal = AppTheme.pagePadding, vertical = AppTheme.Space.m),
                verticalArrangement = Arrangement.spacedBy(AppTheme.cardSpacing)
            ) {
                ReferenceSectionCard(
                    title = "查分器与核心数据源",
                    items = proberReferences,
                    onOpenUrl = ::openExternalUrl
                )

                ReferenceSectionCard(
                    title = "多区服数据与社区生态",
                    items = multiRegionReferences,
                    onOpenUrl = ::openExternalUrl
                )

                ReferenceSectionCard(
                    title = "客户端形态参考",
                    items = designReferences,
                    onOpenUrl = ::openExternalUrl
                )

                AppSectionCard(title = "版权与免责声明") {
                    AppInsetGroup(padding = AppTheme.Space.m) {
                        Text(
                            text = "《maimai でらっくす》与《舞萌 DX》游戏本体及其曲绘封面、版本标识、评级贴图等相关美术与音乐素材版权均归 SEGA 及华立科技（Wahlap）所有。本应用为非商用开源工具，仅作玩家个人谱面查询与算分辅助使用。",
                            style = AppTheme.AppTypography.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReferenceSectionCard(
    title: String,
    items: List<ReferenceEntry>,
    onOpenUrl: (String) -> Unit
) {
    AppSectionCard(title = title) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppTheme.itemSpacing)
        ) {
            items.forEach { item ->
                AppInsetGroup(
                    modifier = Modifier
                        .semantics {
                            contentDescription = "${item.name}，${item.tag}，点击在浏览器中打开"
                        }
                        .clickable(role = Role.Button) {
                            onOpenUrl(item.urlString)
                        },
                    padding = AppTheme.Space.m
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.s)
                        ) {
                            Text(
                                text = item.name,
                                style = AppTheme.AppTypography.songTitle,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            AppCapsuleLabel(
                                text = item.tag,
                                style = CapsuleStyle.Compact
                            )
                        }

                        Text(
                            text = item.summary,
                            style = AppTheme.AppTypography.caption,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                tint = AppTheme.accentText,
                                modifier = Modifier.size(13.dp)
                            )
                            Text(
                                text = item.displayHost,
                                style = AppTheme.AppTypography.captionMono,
                                color = AppTheme.accentText,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
