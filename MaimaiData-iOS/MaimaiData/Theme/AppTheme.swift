import SwiftUI

/// Global design tokens for MaimaiData.
///
/// Architectural rules:
/// 1. All colors, corner radii, spacing scales, and font styles must come from `AppTheme`.
/// 2. Game-domain colors in `Palette` (difficulty, genre, note types, judgments) match the
///    maimai DX visual identity and must not be altered arbitrarily.
/// 3. `Typography` defines semantic text roles rather than one-off view-specific font literals.
enum AppTheme {
    // MARK: - Brand & Accent Colors
    static let accent = Color(hex: 0x1668DC)
    static let accentBright = Color(hex: 0x2B88FF)
    /// Elevated dark-mode blue for text over sheetCard/raised surfaces (`#4EA1FF`).
    static let accentBrightElevated = Color(hex: 0x4EA1FF)
    /// High-contrast brand blue for foreground text (dark mode automatically uses `accentBright`).
    static let accentText = Color.adaptive(light: accent, dark: accentBright)
    static let accentTextElevated = Color.adaptive(light: accent, dark: accentBrightElevated)
    static let favoritePink = Palette.noteBreak
    static let destructive = Color(.systemRed)
    static let warning = Color(.systemOrange)

    static func accentText(for surfaceLevel: AppSurfaceLevel) -> Color {
        surfaceLevel == .sheet ? accentTextElevated : accentText
    }

    // MARK: - Semantic Palette (game terms + shared surfaces; values are locked)
    enum Palette {
        // Notes
        static let noteTap = Color(hex: 0x12B4FF)
        static let noteHold = Color(hex: 0xFF9C24)
        static let noteSlide = Color(hex: 0x2FCA4C)
        static let noteBreak = Color(hex: 0xF43F7E)

        // Chart / delta
        static let chartRemaster = Color(hex: 0x762D8C)
        /// Solid lavender-white Re:MASTER surface & deep-purple text in dark mode (single source of truth).
        static let remasterDarkFill = Color(hex: 0xECE2F4)
        static let remasterDarkText = Color(hex: 0x43155F)
        static let remasterDarkSubtleFill = Color(hex: 0xECE2F4).opacity(0.20)
        static let remasterDarkSubtleText = Color(hex: 0xF0E8F7)
        static let remasterDarkSubtleBorder = Color(hex: 0xECE2F4).opacity(0.55)
        static let deltaUp = Color(hex: 0xE53935)
        static let deltaDown = Color(hex: 0x1E8E3E)

        // Judgments
        static let judgePerfect = Color(hex: 0x854400)
        static let judgeGreat = Color(hex: 0xB72267)

        // Rating tool row icons (shared with note / difficulty colors)
        static let ratingPurple = Color(hex: 0x9F51DC)
        static let ratingOrange = noteHold
        static let ratingBlue = noteTap

        // Difficulty fills & readable text
        static let diffBasic = Color(hex: 0x6FD43D)
        static let diffAdvanced = Color(hex: 0xF8B709)
        static let diffExpert = Color(hex: 0xFF818D)
        static let diffMaster = ratingPurple
        static let diffRemaster = Color(hex: 0xCB5EEA)
        static let diffUtage = Color(hex: 0xB12876)
        static let diffBasicText = Color(hex: 0x183C12)
        static let diffAdvancedText = Color(hex: 0x4B3700)
        static let diffExpertText = Color(hex: 0x681B28)
        static let diffBasicDark = Color(hex: 0x025235)
        static let diffAdvancedDark = Color(hex: 0xC7450C)
        static let diffExpertDark = Color(hex: 0xC02138)
        static let diffMasterDark = Color(hex: 0x671B8D)
        static let diffRemasterDark = Color(hex: 0x8C2DD5)
        static let diffOtherDark = Color(hex: 0x053264)

        // Brightened difficulty colors for text/board on dark tinted backgrounds
        static let boardMainBasic = Color(hex: 0x6FE163)
        static let boardMainAdvanced = Color(hex: 0xF8DF3B)
        static let boardMainExpert = Color(hex: 0xFFA0AE)
        static let boardMainMaster = Color(hex: 0xC27FF4)
        static let boardMainRemaster = Color(hex: 0xE5DDEA)

        // Genres: (fill, stroke) and light-mode text
        static let genrePop: (bg: Color, stroke: Color) = (bg: Color(hex: 0xFF9C24), stroke: Color(hex: 0xFFD21F))
        static let genreNico: (bg: Color, stroke: Color) = (bg: Color(hex: 0x12B4FF), stroke: Color(hex: 0x7ED6FF))
        static let genreTouhou: (bg: Color, stroke: Color) = (bg: Color(hex: 0xCA6FEB), stroke: Color(hex: 0xE8A7FF))
        static let genreGame: (bg: Color, stroke: Color) = (bg: Color(hex: 0x287516), stroke: Color(hex: 0xBAFBA3))
        static let genreMaimai: (bg: Color, stroke: Color) = (bg: Color(hex: 0xFF4646), stroke: Color(hex: 0xFFA7A7))
        static let genreUtage: (bg: Color, stroke: Color) = (bg: Color(hex: 0xDC39B8), stroke: diffUtage)
        static let genreOther: (bg: Color, stroke: Color) = (bg: Color(hex: 0x319DF8), stroke: Color(hex: 0x99D1FF))
        static let genrePopText = Color(hex: 0x8A4800)
        static let genreNicoText = Color(hex: 0x055C84)
        static let genreTouhouText = chartRemaster
        static let genreGameText = Color(hex: 0x1C560C)
        static let genreMaimaiText = Color(hex: 0x9A1B1B)
        static let genreUtageText = Color(hex: 0x8F1863)
        static let genreOtherText = Color(hex: 0x125BB8)

