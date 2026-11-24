import SwiftUI

struct FlowLayout: Layout {
    var spacing: CGFloat = AppTheme.Space.s
    var lineSpacing: CGFloat = AppTheme.Space.s

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var currentX: CGFloat = 0
        var currentY: CGFloat = 0
        var lineHeight: CGFloat = 0
        var maxLineWidth: CGFloat = 0

        for subview in subviews {
            let subProposal = maxWidth.isFinite ? ProposedViewSize(width: maxWidth, height: nil) : .unspecified
            let size = subview.sizeThatFits(subProposal)
            if currentX + size.width > maxWidth && currentX > 0 {
                maxLineWidth = max(maxLineWidth, currentX - spacing)
                currentX = 0
                currentY += lineHeight + lineSpacing
                lineHeight = 0
            }
            currentX += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
        maxLineWidth = max(maxLineWidth, max(0, currentX - spacing))
        return CGSize(width: maxWidth.isFinite ? maxWidth : maxLineWidth, height: currentY + lineHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var currentX = bounds.minX
        var currentY = bounds.minY
        var lineHeight: CGFloat = 0

        for subview in subviews {
            let size = subview.sizeThatFits(ProposedViewSize(width: bounds.width, height: nil))
            if currentX + size.width > bounds.maxX && currentX > bounds.minX {
                currentX = bounds.minX
                currentY += lineHeight + lineSpacing
                lineHeight = 0
            }
            subview.place(at: CGPoint(x: currentX, y: currentY), proposal: ProposedViewSize(size))
            currentX += size.width + spacing
            lineHeight = max(lineHeight, size.height)
        }
    }
}

struct AppCard<Content: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    var padding: CGFloat = AppTheme.cardPadding
    var tint: Color? = nil
    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.card, style: .continuous)
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(
                colorScheme == .dark
                    ? AppTheme.Palette.surfaceDark
                    : Color.white.opacity(0.92),
                in: shape
            )
            .overlay {
                if let tint {
                    shape.fill(
                        LinearGradient(
                            colors: [tint.opacity(colorScheme == .dark ? 0.10 : 0.055), .clear],
                            startPoint: .topLeading,
                            endPoint: .bottomTrailing
                        )
                    )
                    .allowsHitTesting(false)
                }
            }
            .overlay {
                shape.strokeBorder(
                    colorScheme == .dark
                        ? Color.white.opacity(0.09)
                        : AppTheme.Palette.hairline.opacity(0.08),
                    lineWidth: 1
                )
            }
    }
}

struct AppSectionHeader<Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    @ViewBuilder let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.title = title
        self.subtitle = subtitle
        self.trailing = trailing
    }

    var body: some View {
        HStack(alignment: .center, spacing: AppTheme.Space.s) {
            Capsule()
                .fill(AppTheme.accent.gradient)
                .frame(width: 4, height: 16)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AppTheme.Typography.cardTitle)
                    .foregroundStyle(.primary)
                if let subtitle {
                    Text(subtitle)
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                }
            }

            Spacer(minLength: AppTheme.Space.s)
            trailing()
        }
    }
}

struct AppSectionCard<Trailing: View, Content: View>: View {
    let title: String
    var subtitle: String? = nil
    var padding: CGFloat = AppTheme.cardPadding
    @ViewBuilder let trailing: () -> Trailing
    @ViewBuilder let content: () -> Content

    init(
        title: String,
        subtitle: String? = nil,
        padding: CGFloat = AppTheme.cardPadding,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() },
        @ViewBuilder content: @escaping () -> Content
    ) {
        self.title = title
        self.subtitle = subtitle
        self.padding = padding
        self.trailing = trailing
        self.content = content
    }

    init(
        title: String,
        subtitle: String? = nil,
        padding: CGFloat = AppTheme.cardPadding,
        @ViewBuilder content: @escaping () -> Content
    ) where Trailing == EmptyView {
        self.init(title: title, subtitle: subtitle, padding: padding, trailing: { EmptyView() }, content: content)
    }

    var body: some View {
        AppCard(padding: padding) {
            VStack(alignment: .leading, spacing: AppTheme.Space.m) {
                AppSectionHeader(title: title, subtitle: subtitle) {
                    trailing()
                }

                content()
            }
        }
    }
}

