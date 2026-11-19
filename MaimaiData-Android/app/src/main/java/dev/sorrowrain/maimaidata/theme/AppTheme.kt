package dev.sorrowrain.maimaidata.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val LocalIsDarkTheme = staticCompositionLocalOf { false }

/**
 * Global Material 3 + maimai DX domain design tokens (1:1 locked palette with iOS `AppTheme.swift`).
 */
object AppTheme {
    // Brand & Accent Colors
    val accent = Color(0xFF1668DC)
    val accentBright = Color(0xFF2B88FF)
    val favoritePink = Palette.noteBreak
    val destructive = Color(0xFFDC2626)
    val destructiveDark = Color(0xFFF87171)
    val warning = Color(0xFFD97706)
    val warningDark = Color(0xFFFBBF24)

    val accentText: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) accentBright else accent

    val destructiveText: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) destructiveDark else destructive

    val warningText: Color
        @Composable
        @ReadOnlyComposable
        get() = if (LocalIsDarkTheme.current) warningDark else warning

    object Palette {
        // Notes
        val noteTap = Color(0xFF12B4FF)
        val noteHold = Color(0xFFFF9C24)
        val noteSlide = Color(0xFF2FCA4C)
        val noteBreak = Color(0xFFF43F7E)

        // Chart / delta
        val chartRemaster = Color(0xFF762D8C)
        val deltaUp = Color(0xFFE53935)
        val deltaDown = Color(0xFF1E8E3E)

        // Judgments
        val judgePerfect = Color(0xFF854400)
        val judgeGreat = Color(0xFFB72267)

        // Rating tool row icons
        val ratingPurple = Color(0xFF9F51DC)
        val ratingOrange = noteHold
        val ratingBlue = noteTap

        // Difficulty fills & readable text
        val diffBasic = Color(0xFF6FD43D)
        val diffAdvanced = Color(0xFFF8B709)
        val diffExpert = Color(0xFFFF818D)
        val diffMaster = ratingPurple
        val diffRemaster = Color(0xFFCB5EEA)
        val diffUtage = Color(0xFFB12876)

        val diffBasicText = Color(0xFF183C12)
        val diffAdvancedText = Color(0xFF4B3700)
        val diffExpertText = Color(0xFF681B28)

        val diffBasicDark = Color(0xFF025235)
        val diffAdvancedDark = Color(0xFFC7450C)
        val diffExpertDark = Color(0xFFC02138)
        val diffMasterDark = Color(0xFF671B8D)
        val diffRemasterDark = Color(0xFF8C2DD5)
        val diffOtherDark = Color(0xFF053264)

        val boardMainBasic = Color(0xFF6FE163)
        val boardMainAdvanced = Color(0xFFF8DF3B)
        val boardMainExpert = Color(0xFFFFA0AE)
        val boardMainMaster = Color(0xFFC27FF4)
        val boardMainRemaster = Color(0xFFE5DDEA)

        // Genres (fill, stroke) & light-mode readable text
        val genrePopBg = Color(0xFFFF9C24)
        val genrePopStroke = Color(0xFFFFD21F)
        val genreNicoBg = Color(0xFF12B4FF)
        val genreNicoStroke = Color(0xFF7ED6FF)
        val genreTouhouBg = Color(0xFFCA6FEB)
        val genreTouhouStroke = Color(0xFFE8A7FF)
        val genreGameBg = Color(0xFF287516)
        val genreGameStroke = Color(0xFFBAFBA3)
        val genreMaimaiBg = Color(0xFFFF4646)
        val genreMaimaiStroke = Color(0xFFFFA7A7)
        val genreUtageBg = Color(0xFFDC39B8)
        val genreUtageStroke = diffUtage
        val genreOtherBg = Color(0xFF319DF8)
        val genreOtherStroke = Color(0xFF99D1FF)

        val genrePopText = Color(0xFF8A4800)
        val genreNicoText = Color(0xFF055C84)
        val genreTouhouText = chartRemaster
        val genreGameText = Color(0xFF1C560C)
        val genreMaimaiText = Color(0xFF9A1B1B)
        val genreUtageText = Color(0xFF8F1863)
        val genreOtherText = Color(0xFF125BB8)
        val genreGameCardFill = Color(0xFF398428)

        // Checklist stat pills
        val statSSS = Color(0xFFB46900)
        val statFC = Color(0xFF237818)
        val statAP = Color(0xFFC85A00)
        val statFSD = Color(0xFF0A6BA1)

        val progressAccent = noteHold

        // Surfaces & page backgrounds (Material 3 clean neutral-blue tonal surfaces)
        val surfaceLight = Color(0xFFFFFFFF)
        val surfaceDark = Color(0xFF172135)
        val insetLight = Color(0xFFF3F6FC)
        val insetDark = Color(0xFF101829)
        val pageDarkGradient = listOf(Color(0xFF0B1120), Color(0xFF0F172A), Color(0xFF111827))
        val pageLightGradient = listOf(Color(0xFFEFF4FC), Color(0xFFF5F8FD), Color(0xFFF1F5FB))
    }

    // Macro Layout Constants
    val pagePadding = 16.dp
    val cardPadding = 16.dp
    val compactCardPadding = 12.dp
    val cardSpacing = 12.dp
    val itemSpacing = 10.dp
    val rowMinHeight = 52.dp

    object Radius {
        val card = 16.dp
        val inset = 12.dp
        val button = 14.dp
        val input = 12.dp
        val badge = 6.dp
        val tile = 10.dp
        val chip = 12.dp
    }

    object Space {
        val xs = 4.dp
        val s = 8.dp
        val m = 12.dp
        val l = 16.dp
        val xl = 24.dp
    }

    object AppTypography {
        private const val TABULAR = "tnum"
        private val tightPlatform = PlatformTextStyle(includeFontPadding = false)
        private val cjkLocale = LocaleList("zh-CN")

        // L1 Hero & Section Headers
        val heroTitle = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val metricDisplay = TextStyle(fontSize = 20.sp, lineHeight = 25.sp, fontWeight = FontWeight.Bold, platformStyle = tightPlatform, localeList = cjkLocale)
        val sectionMetric = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val cardTitle = TextStyle(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, platformStyle = tightPlatform, localeList = cjkLocale)

        // L2 Row, Button & Input Content
        val songTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, platformStyle = tightPlatform, localeList = cjkLocale)
        val subheadlineBold = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, platformStyle = tightPlatform, localeList = cjkLocale)
        val rowTitle = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, platformStyle = tightPlatform, localeList = cjkLocale)
        val input = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val rowMetric = TextStyle(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val body = TextStyle(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Normal, platformStyle = tightPlatform, localeList = cjkLocale)

        // L3 Captions, Tags & Inline Actions
        val inlineAction = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, platformStyle = tightPlatform, localeList = cjkLocale)
        val captionBold = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, platformStyle = tightPlatform, localeList = cjkLocale)
        val tag = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, platformStyle = tightPlatform, localeList = cjkLocale)
        val caption = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, platformStyle = tightPlatform, localeList = cjkLocale)
        val captionMono = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val captionMeta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val captionMetaBold = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)

        // L4 Compact Badges & Micro Labels
        val badge = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TABULAR, platformStyle = tightPlatform, localeList = cjkLocale)
        val caption2Bold = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Bold, platformStyle = tightPlatform, localeList = cjkLocale)
        val caption2Semibold = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold, platformStyle = tightPlatform, localeList = cjkLocale)
        val caption2 = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Normal, platformStyle = tightPlatform, localeList = cjkLocale)
    }
}

