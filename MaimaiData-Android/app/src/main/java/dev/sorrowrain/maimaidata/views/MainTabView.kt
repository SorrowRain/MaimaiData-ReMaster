package dev.sorrowrain.maimaidata.views

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Leaderboard
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import dev.sorrowrain.maimaidata.bridges.ExternalLinkLauncher
import dev.sorrowrain.maimaidata.services.LocalDataStore
import dev.sorrowrain.maimaidata.theme.AppTheme
import kotlinx.serialization.Serializable

@Serializable
data object RouteSongList

@Serializable
data object RouteRatingTools

@Serializable
data object RouteSettings

@Serializable
data class RouteSongDetail(
    val songId: String,
    val initialDifficultyIndex: Int = -1
)

@Serializable
data object RouteProber

@Serializable
data object RouteVersionCheck

@Serializable
data object RouteLevelCheck

@Serializable
data object RouteCalculator

@Serializable
data object RouteFinaleToDx

@Serializable
data object RouteAccounts

@Serializable
data object RouteRegionSettings

@Serializable
data object RoutePreferencesSettings

@Serializable
data object RouteDataStorageSettings

@Serializable
data object RouteAbout

@Serializable
data object RouteAcknowledgments

private data class RootTabSpec<T : Any>(
    val route: T,
    val title: String,
    val icon: ImageVector,
    val matches: (NavDestination?) -> Boolean
)

