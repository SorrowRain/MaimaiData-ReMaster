import Observation
import SwiftUI

extension View {
    /// The single adaptive glass helper: Liquid Glass on iOS 26+, material (plus tint) before that.
    /// This is the only place in the app that calls `glassEffect`.
    @ViewBuilder
    func appGlass<S: Shape>(in shape: S, tint: Color? = nil, interactive: Bool = true) -> some View {
        if #available(iOS 26.0, *) {
            let base: Glass = interactive ? .regular.interactive() : .regular
            glassEffect(tint.map { interactive ? Glass.regular.tint($0).interactive() : Glass.regular.tint($0) } ?? base, in: shape)
        } else {
            // Layer order matters: backgrounds applied later sit further behind. The tint must be
            // closest to the content so the material does not wash out the brand color.
            background {
                if let tint { shape.fill(tint.opacity(0.85)) }
            }
            .background(.ultraThinMaterial, in: shape)
            .overlay {
                // Untinted fallback (secondary/circle buttons) needs a hairline edge on light pages.
                if tint == nil {
                    shape.stroke(Color.primary.opacity(0.08), lineWidth: 1)
                }
            }
        }
    }

    /// Glass capsule container (search bar).
    func appGlassCapsule() -> some View {
        appGlass(in: Capsule())
    }

    /// Tap on empty area dismisses the keyboard.
    func dismissKeyboardOnTap() -> some View {
        contentShape(Rectangle())
            .onTapGesture { KeyboardDismiss.hide() }
    }

    /// Error alert driven by an optional message; clears the message on dismiss.
    func errorAlert(_ title: String, message: Binding<String?>) -> some View {
        alert(
            title,
            isPresented: Binding(
                get: { message.wrappedValue != nil },
                set: { if !$0 { message.wrappedValue = nil } }
            )
        ) {
            Button("确定") { message.wrappedValue = nil }
        } message: {
            Text(message.wrappedValue ?? "")
        }
    }

    /// Bottom glass toast overlay bound to a race-free `ToastState`.
    func statusToast(_ toast: ToastState) -> some View {
        overlay(alignment: .bottom) {
            if let message = toast.text {
                StatusToast(text: message, isError: toast.isError)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
    }
}

/// Race-free toast state controller: showing a new toast immediately cancels any prior dismiss timer
/// so rapid consecutive actions never cut short the latest toast.
@MainActor
@Observable
final class ToastState {
    private(set) var text: String?
    private(set) var isError: Bool = false
    @ObservationIgnored private var dismissTask: Task<Void, Never>?

    func show(_ message: String, isError: Bool = false, duration: Double = 1.8) {
        dismissTask?.cancel()
        withAnimation(.snappy(duration: 0.22)) {
            self.isError = isError
            self.text = message
        }
        dismissTask = Task { [weak self] in
            try? await Task.sleep(for: .seconds(duration))
            guard !Task.isCancelled else { return }
            withAnimation(.snappy(duration: 0.22)) {
                self?.text = nil
            }
        }
    }
}

struct StatusToast: View {
    let text: String
    var isError: Bool = false

    var body: some View {
        HStack(spacing: AppTheme.Space.s) {
            Image(systemName: isError ? "exclamationmark.circle.fill" : "checkmark.circle.fill")
                .foregroundStyle(isError ? AppTheme.warning : AppTheme.accentText)
            Text(text)
                .font(AppTheme.Typography.inlineAction)
                .multilineTextAlignment(.center)
                .transaction { $0.animation = nil }
        }
        .padding(.horizontal, AppTheme.Space.l)
        .padding(.vertical, AppTheme.itemSpacing)
        .appGlass(in: Capsule(), interactive: false)
        .padding(.bottom, AppTheme.Space.xl)
        .accessibilityAddTraits(.isStaticText)
    }
}

struct SyncStatusBanner: View {
    @Bindable var store: DataStore

    var body: some View {
        let isLoading = store.isLoadingLocalData || store.isSyncingSongs
        if isLoading || store.syncNotice != nil {
            AppCard(padding: AppTheme.compactCardPadding) {
                VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                    HStack(alignment: .center, spacing: AppTheme.itemSpacing) {
                        if isLoading {
                            ProgressView().controlSize(.small)
                            Text(store.isLoadingLocalData ? "正在读取本地曲库…" : "正在同步曲库与规则…")
                                .font(AppTheme.Typography.tag)
                                .foregroundStyle(.secondary)
                        } else if let notice = store.syncNotice {
                            Image(systemName: "wifi.exclamationmark")
                                .foregroundStyle(AppTheme.warning)
                                .accessibilityHidden(true)
                            Text(notice.message)
                                .font(AppTheme.Typography.caption)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        Spacer(minLength: 0)
                    }
                    if !isLoading, let notice = store.syncNotice {
                        HStack(spacing: AppTheme.itemSpacing) {
                            AppInlineButton(
                                title: "重试",
                                icon: "arrow.clockwise",
                                isDisabled: notice.kind == .songs ? store.isSyncingSongs : store.isSyncingStats
                            ) {
                                store.syncNotice = nil
                                switch notice.kind {
                                case .songs:
                                    Task { await store.syncAllCatalogsAndRules(force: true) }
                                case .stats:
                                    Task { await store.refreshChartStatsIfStale(force: true) }
                                }
                            }

                            AppInlineButton(title: "关闭", tint: .secondary) {
                                store.syncNotice = nil
                            }
                        }
                    }
                }
            }
            .accessibilityElement(children: .contain)
        }
    }
}

/// Centered loading indicator paired with a secondary caption (mirrors Android `AppLoadingState`).
struct AppLoadingState: View {
    let message: String
    var topPadding: CGFloat = AppTheme.Space.xl

    var body: some View {
        ProgressView(message)
            .font(AppTheme.Typography.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity)
            .padding(.top, topPadding)
    }
}
