import SwiftUI

/// Primary action button with semantic layout presets.
struct AppPrimaryButton: View {
    enum Style {
        /// Standard full-width primary button (`minHeight = 46pt`, button radius).
        case standard
        /// Compact button placed side-by-side with `AppTextField` (`minHeight = 50pt`, input radius).
        case inputCompanion
    }

    let title: String
    var icon: String? = nil
    var tint: Color = AppTheme.accent
    var style: Style = .standard
    var isLoading = false
    var isDisabled = false
    let action: () -> Void

    var body: some View {
        let isInputCompanion = style == .inputCompanion
        let radius = isInputCompanion ? AppTheme.Radius.input : AppTheme.Radius.button
        let minHeight = isInputCompanion ? AppTheme.rowMinHeight : 46
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)

        Button(action: action) {
            HStack(spacing: AppTheme.Space.s) {
                if isLoading {
                    ProgressView()
                        .controlSize(.small)
                        .tint(.white)
                } else if let icon {
                    Image(systemName: icon)
                        .font(AppTheme.Typography.songTitle)
                }
                Text(title)
                    .font(AppTheme.Typography.songTitle)
                    .lineLimit(1)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 18)
            .frame(maxWidth: isInputCompanion ? nil : .infinity, minHeight: minHeight)
            .contentShape(shape)
        }
        .buttonStyle(.plain)
        .background(tint, in: shape)
        .opacity(isLoading || isDisabled ? 0.5 : 1)
        .disabled(isLoading || isDisabled)
    }
}

/// Compact inline action button for rows and card headers (12pt, smaller than 15pt row titles).
struct AppInlineButton: View {
    let title: String
    var icon: String? = nil
    var tint: Color = AppTheme.accent
    var isLoading: Bool = false
    var isDisabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: AppTheme.Space.xs) {
                if isLoading {
                    ProgressView()
                        .controlSize(.mini)
                } else if let icon {
                    Image(systemName: icon)
                        .font(AppTheme.Typography.caption2Bold)
                }
                Text(title)
                    .font(AppTheme.Typography.inlineAction)
            }
            .modifier(TintedActionCapsuleChrome(tint: tint))
        }
        .buttonStyle(.plain)
        .opacity(isDisabled ? 0.45 : 1)
        .disabled(isLoading || isDisabled)
    }
}

/// Secondary glass button (e.g. "重置选项").
struct AppSecondaryButton: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String
    var tint: Color = AppTheme.accent
    var isDisabled: Bool = false
    let action: () -> Void

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.button, style: .continuous)
        let fgColor = colorScheme == .dark
            ? tint.brightened(toAtLeast: 0.90, maxSaturation: 0.65)
            : tint
        Button(action: action) {
            Text(title)
                .font(AppTheme.Typography.songTitle)
                .foregroundStyle(fgColor)
                .lineLimit(1)
                .frame(maxWidth: .infinity, minHeight: 46)
                .contentShape(shape)
        }
        .buttonStyle(.plain)
        .background(tint.opacity(colorScheme == .dark ? 0.14 : 0.08), in: shape)
        .overlay(shape.strokeBorder(tint.opacity(colorScheme == .dark ? 0.32 : 0.22), lineWidth: 1))
        .opacity(isDisabled ? 0.45 : 1)
        .disabled(isDisabled)
    }
}

/// Label for a Menu rendered as a tinted capsule (sort menu, version switcher).
struct AppMenuCapsule: View {
    let title: String
    var tint: Color = AppTheme.accent
    var isDisabled: Bool = false

    var body: some View {
        HStack(spacing: AppTheme.Space.xs) {
            Text(title)
                .font(AppTheme.Typography.inlineAction)
            Image(systemName: "chevron.up.chevron.down")
                .font(AppTheme.Typography.caption2Bold)
        }
        .modifier(TintedActionCapsuleChrome(tint: tint))
        .opacity(isDisabled ? 0.45 : 1)
    }
}

/// Shared tinted capsule surface used by `AppInlineButton` and `AppMenuCapsule`.
private struct TintedActionCapsuleChrome: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let tint: Color

    func body(content: Content) -> some View {
        let isDark = colorScheme == .dark
        let isNeutral = tint == .secondary
        content
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .foregroundStyle(
                isNeutral
                    ? Color.secondary
                    : (isDark ? tint.brightened(toAtLeast: 0.92, maxSaturation: 0.65) : tint)
            )
            .padding(.horizontal, 11)
            .padding(.vertical, 6)
            .background(tint.opacity(isDark && !isNeutral ? 0.18 : 0.11), in: Capsule())
            .overlay(Capsule().strokeBorder(tint.opacity(isDark && !isNeutral ? 0.35 : 0.22), lineWidth: 0.8))
            .contentShape(Capsule())
    }
}

/// Circular glass icon button. An accessibility label is required.
struct AppCircleIconButton: View {
    let systemImage: String
    let accessibilityLabel: String
    var isDisabled = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: systemImage)
                .font(AppTheme.Typography.subheadlineBold)
                .frame(width: 34, height: 34)
        }
        .buttonStyle(.plain)
        .appGlass(in: Circle())
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.4 : 1)
        .accessibilityLabel(accessibilityLabel)
    }
}
