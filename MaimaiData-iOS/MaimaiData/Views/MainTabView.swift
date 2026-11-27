import SwiftUI

struct MainTabView: View {
    @Environment(DataStore.self) private var store
    @State private var selectedTab: Int = 0
    @State private var updateSheetHeight: CGFloat = 320

    var body: some View {
        TabView(selection: $selectedTab) {
            SongListView()
                .background(
                    TabCrossfadeBridge(selectedTab: selectedTab)
                        .frame(width: 0, height: 0)
                )
                .tabItem {
                    Label("曲库", systemImage: "music.note.list")
                }
                .tag(0)

            RatingToolsView()
                .tabItem {
                    Label("Rating 与查分", systemImage: "chart.bar.doc.horizontal")
                }
                .tag(1)

            SettingsView()
                .tabItem {
                    Label("设置与数据", systemImage: "gearshape")
                }
                .tag(2)
        }
        .tint(AppTheme.accent)
        .sensoryFeedback(.selection, trigger: selectedTab)
        .sheet(
            item: Binding(
                get: { store.availableAppUpdate },
                set: { store.availableAppUpdate = $0 }
            )
        ) { release in
            AppUpdateSheet(
                release: release,
                onMeasuredHeight: { height in
                    if height > 0, abs(updateSheetHeight - height) > 1 {
                        updateSheetHeight = height
                    }
                },
                onIgnore: {
                    store.ignoreAppUpdate(version: release.version)
                },
                onDismiss: {
                    store.availableAppUpdate = nil
                }
            )
            .presentationDetents([.height(max(240, updateSheetHeight))])
            .presentationDragIndicator(.visible)
            .appSheetContainer()
        }
    }
}

private struct SheetHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct NotesHeightPreferenceKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = max(value, nextValue())
    }
}

private struct AppUpdateSheet: View {
    let release: AppReleaseInfo
    let onMeasuredHeight: (CGFloat) -> Void
    let onIgnore: () -> Void
    let onDismiss: () -> Void

    @State private var measuredNotesHeight: CGFloat = 0

    private var screenHeight: CGFloat {
        let scene = UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .first
        return scene?.screen.bounds.height ?? 844
    }

    private var maxNotesHeight: CGFloat {
        min(300, screenHeight * 0.42)
    }

    private enum NoteLine: Identifiable {
        case heading(id: Int, text: String)
        case bullet(id: Int, text: String)
        case text(id: Int, text: String)

        var id: Int {
            switch self {
            case let .heading(id, _), let .bullet(id, _), let .text(id, _):
                return id
            }
        }
    }

    private var parsedNotes: [NoteLine] {
        var output: [NoteLine] = []
        let lines = release.notes.components(separatedBy: .newlines)
        for (idx, rawLine) in lines.enumerated() {
            let trimmed = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !trimmed.isEmpty else { continue }
            let cleaned = trimmed
                .replacingOccurrences(of: "**", with: "")
                .replacingOccurrences(of: "`", with: "")
            if cleaned.hasPrefix("#") {
                let heading = cleaned
                    .drop(while: { $0 == "#" || $0 == " " })
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !heading.isEmpty {
                    output.append(.heading(id: idx, text: heading))
                }
            } else if cleaned.hasPrefix("- ") || cleaned.hasPrefix("* ") || cleaned.hasPrefix("• ") {
                let bulletText = String(cleaned.dropFirst(2))
                    .trimmingCharacters(in: .whitespacesAndNewlines)
                if !bulletText.isEmpty {
                    output.append(.bullet(id: idx, text: bulletText))
                }
            } else {
                output.append(.text(id: idx, text: cleaned))
            }
        }
        return output
    }

    var body: some View {
        let shouldScrollNotes = measuredNotesHeight > maxNotesHeight
        VStack(alignment: .leading, spacing: AppTheme.cardSpacing) {
            HStack(spacing: AppTheme.Space.s) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.title3)
                    .foregroundStyle(AppTheme.accentText(for: .sheet))

                Text("发现新版本")
                    .font(AppTheme.Typography.cardTitle)
                    .foregroundStyle(.primary)

                AppCapsuleLabel(text: "v\(release.version)", isNeutral: false, style: .count)

                Spacer(minLength: 0)
            }

            AppCard(padding: AppTheme.cardPadding) {
                Group {
                    if shouldScrollNotes {
                        ScrollView {
                            notesContent
                        }
                        .scrollIndicators(.visible)
                        .frame(height: maxNotesHeight)
                    } else {
                        notesContent
                    }
                }
            }

            Text("请前往 TestFlight 更新至最新版本")
                .font(AppTheme.Typography.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .center)

            VStack(spacing: AppTheme.Space.xs) {
                AppPrimaryButton(
                    title: "知道了",
                    action: onDismiss
                )

                Button(action: onIgnore) {
                    Text("忽略此版本")
                        .font(AppTheme.Typography.rowTitle)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, minHeight: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, AppTheme.pagePadding)
        .padding(.top, AppTheme.Space.xl)
        .padding(.bottom, AppTheme.Space.m)
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: SheetHeightPreferenceKey.self,
                    value: min(proxy.size.height, screenHeight * 0.85)
                )
            }
        }
        .onPreferenceChange(SheetHeightPreferenceKey.self) { height in
            onMeasuredHeight(height)
        }
    }

    @ViewBuilder
    private var notesContent: some View {
        Group {
            if parsedNotes.isEmpty {
                Text("已有新版本发布，建议更新以获得最新功能与体验优化。")
                    .font(AppTheme.Typography.body)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                    ForEach(Array(parsedNotes.enumerated()), id: \.element.id) { index, line in
                        switch line {
                        case let .heading(_, text):
                            Text(text)
                                .font(AppTheme.Typography.subheadlineBold)
                                .foregroundStyle(.primary)
                                .fixedSize(horizontal: false, vertical: true)
                                .padding(.top, index == 0 ? 0 : AppTheme.Space.xs)
                        case let .bullet(_, text):
                            HStack(alignment: .top, spacing: AppTheme.Space.s) {
                                Text("•")
                                    .font(AppTheme.Typography.body)
                                    .foregroundStyle(AppTheme.accentText(for: .sheet))
                                Text(text)
                                    .font(AppTheme.Typography.body)
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                        case let .text(_, text):
                            Text(text)
                                .font(AppTheme.Typography.body)
                                .foregroundStyle(.secondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
        }
        .background {
            GeometryReader { proxy in
                Color.clear.preference(
                    key: NotesHeightPreferenceKey.self,
                    value: proxy.size.height
                )
            }
        }
        .onPreferenceChange(NotesHeightPreferenceKey.self) { height in
            if abs(measuredNotesHeight - height) > 1 {
                measuredNotesHeight = height
            }
        }
    }
}
