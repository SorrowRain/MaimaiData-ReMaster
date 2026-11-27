import SwiftUI
import UIKit

@MainActor
enum KeyboardDismiss {
    static func hide() {
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
    }
}

/// Shared container chrome for `AppTextField` and `AppSecureField`.
/// Enforces full-box tap hit-testing (`minHeight = 50pt`) and focus border highlight.
private struct AppInputFieldBox<Content: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String?
    let isFocused: Bool
    let onTap: () -> Void
    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.input, style: .continuous)
        VStack(alignment: .leading, spacing: 5) {
            if let title {
                Text(title)
                    .font(AppTheme.Typography.tag)
                    .foregroundStyle(.secondary)
            }
            content()
                .padding(.horizontal, AppTheme.Space.m)
                .frame(minHeight: AppTheme.rowMinHeight)
                .background(
                    colorScheme == .dark
                        ? Color.white.opacity(0.06)
                        : AppTheme.Palette.hairline.opacity(0.045),
                    in: shape
                )
                .overlay(
                    shape.strokeBorder(
                        isFocused
                            ? AppTheme.accent.opacity(0.6)
                            : (colorScheme == .dark
                                ? Color.white.opacity(0.12)
                                : AppTheme.Palette.hairline.opacity(0.10)),
                        lineWidth: isFocused ? 1.2 : 1
                    )
                )
                .contentShape(shape)
                .onTapGesture(perform: onTap)
        }
    }
}

/// Standard single-line text input box.
/// Use this instead of raw `TextField` in forms/tools so edge taps and focus borders behave consistently.
struct AppTextField: View {
    var title: String? = nil
    let placeholder: String
    @Binding var text: String
    var keyboardType: UIKeyboardType = .default

    @FocusState private var isFocused: Bool

    var body: some View {
        AppInputFieldBox(title: title, isFocused: isFocused, onTap: { isFocused = true }) {
            HStack(spacing: 0) {
                TextField(placeholder, text: $text)
                    .keyboardType(keyboardType)
                    .font(AppTheme.Typography.input)
                    .focused($isFocused)
                Spacer(minLength: 0)
            }
        }
    }
}

/// Standard password input box with show/hide toggle.
struct AppSecureField: View {
    var title: String? = nil
    let placeholder: String
    @Binding var text: String

    @State private var showPassword = false
    @FocusState private var isFocused: Bool

    var body: some View {
        AppInputFieldBox(title: title, isFocused: isFocused, onTap: { isFocused = true }) {
            HStack(spacing: AppTheme.Space.s) {
                Group {
                    if showPassword {
                        TextField(placeholder, text: $text)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .textContentType(.password)
                            .focused($isFocused)
                    } else {
                        SecureField(placeholder, text: $text)
                            .textContentType(.password)
                            .focused($isFocused)
                    }
                }
                .font(AppTheme.Typography.input)

                Button {
                    showPassword.toggle()
                } label: {
                    Image(systemName: showPassword ? "eye.slash.fill" : "eye.fill")
                        .font(AppTheme.Typography.body)
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(showPassword ? "隐藏密码" : "显示密码")
            }
        }
    }
}

/// Single-line URL or token input field with an inline paste-from-clipboard icon button.
struct AppPasteField: View {
    var title: String? = nil
    let placeholder: String
    @Binding var text: String
    var keyboardType: UIKeyboardType = .URL
    var onSubmit: (() -> Void)? = nil

    @FocusState private var isFocused: Bool

    var body: some View {
        AppInputFieldBox(title: title, isFocused: isFocused, onTap: { isFocused = true }) {
            HStack(spacing: AppTheme.Space.s) {
                TextField(placeholder, text: $text)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .keyboardType(keyboardType)
                    .submitLabel(.done)
                    .font(AppTheme.Typography.input)
                    .focused($isFocused)
                    .onSubmit {
                        onSubmit?()
                    }

                Button {
                    if let pasted = UIPasteboard.general.string {
                        text = pasted.trimmingCharacters(in: .whitespacesAndNewlines)
                    }
                } label: {
                    Image(systemName: "doc.on.clipboard")
                        .font(AppTheme.Typography.body)
                        .foregroundStyle(.secondary)
                        .frame(width: 32, height: 32)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("粘贴剪贴板内容")
            }
        }
    }
}

/// Standard labeled discrete integer slider row (`label` + `AppValueBadge` + `Slider`).
/// Shared by `SearchFilterSheet` and `RatingCalculatorView`.
struct AppLabeledSliderRow: View {
    let label: String
    let valueText: String
    @Binding var value: Double
    let range: ClosedRange<Double>
    var step: Double = 1
    var isActive: Bool = true
    var enabled: Bool = true
    var accessibilityLabel: String? = nil

    var body: some View {
        HStack(spacing: AppTheme.itemSpacing) {
            Text(label)
                .font(AppTheme.Typography.inlineAction)
                .foregroundStyle(.secondary)
                .lineLimit(1)
                .fixedSize(horizontal: true, vertical: false)
                .frame(minWidth: 30, alignment: .leading)
            AppValueBadge(text: valueText, isActive: isActive && enabled)
            Slider(value: $value, in: range, step: step)
                .tint(AppTheme.accent)
                .disabled(!enabled)
                .opacity(enabled ? 1 : 0.45)
                .accessibilityLabel(accessibilityLabel ?? label)
                .accessibilityValue(valueText)
        }
    }
}