@Composable
fun MainTabView() {
    val store = LocalDataStore.current
    val context = LocalContext.current
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val rootTabs = listOf(
        RootTabSpec(
            route = RouteSongList,
            title = "曲库",
            icon = Icons.Rounded.LibraryMusic,
            matches = { it?.hasRoute<RouteSongList>() == true }
        ),
        RootTabSpec(
            route = RouteRatingTools,
            title = "Rating 与查分",
            icon = Icons.Rounded.Leaderboard,
            matches = { it?.hasRoute<RouteRatingTools>() == true }
        ),
        RootTabSpec(
            route = RouteSettings,
            title = "设置与数据",
            icon = Icons.Rounded.Settings,
            matches = { it?.hasRoute<RouteSettings>() == true }
        )
    )

    val isOnRootTab = currentDestination.isRootTabDestination()

    val navigateToSong: (String, Int?) -> Unit = { songId, initialDifficultyIndex ->
        navController.navigate(
            RouteSongDetail(
                songId = songId,
                initialDifficultyIndex = initialDifficultyIndex ?: -1
            )
        )
    }

    val navigateBack: () -> Unit = {
        navController.popBackIfResumed()
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (isOnRootTab) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    HorizontalDivider(
                        thickness = 0.8.dp,
                        color = MaterialTheme.colorScheme.outlineVariant
                    )
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 0.dp
                    ) {
                        rootTabs.forEach { tab ->
                            val isSelected = tab.matches(currentDestination)
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = {
                                    if (!isSelected) {
                                        navController.navigate(tab.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                },
                                icon = {
                                    Icon(
                                        imageVector = tab.icon,
                                        contentDescription = tab.title
                                    )
                                },
                                label = {
                                    Text(
                                        text = tab.title,
                                        style = AppTheme.AppTypography.caption2Semibold,
                                        maxLines = 1
                                    )
                                },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = AppTheme.accentText,
                                    selectedTextColor = AppTheme.accentText,
                                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            )
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        val bottomPadding = innerPadding.calculateBottomPadding()

        NavHost(
            navController = navController,
            startDestination = RouteSongList,
            enterTransition = {
                if (initialState.destination.isRootTabDestination() &&
                    targetState.destination.isRootTabDestination()
                ) {
                    fadeIn(animationSpec = tween(160))
                } else {
                    slideInHorizontally(animationSpec = tween(240)) { it / 8 } +
                        fadeIn(animationSpec = tween(200))
                }
            },
            exitTransition = { fadeOut(animationSpec = tween(160)) },
            popEnterTransition = {
                fadeIn(animationSpec = tween(180))
            },
            popExitTransition = {
                slideOutHorizontally(animationSpec = tween(220)) { it / 8 } +
                    fadeOut(animationSpec = tween(160))
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(bottom = bottomPadding)
                .consumeWindowInsets(PaddingValues(bottom = bottomPadding))
        ) {
            composable<RouteSongList> {
                SongListScreen(onOpenSong = navigateToSong)
            }

            composable<RouteRatingTools> {
                RatingToolsScreen(
                    onNavigateProber = { navController.navigate(RouteProber) },
                    onNavigateVersionCheck = { navController.navigate(RouteVersionCheck) },
                    onNavigateLevelCheck = { navController.navigate(RouteLevelCheck) },
                    onNavigateCalculator = { navController.navigate(RouteCalculator) },
                    onNavigateFinaleToDx = { navController.navigate(RouteFinaleToDx) },
                    onNavigateAccountManagement = { navController.navigate(RouteAccounts) }
                )
            }

            composable<RouteSettings> {
                SettingsScreen(
                    onNavigateAccounts = { navController.navigate(RouteAccounts) },
                    onNavigateRegion = { navController.navigate(RouteRegionSettings) },
                    onNavigatePreferences = { navController.navigate(RoutePreferencesSettings) },
                    onNavigateDataStorage = { navController.navigate(RouteDataStorageSettings) },
                    onNavigateAbout = { navController.navigate(RouteAbout) },
                    onNavigateAcknowledgments = { navController.navigate(RouteAcknowledgments) }
                )
            }

            composable<RouteSongDetail> { backStackEntry ->
                val route = backStackEntry.toRoute<RouteSongDetail>()
                SongDetailScreen(
                    songId = route.songId,
                    initialDifficultyIndex = route.initialDifficultyIndex.takeIf { it >= 0 },
                    onBack = navigateBack
                )
            }

            composable<RouteProber> {
                ProberScreen(
                    onBack = navigateBack,
                    onOpenSong = navigateToSong
                )
            }

            composable<RouteVersionCheck> {
                VersionCheckScreen(
                    onBack = navigateBack,
                    onOpenSong = navigateToSong
                )
            }

            composable<RouteLevelCheck> {
                LevelCheckScreen(
                    onBack = navigateBack,
                    onOpenSong = navigateToSong
                )
            }

            composable<RouteCalculator> {
                RatingCalculatorScreen(onBack = navigateBack)
            }

            composable<RouteFinaleToDx> {
                FinaleToDxScreen(onBack = navigateBack)
            }

            composable<RouteAccounts> {
                AccountManagementScreen(onBack = navigateBack)
            }

            composable<RouteRegionSettings> {
                ServerRegionSettingsScreen(onBack = navigateBack)
            }

            composable<RoutePreferencesSettings> {
                PreferencesSettingsScreen(onBack = navigateBack)
            }

            composable<RouteDataStorageSettings> {
                DataAndStorageSettingsScreen(onBack = navigateBack)
            }

            composable<RouteAbout> {
                AboutScreen(onBack = navigateBack)
            }

            composable<RouteAcknowledgments> {
                AcknowledgmentsScreen(onBack = navigateBack)
            }
        }
    }

    val pendingRelease = store.availableAppUpdate
    if (pendingRelease != null) {
        AlertDialog(
            onDismissRequest = { store.availableAppUpdate = null },
            shape = RoundedCornerShape(AppTheme.Radius.card),
            containerColor = MaterialTheme.colorScheme.surface,
            title = {
                Text(
                    text = "发现新版本 v${pendingRelease.version}",
                    style = AppTheme.AppTypography.cardTitle,
                    color = MaterialTheme.colorScheme.onSurface
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 260.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = pendingRelease.notes.ifEmpty {
                            "已有新版本发布，点击下方前往 GitHub Releases 下载更新。"
                        },
                        style = AppTheme.AppTypography.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(AppTheme.Space.xs)) {
                    TextButton(
                        onClick = { store.ignoreAppUpdate(pendingRelease.version) }
                    ) {
                        Text(
                            text = "忽略此版本",
                            style = AppTheme.AppTypography.rowTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = { store.availableAppUpdate = null }
                    ) {
                        Text(
                            text = "稍后",
                            style = AppTheme.AppTypography.rowTitle,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val url = pendingRelease.htmlUrl
                        store.availableAppUpdate = null
                        ExternalLinkLauncher.open(context, url)
                    }
                ) {
                    Text(
                        text = "前往下载",
                        style = AppTheme.AppTypography.subheadlineBold,
                        color = AppTheme.accentText
                    )
                }
            }
        )
    }
}

private fun NavDestination?.isRootTabDestination(): Boolean {
    if (this == null) return false
    return hasRoute<RouteSongList>() ||
        hasRoute<RouteRatingTools>() ||
        hasRoute<RouteSettings>()
}

private fun NavHostController.popBackIfResumed() {
    val currentEntry = currentBackStackEntry
    if (currentEntry != null &&
        currentEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
        previousBackStackEntry != null
    ) {
        popBackStack()
    }
}