private val LightColorScheme = lightColorScheme(
    primary = AppTheme.accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8FC),
    onPrimaryContainer = Color(0xFF072B61),
    secondary = Color(0xFF4A5D7A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDCE4F2),
    onSecondaryContainer = Color(0xFF152238),
    background = Color(0xFFF2F6FC),
    onBackground = Color(0xFF0F172A),
    surface = AppTheme.Palette.surfaceLight,
    onSurface = Color(0xFF0F172A),
    surfaceVariant = AppTheme.Palette.insetLight,
    onSurfaceVariant = Color(0xFF526077),
    outline = Color(0xFFCBD5E1),
    outlineVariant = Color(0xFFE2E8F0),
    error = AppTheme.destructive,
    onError = Color.White
)

private val DarkColorScheme = darkColorScheme(
    primary = AppTheme.accentBright,
    onPrimary = Color(0xFF002D62),
    primaryContainer = Color(0xFF103C78),
    onPrimaryContainer = Color(0xFFD6E6FF),
    secondary = Color(0xFFB3C5E2),
    onSecondary = Color(0xFF1D2E47),
    secondaryContainer = Color(0xFF283A56),
    onSecondaryContainer = Color(0xFFD7E3F8),
    background = Color(0xFF0B1120),
    onBackground = Color(0xFFF1F5F9),
    surface = AppTheme.Palette.surfaceDark,
    onSurface = Color(0xFFF1F5F9),
    surfaceVariant = AppTheme.Palette.insetDark,
    onSurfaceVariant = Color(0xFF94A3B8),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF1E293B),
    error = AppTheme.destructiveDark,
    onError = Color(0xFF450A0A)
)

private val M3Typography = Typography(
    titleLarge = AppTheme.AppTypography.heroTitle,
    titleMedium = AppTheme.AppTypography.cardTitle,
    titleSmall = AppTheme.AppTypography.songTitle,
    bodyLarge = AppTheme.AppTypography.rowTitle,
    bodyMedium = AppTheme.AppTypography.body,
    bodySmall = AppTheme.AppTypography.caption,
    labelLarge = AppTheme.AppTypography.inlineAction,
    labelMedium = AppTheme.AppTypography.tag,
    labelSmall = AppTheme.AppTypography.caption2Semibold
)

@Composable
fun MaimaiDataTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = M3Typography,
            content = content
        )
    }
}

@Composable
fun AppPageBackground(
    tint: Color = AppTheme.accent,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = LocalIsDarkTheme.current
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = if (isDark) AppTheme.Palette.pageDarkGradient else AppTheme.Palette.pageLightGradient
                )
            )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            tint.copy(alpha = if (isDark) 0.14f else 0.09f),
                            Color.Transparent
                        )
                    )
                )
        )
        content()
    }
}
