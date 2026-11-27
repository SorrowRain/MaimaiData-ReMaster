import SwiftUI

/// Standardized tinted capsule label with semantic presets.
///
/// Do not add ad-hoc padding/opacity/font parameters here; use one of the defined `Style` cases
/// so all pills across the app share consistent sizing and dark-mode contrast.
struct AppCapsuleLabel: View {
    enum Style {
        /// Section header count pill (e.g. "24 首").
        case count
        /// Active filter summary pill with subtle border.
        case filter
        /// Compact inline status/rank badge (e.g. "#1", "当前", "4x").
        case compact
        /// Prominent metric pill with a prefix label (e.g. "Ra 333").
        case metric(prefix: String)
    }

    @Environment(\.colorScheme) private var colorScheme
    let text: String
    var tint: Color = AppTheme.accent
    var isNeutral: Bool? = nil
    var style: Style = .count
    var onRemove: (() -> Void)? = nil

    var body: some View {
        if let onRemove {
            Button(action: onRemove) {
                capsuleBody
            }
            .buttonStyle(.plain)
            .accessibilityLabel("移除筛选：\(text)")
        } else {
            capsuleBody
        }
    }

    private var capsuleBody: some View {
        let isDark = colorScheme == .dark
        let spec = resolvedSpec
        let useNeutral = isNeutral ?? (tint == .secondary || style.isCount)

        return HStack(spacing: AppTheme.Space.xs) {
            if let prefix = spec.prefix {
                Text(prefix)
                    .font(AppTheme.Typography.captionBold)
            }
            Text(text)
                .font(spec.font)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .contentTransition(.numericText())
            if onRemove != nil {
                Image(systemName: "xmark")
                    .font(AppTheme.Typography.caption2Bold)
                    .opacity(0.75)
            }
        }
        .fixedSize(horizontal: true, vertical: false)
        .padding(.horizontal, spec.hPadding)
        .padding(.vertical, spec.vPadding)
        .background(
            useNeutral
                ? Color.primary.opacity(isDark ? 0.10 : 0.06)
                : tint.opacity(isDark ? max(spec.bgOpacity, 0.22) : spec.bgOpacity),
            in: Capsule()
        )
        .overlay(
            Capsule().strokeBorder(
                useNeutral
                    ? Color.primary.opacity(spec.strokeOpacity > 0 ? 0.18 : 0)
                    : tint.opacity(isDark && spec.strokeOpacity > 0 ? max(spec.strokeOpacity, 0.42) : spec.strokeOpacity),
                lineWidth: spec.strokeOpacity > 0 ? 0.8 : 0
            )
        )
        .foregroundStyle(
            useNeutral
                ? Color.secondary
                : (isDark ? tint.brightened(toAtLeast: 1.0, maxSaturation: 0.5) : tint)
        )
        .contentShape(Capsule())
    }

    private struct Spec {
        let font: Font
        let hPadding: CGFloat
        let vPadding: CGFloat
        let bgOpacity: Double
        let strokeOpacity: Double
        var prefix: String? = nil
    }

    private var resolvedSpec: Spec {
        switch style {
        case .count:
            return Spec(font: AppTheme.Typography.captionMeta, hPadding: 8, vPadding: 3, bgOpacity: 0.12, strokeOpacity: 0)
        case .filter:
            return Spec(font: AppTheme.Typography.caption2Semibold, hPadding: 9, vPadding: 4, bgOpacity: 0.14, strokeOpacity: 0.28)
        case .compact:
            return Spec(font: AppTheme.Typography.caption2Bold, hPadding: 6, vPadding: 2, bgOpacity: 0.14, strokeOpacity: 0)
        case .metric(let prefix):
            return Spec(font: AppTheme.Typography.heroTitle, hPadding: 10, vPadding: 4, bgOpacity: 0.12, strokeOpacity: 0, prefix: prefix)
        }
    }
}

private extension AppCapsuleLabel.Style {
    var isCount: Bool {
        if case .count = self { return true }
        return false
    }
}

/// Inset metric box: caption label above a metric value or custom content.
struct AppMetricTile<Content: View>: View {
    let title: String
    var highlight: Bool = false
    var valueSlotHeight: CGFloat? = nil
    @ViewBuilder let content: () -> Content

    init(
        title: String,
        highlight: Bool = false,
        valueSlotHeight: CGFloat? = nil,
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.highlight = highlight
        self.valueSlotHeight = valueSlotHeight
        self.content = content
    }