struct AppInsetGroup<Content: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    var padding: CGFloat = 0
    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.inset, style: .continuous)
        content()
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipShape(shape)
            .background(
                colorScheme == .dark
                    ? Color.white.opacity(0.045)
                    : AppTheme.Palette.hairline.opacity(0.04),
                in: shape
            )
            .overlay(
                shape.strokeBorder(
                    colorScheme == .dark
                        ? Color.white.opacity(0.08)
                        : AppTheme.Palette.hairline.opacity(0.065),
                    lineWidth: 1
                )
            )
    }
}

/// Full-width inset hairline divider with zero leading indentation so highlighted rows never leave asymmetric gaps.
struct AppInsetDivider: View {
    var body: some View {
        Divider()
            .frame(maxWidth: .infinity)
    }
}

/// Standard row inside `AppInsetGroup` (leading optional icon + title/subtitle + trailing control/chevron).
struct AppInsetRow<Leading: View, Trailing: View>: View {
    let title: String
    var subtitle: String? = nil
    var monospacedSubtitle: Bool = false
    var showsChevron: Bool = false
    @ViewBuilder let leading: () -> Leading
    @ViewBuilder let trailing: () -> Trailing

    init(
        title: String,
        subtitle: String? = nil,
        monospacedSubtitle: Bool = false,
        showsChevron: Bool = false,
        @ViewBuilder leading: @escaping () -> Leading,
        @ViewBuilder trailing: @escaping () -> Trailing
    ) {
        self.title = title
        self.subtitle = subtitle
        self.monospacedSubtitle = monospacedSubtitle
        self.showsChevron = showsChevron
        self.leading = leading
        self.trailing = trailing
    }

    init(
        title: String,
        subtitle: String? = nil,
        monospacedSubtitle: Bool = false,
        showsChevron: Bool = false,
        @ViewBuilder trailing: @escaping () -> Trailing = { EmptyView() }
    ) where Leading == EmptyView {
        self.init(
            title: title,
            subtitle: subtitle,
            monospacedSubtitle: monospacedSubtitle,
            showsChevron: showsChevron,
            leading: { EmptyView() },
            trailing: trailing
        )
    }

    init(
        icon: String,
        iconColor: Color,
        title: String,
        subtitle: String? = nil,
        showsChevron: Bool = true
    ) where Leading == AnyView, Trailing == EmptyView {
        self.init(
            title: title,
            subtitle: subtitle,
            showsChevron: showsChevron,
            leading: {
                AnyView(
                    Image(systemName: icon)
                        .font(AppTheme.Typography.subheadlineBold)
                        .foregroundStyle(.white)
                        .frame(width: 34, height: 34)
                        .background(
                            iconColor.gradient,
                            in: RoundedRectangle(cornerRadius: AppTheme.Radius.tile, style: .continuous)
                        )
                )
            },
            trailing: { EmptyView() }
        )
    }