        // Checklist stat pills (solid fill tokens darken SSS/AP for >= 4.5:1 white text contrast)
        static let statSSS = Color(hex: 0xB46900)
        static let statSSSFill = Color(hex: 0xA35F00)
        static let statFC = Color(hex: 0x237818)
        static let statAP = Color(hex: 0xC85A00)
        static let statAPFill = Color(hex: 0xB55000)
        static let statFSD = Color(hex: 0x0A6BA1)

        // Level progress header tint
        static let progressAccent = noteHold

        // Neutral dark & light surfaces
        static let pageDark = Color(hex: 0x0F0F10)
        static let surfaceDark = Color(hex: 0x1A1A1C)
        static let insetDark = Color(hex: 0x242427)
        static let sheetDark = Color(hex: 0x1C1C1E)
        static let sheetCardDark = Color(hex: 0x2A2A2D)
        static let sheetInsetDark = Color(hex: 0x343437)
        static let raisedDark = Color(hex: 0x2E2E32)

        static let pageLight = Color(hex: 0xF2F2F7)
        static let sheetLight = Color(hex: 0xF2F2F7)
        static let sheetBackground = Color.adaptive(light: sheetLight, dark: sheetDark)

        static let hairline = Color.black
        static let genreGameCardFill = Color(hex: 0x398428)
    }

    // MARK: - Spacing & Sizing
    static let pagePadding: CGFloat = 16
    static let cardPadding: CGFloat = 16
    static let compactCardPadding: CGFloat = 12
    static let cardSpacing: CGFloat = 12
    static let itemSpacing: CGFloat = 10
    static let rowMinHeight: CGFloat = 50

    // MARK: - Corner Radii (Always use .continuous)
    enum Radius {
        static let card: CGFloat = 16
        static let inset: CGFloat = 12
        static let button: CGFloat = 14
        static let input: CGFloat = 12
        static let badge: CGFloat = 6
        static let tile: CGFloat = 10
        static let chip: CGFloat = 12
    }

    // MARK: - Spacing Scale
    enum Space {
        static let xs: CGFloat = 4
        static let s: CGFloat = 8
        static let m: CGFloat = 12
        static let l: CGFloat = 16
        static let xl: CGFloat = 24
    }

    // MARK: - Semantic Typography Hierarchy
    // Visual hierarchy: 20pt Hero > 17pt Card Title > 15pt Row / Input > 12pt Caption / Inline Action > 11pt Badge
    enum Typography {
        // 1. Hero & Section Headers (20pt / 17pt / 16pt)
        static let heroTitle = Font.title3.weight(.bold).monospacedDigit()
        /// Proportional 20pt bold for centered KPI metric tiles (avoids tabular '1' left-sidebearing shift).
        static let metricDisplay = Font.title3.weight(.bold)
        static let sectionMetric = Font.headline.weight(.bold).monospacedDigit()
        static let cardTitle = Font.headline

        // 2. Row, Button & Input Content (15pt)
        static let songTitle = Font.subheadline.weight(.semibold)
        static let subheadlineBold = Font.subheadline.weight(.bold)
        static let rowTitle = Font.subheadline.weight(.medium)
        static let input = Font.subheadline.weight(.medium).monospacedDigit()
        static let rowMetric = Font.subheadline.weight(.bold).monospacedDigit()
        static let body = Font.subheadline

        // 3. Captions, Tags & Inline Actions (12pt)
        static let inlineAction = Font.caption.weight(.semibold)
        static let captionBold = Font.caption.weight(.bold)
        static let tag = Font.caption.weight(.medium)
        static let caption = Font.caption
        static let captionMono = Font.caption.monospacedDigit()
        static let captionMeta = Font.caption.weight(.semibold).monospacedDigit()
        static let captionMetaBold = Font.caption.weight(.bold).monospacedDigit()

        // 4. Compact Badges & Micro Labels (11pt)
        static let badge = Font.caption2.weight(.bold).monospacedDigit()
        static let caption2Bold = Font.caption2.weight(.bold)
        static let caption2Semibold = Font.caption2.weight(.semibold)
        static let caption2 = Font.caption2
    }
}

enum AppSurfaceLevel: Equatable {
    case page
    case sheet
}

private struct AppSurfaceLevelKey: EnvironmentKey {
    static let defaultValue: AppSurfaceLevel = .page
}

extension EnvironmentValues {
    var appSurfaceLevel: AppSurfaceLevel {
        get { self[AppSurfaceLevelKey.self] }
        set { self[AppSurfaceLevelKey.self] = newValue }
    }
}

struct AppPageBackground: View {
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        (colorScheme == .dark ? AppTheme.Palette.pageDark : AppTheme.Palette.pageLight)
            .ignoresSafeArea()
    }
}

extension View {
    func appPageBackground() -> some View {
        background {
            AppPageBackground()
        }
    }

    func appSurfaceLevel(_ level: AppSurfaceLevel) -> some View {
        environment(\.appSurfaceLevel, level)
    }

    func appSheetContainer() -> some View {
        appSurfaceLevel(.sheet)
            .presentationBackground(AppTheme.Palette.sheetBackground)
    }

    /// Cap Dynamic Type size inside dense multi-column grids / badge rows so layout holds cleanly.
    func appDenseDynamicType() -> some View {
        dynamicTypeSize(...DynamicTypeSize.xxxLarge)
    }
}
