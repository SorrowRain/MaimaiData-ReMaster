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
    var isNeutral: Bool = false
    var isSolidActive: Bool = false
    var isLoading: Bool = false
    var isDisabled: Bool = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: AppTheme.Space.xs) {
                if isLoading {
                    ProgressView()
                        .controlSize(.mini)
                        .tint(isSolidActive ? .white : nil)
                } else if let icon {
                    Image(systemName: icon)
                        .font(AppTheme.Typography.caption2Bold)
                }
                Text(title)
                    .font(AppTheme.Typography.inlineAction)
            }
            .modifier(TintedActionCapsuleChrome(tint: tint, isNeutral: isNeutral, isSolidActive: isSolidActive))
        }
        .buttonStyle(ScalePressButtonStyle())
        .opacity(isDisabled ? 0.45 : 1)
        .disabled(isLoading || isDisabled)
    }
}

struct ScalePressButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .opacity(configuration.isPressed ? 0.92 : 1.0)
            .animation(.easeOut(duration: 0.14), value: configuration.isPressed)
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
    var isNeutral: Bool = false
    var isSolidActive: Bool = false

    func body(content: Content) -> some View {
        let isDark = colorScheme == .dark
        let useNeutral = !isSolidActive && (isNeutral || tint == .secondary)
        let fg: Color = if isSolidActive {
            .white
        } else if useNeutral {
            .secondary
        } else if isDark {
            tint.brightened(toAtLeast: 0.92, maxSaturation: 0.65)
        } else {
            tint
        }
        let bg: Color = if isSolidActive {
            tint
        } else if useNeutral {
            Color.primary.opacity(isDark ? 0.10 : 0.06)
        } else {
            tint.opacity(isDark ? 0.18 : 0.11)
        }
        let strokeColor: Color = if isSolidActive {
            Color.clear
        } else if useNeutral {
            Color.primary.opacity(isDark ? 0.18 : 0.12)
        } else {
            tint.opacity(isDark ? 0.35 : 0.22)
        }

        content
            .lineLimit(1)
            .fixedSize(horizontal: true, vertical: false)
            .foregroundStyle(fg)
            .padding(.horizontal, 11)
            .padding(.vertical, 6)
            .background(bg, in: Capsule())
            .overlay(Capsule().strokeBorder(strokeColor, lineWidth: isSolidActive ? 0 : 0.8))
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
                .appGlass(in: Circle())
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(ScalePressButtonStyle())
        .disabled(isDisabled)
        .opacity(isDisabled ? 0.4 : 1)
        .accessibilityLabel(accessibilityLabel)
    }
}