    init(
        title: String,
        value: String,
        highlight: Bool = false,
        valueSlotHeight: CGFloat? = nil
    ) where Content == AnyView {
        self.init(title: title, highlight: highlight, valueSlotHeight: valueSlotHeight) {
            AnyView(
                Text(value)
                    .font(AppTheme.Typography.sectionMetric)
                    .foregroundStyle(highlight ? AppTheme.accentText : .primary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            )
        }
    }

    var body: some View {
        AppInsetGroup {
            VStack(alignment: .center, spacing: 5) {
                Text(title)
                    .font(AppTheme.Typography.caption2Semibold)
                    .foregroundStyle(highlight ? AppTheme.accentText : .secondary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.85)
                if let valueSlotHeight {
                    content()
                        .frame(maxWidth: .infinity, minHeight: valueSlotHeight, maxHeight: valueSlotHeight, alignment: .center)
                } else {
                    content()
                }
            }
            .padding(.horizontal, AppTheme.Space.s)
            .padding(.top, 12)
            .padding(.bottom, valueSlotHeight != nil ? 11 : 12)
            .frame(maxWidth: .infinity, alignment: .center)
        }
    }
}

/// Fixed-width value badge paired with sliders (e.g. "ALL", "13+", ".8").
struct AppValueBadge: View {
    @Environment(\.colorScheme) private var colorScheme
    let text: String
    let isActive: Bool

    var body: some View {
        let isDark = colorScheme == .dark
        Text(text)
            .font(AppTheme.Typography.captionMetaBold)
            .foregroundStyle(isActive ? AppTheme.accentText : .secondary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .padding(.horizontal, 6)
            .frame(minWidth: 44, minHeight: 24)
            .background(
                isActive
                    ? AppTheme.accent.opacity(isDark ? 0.22 : 0.13)
                    : Color.primary.opacity(isDark ? 0.10 : 0.06),
                in: RoundedRectangle(cornerRadius: AppTheme.Radius.badge, style: .continuous)
            )
    }
}

/// Statistic tile: caption title above value, tinted background (solid active fill, translucent unselected fill).
struct AppStatTile: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String
    let value: String
    let tint: Color
    var fillTint: Color? = nil
    var isSelected: Bool = false

    var body: some View {
        let isDark = colorScheme == .dark
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.tile, style: .continuous)
        let activeFill = fillTint ?? tint
        let unselectedTitleColor = isDark
            ? tint.brightened(toAtLeast: 0.95, maxSaturation: 0.6)
            : tint

        VStack(spacing: 3) {
            Text(title)
                .font(AppTheme.Typography.caption2Bold)
                .foregroundStyle(isSelected ? Color.white : unselectedTitleColor)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
            Text(value)
                .font(AppTheme.Typography.captionMeta)
                .foregroundStyle(isSelected ? Color.white.opacity(0.95) : .primary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
                .contentTransition(.numericText())
        }
        .frame(maxWidth: .infinity, minHeight: 44)
        .padding(.vertical, AppTheme.Space.s)
        .padding(.horizontal, AppTheme.Space.xs)
        .background(
            isSelected ? activeFill : tint.opacity(isDark ? 0.14 : 0.10),
            in: shape
        )
        .contentShape(shape)
        .appDenseDynamicType()
    }
}

struct DifficultyLevelBadge: View {
    @Environment(\.colorScheme) private var colorScheme
    let difficulty: DifficultyType
    let text: String

    var body: some View {
        let isRemaster = difficulty == .remaster
        let isDark = colorScheme == .dark
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.badge, style: .continuous)
        let fgColor: Color = if isRemaster {
            isDark ? AppTheme.Palette.remasterDarkText : AppTheme.Palette.chartRemaster
        } else {
            difficulty.readableForeground
        }
        let bgColor: Color = if isRemaster {
            isDark ? AppTheme.Palette.remasterDarkFill : Color.white
        } else {
            difficulty.solidFill(isDark: isDark)
        }
        let strokeColor: Color = if isRemaster {
            isDark ? difficulty.color.opacity(0.45) : difficulty.color
        } else {
            Color.black.opacity(0.08)
        }

        Text(text)
            .font(AppTheme.Typography.badge)
            .foregroundStyle(fgColor)
            .frame(minWidth: 36, minHeight: 22)
            .padding(.horizontal, 6)
            .padding(.vertical, 2)
            .background(bgColor, in: shape)
            .overlay(
                shape.strokeBorder(strokeColor, lineWidth: isRemaster ? 1.5 : 0.5)
            )
    }
}

/// Unified genre-tinted capsule chrome (light 15% fill / 30% border, dark 22% fill / 45% border).
private struct GenreTagChrome: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let genre: String

    func body(content: Content) -> some View {
        let bg = MaimaiConstants.genreColors(for: genre).bg
        let dark = colorScheme == .dark
        content
            .background(bg.opacity(dark ? 0.22 : 0.15), in: Capsule())
            .overlay(Capsule().strokeBorder(bg.opacity(dark ? 0.45 : 0.30), lineWidth: 0.8))
            .foregroundStyle(MaimaiConstants.genreTextColor(for: genre))
    }
}

struct GenreCapsuleTag: View {
    enum Style {
        /// Compact song-row genre label (11pt).
        case compact
        /// Regular detail-page alias capsule (12pt).
        case regular
    }