    var body: some View {
        HStack(spacing: AppTheme.Space.m) {
            leading()

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(AppTheme.Typography.rowTitle)
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                if let subtitle {
                    Text(subtitle)
                        .font(monospacedSubtitle ? AppTheme.Typography.captionMono : AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer(minLength: AppTheme.Space.s)
            trailing()

            if showsChevron {
                Image(systemName: "chevron.right")
                    .font(AppTheme.Typography.captionBold)
                    .foregroundStyle(.tertiary)
            }
        }
        .padding(.horizontal, AppTheme.Space.m)
        .padding(.vertical, AppTheme.itemSpacing)
        .frame(minHeight: AppTheme.rowMinHeight)
        .contentShape(Rectangle())
    }
}

/// Standard toggle switch row inside `AppInsetGroup`.
struct AppInsetToggleRow: View {
    let title: String
    @Binding var isOn: Bool

    var body: some View {
        Toggle(title, isOn: $isOn)
            .font(AppTheme.Typography.rowTitle)
            .tint(AppTheme.accent)
            .padding(.horizontal, AppTheme.Space.m)
            .frame(minHeight: AppTheme.rowMinHeight)
    }
}

/// Shared full-screen photo preview scaffold used by `CoverZoomView` and `B50PreviewSheet`.
/// Owns the black backdrop, dark navigation bar, pinch-to-zoom scroll view, share sheet, and save toast.
struct PhotoPreviewScaffold<Placeholder: View>: View {
    @Environment(\.dismiss) private var dismiss

    let title: String
    let image: UIImage?
    var imagePadding: CGFloat = AppTheme.Space.m
    var accessibilityLabel: String?
    var shareAccessibilityLabel: String = "分享图片"
    var saveAccessibilityLabel: String?

    @ViewBuilder let placeholder: () -> Placeholder

    @State private var saveToast = ToastState()
    @State private var showShare = false
    @State private var isSaving = false
    @State private var didSaveTrigger = 0

    init(
        title: String,
        image: UIImage?,
        imagePadding: CGFloat = AppTheme.Space.m,
        accessibilityLabel: String? = nil,
        shareAccessibilityLabel: String = "分享图片",
        saveAccessibilityLabel: String? = nil,
        @ViewBuilder placeholder: @escaping () -> Placeholder
    ) {
        self.title = title
        self.image = image
        self.imagePadding = imagePadding
        self.accessibilityLabel = accessibilityLabel
        self.shareAccessibilityLabel = shareAccessibilityLabel
        self.saveAccessibilityLabel = saveAccessibilityLabel
        self.placeholder = placeholder
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                if let image {
                    ZoomableImageScrollView(image: image)
                        .padding(imagePadding)
                        .accessibilityLabel(accessibilityLabel ?? title)
                } else {
                    placeholder()
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .foregroundStyle(.white)
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    if let image {
                        Button {
                            showShare = true
                        } label: {
                            Image(systemName: "square.and.arrow.up")
                                .foregroundStyle(.white)
                        }
                        .accessibilityLabel(shareAccessibilityLabel)

                        Button("保存") {
                            guard !isSaving else { return }
                            Task { await saveToPhotos(image) }
                        }
                        .fontWeight(.semibold)
                        .foregroundStyle(.white)
                        .disabled(isSaving)
                        .accessibilityLabel(saveAccessibilityLabel ?? "保存")
                    }
                }
            }
            .statusToast(saveToast)
            .sensoryFeedback(.success, trigger: didSaveTrigger)
            .sheet(isPresented: $showShare) {
                if let image {
                    ShareSheet(items: [image])
                }
            }
        }
    }

    private func saveToPhotos(_ image: UIImage) async {
        isSaving = true
        defer { isSaving = false }
        do {
            try await PhotoLibrarySaver.save(image)
            didSaveTrigger += 1
            saveToast.show("已保存到系统相册", duration: 2.2)
        } catch {
            saveToast.show(error.localizedDescription, isError: true, duration: 2.5)
        }
    }
}

private struct InsetRowHighlightModifier: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    let isHighlighted: Bool
    let tint: Color

    func body(content: Content) -> some View {
        content.background(
            isHighlighted
                ? tint.opacity(colorScheme == .dark ? 0.16 : 0.08)
                : Color.clear,
            in: Rectangle()
        )
    }
}

extension View {
    /// Edge-to-edge rectangular row highlight inside `AppInsetGroup`.
    /// Passing `in: Rectangle()` explicitly prevents SwiftUI on iOS 26 from inheriting the enclosing rounded container shape,
    /// while `AppInsetGroup.clipShape` cleanly rounds only the outer corners of the first/last rows.
    func appInsetRowHighlight(_ isHighlighted: Bool, tint: Color = AppTheme.accent) -> some View {
        modifier(InsetRowHighlightModifier(isHighlighted: isHighlighted, tint: tint))
    }
}

/// Single-line score profile row content (provider tag on the left + player ID + trailing selection mark).
struct ScoreProfileRowLabel: View {
    let profile: ScoreProfile
    let isSelected: Bool

    var body: some View {
        let providerTint = profile.provider == .lxns ? AppTheme.Palette.ratingPurple : AppTheme.accent

        HStack(spacing: AppTheme.Space.s) {
            AppCapsuleLabel(
                text: profile.provider.shortName,
                tint: providerTint,
                style: .compact
            )

            Text(profile.displayTitle)
                .font(AppTheme.Typography.rowTitle)
                .foregroundStyle(isSelected ? AppTheme.accentText : .primary)
                .lineLimit(1)
                .transaction { $0.animation = nil }

            Spacer(minLength: AppTheme.Space.s)

            if isSelected {
                Image(systemName: "checkmark")
                    .font(AppTheme.Typography.captionBold)
                    .foregroundStyle(AppTheme.accentText)
                    .accessibilityLabel("当前选中")
            }
        }
    }
}