    let genre: String
    var text: String? = nil
    var style: Style = .compact

    var body: some View {
        let isRegular = style == .regular
        let displayText = text ?? MaimaiConstants.shortGenreDisplayName(for: genre)
        Text(displayText)
            .font(isRegular ? AppTheme.Typography.tag : AppTheme.Typography.caption2Semibold)
            .lineLimit(1)
            .padding(.horizontal, isRegular ? AppTheme.itemSpacing : AppTheme.Space.s)
            .padding(.vertical, isRegular ? 5 : 3)
            .modifier(GenreTagChrome(genre: genre))
    }
}

/// Rank + optional FC / FS asset badges with accessible VoiceOver labels.
struct RecordStatusBadges: View {
    enum Size {
        case compact
        case regular
    }

    let record: ProberRecord
    var size: Size = .compact
    var region: MaimaiConstants.ServerRegion = .cn

    var body: some View {
        let rankHeight: CGFloat = size == .regular ? 20 : 16
        let markHeight: CGFloat = size == .regular ? 22 : 18

        HStack(spacing: AppTheme.Space.xs) {
            Image(record.rankAssetName(for: region))
                .resizable()
                .scaledToFit()
                .frame(height: rankHeight)
                .accessibilityLabel(record.rankAccessibilityLabel)

            if let fc = record.fcAssetName {
                Image(fc)
                    .resizable()
                    .scaledToFit()
                    .frame(height: markHeight)
                    .accessibilityLabel(record.fcAccessibilityLabel)
            }

            if let fs = record.fsAssetName {
                Image(fs)
                    .resizable()
                    .scaledToFit()
                    .frame(height: markHeight)
                    .accessibilityLabel(record.fsAccessibilityLabel)
            }
        }
    }
}

struct ChartTypeBadge: View {
    let isDX: Bool
    var compact: Bool = false

    var body: some View {
        Image(isDX ? "ic_deluxe" : "ic_standard")
            .resizable()
            .scaledToFit()
            .frame(height: compact ? 13 : 15)
            .accessibilityLabel(isDX ? "DX 谱面" : "标准谱面")
    }
}

struct SongCoverBadgeView: View {
    enum Size {
        case compact // 56pt
        case list    // 64pt
        case hero    // 100pt

        var dimension: CGFloat {
            switch self {
            case .compact: return 56
            case .list: return 64
            case .hero: return 100
            }
        }

        var cornerRadius: CGFloat {
            switch self {
            case .compact: return AppTheme.Radius.tile
            case .list: return AppTheme.Radius.inset
            case .hero: return AppTheme.Radius.button
            }
        }

        var strokeWidth: CGFloat {
            switch self {
            case .compact, .list: return 2
            case .hero: return 3
            }
        }
    }

    let song: SongData?
    var size: Size = .list
    var borderColor: Color = AppTheme.accent
    var showCornerTypeBadge: Bool = false

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: size.cornerRadius, style: .continuous)
        ZStack(alignment: .bottomTrailing) {
            CoverImageView(song: song)
                .frame(width: size.dimension, height: size.dimension)
                .clipShape(shape)
                .overlay(
                    shape.strokeBorder(borderColor, lineWidth: size.strokeWidth)
                )

            if showCornerTypeBadge, let song {
                ChartTypeBadge(isDX: song.isDX, compact: size != .hero)
                    .padding(.horizontal, AppTheme.Space.xs)
                    .padding(.vertical, 2)
                    .background(Color.black.opacity(0.55), in: Capsule())
                    .padding(AppTheme.Space.xs)
            }
        }
    }
}

struct FilterCapsuleChip: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String
    let isSelected: Bool
    var selectedColor: Color = AppTheme.accent
    let action: () -> Void

    var body: some View {
        let unselectedFill: Color = colorScheme == .dark
            ? Color.white.opacity(0.07)
            : AppTheme.Palette.hairline.opacity(0.055)
        let strokeColor: Color = isSelected
            ? Color.white.opacity(0.28)
            : (colorScheme == .dark
                ? Color.white.opacity(0.14)
                : AppTheme.Palette.hairline.opacity(0.08))

        Button(action: action) {
            Text(title)
                .font(AppTheme.Typography.tag)
                .lineLimit(1)
                .padding(.horizontal, AppTheme.Space.m)
                .padding(.vertical, 7)
                .background(
                    isSelected ? AnyShapeStyle(selectedColor.gradient) : AnyShapeStyle(unselectedFill),
                    in: Capsule()
                )
                .overlay(Capsule().strokeBorder(strokeColor, lineWidth: 1))
                .foregroundStyle(isSelected ? .white : .primary)
                .contentShape(Capsule())
        }
        .buttonStyle(ScalePressButtonStyle())
        .accessibilityValue(isSelected ? "已选中" : "未选中")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
