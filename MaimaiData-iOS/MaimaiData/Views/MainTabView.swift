import SwiftUI

struct MainTabView: View {
    @Environment(DataStore.self) private var store
    @Environment(\.openURL) private var openURL
    @State private var selectedTab: Int = 0

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
                onPrimaryAction: {
                    if IOSInstallChannel.current == .testFlight,
                       let tfURL = URL(string: "itms-beta://") {
                        openURL(tfURL)
                    } else if let url = URL(string: release.htmlURL) {
                        openURL(url)
                    }
                    store.availableAppUpdate = nil
                },
                onIgnore: {
                    store.ignoreAppUpdate(version: release.version)
                },
                onDismiss: {
                    store.availableAppUpdate = nil
                }
            )
            .presentationDetents([.medium, .large])
            .presentationDragIndicator(.visible)
        }
    }
}

private struct AppUpdateSheet: View {
    let release: AppReleaseInfo
    let onPrimaryAction: () -> Void
    let onIgnore: () -> Void
    let onDismiss: () -> Void

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

    private var primaryTitle: String {
        IOSInstallChannel.current == .testFlight ? "打开 TestFlight" : "前往下载"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: AppTheme.cardSpacing) {
            HStack(spacing: AppTheme.Space.s) {
                Image(systemName: "arrow.down.circle.fill")
                    .font(.title3)
                    .foregroundStyle(AppTheme.accent)

                Text("发现新版本")
                    .font(AppTheme.Typography.cardTitle)
                    .foregroundStyle(.primary)

                AppCapsuleLabel(text: "v\(release.version)", style: .count)

                Spacer(minLength: 0)

                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.title3)
                        .foregroundStyle(.tertiary)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("关闭")
            }

            ScrollView {
                AppCard(padding: AppTheme.cardPadding) {
                    if parsedNotes.isEmpty {
                        Text("已有新版本发布，建议更新以获得最新功能与体验优化。")
                            .font(AppTheme.Typography.body)
                            .foregroundStyle(.secondary)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    } else {
                        VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                            ForEach(Array(parsedNotes.enumerated()), id: \.element.id) { index, line in
                                switch line {
                                case let .heading(_, text):
                                    Text(text)
                                        .font(AppTheme.Typography.subheadlineBold)
                                        .foregroundStyle(.primary)
                                        .padding(.top, index == 0 ? 0 : AppTheme.Space.xs)
                                case let .bullet(_, text):
                                    HStack(alignment: .top, spacing: AppTheme.Space.s) {
                                        Text("•")
                                            .font(AppTheme.Typography.body)
                                            .foregroundStyle(AppTheme.accentText)
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
            }
            .scrollIndicators(.hidden)

            VStack(spacing: AppTheme.Space.s) {
                AppPrimaryButton(
                    title: primaryTitle,
                    icon: "arrow.down.circle.fill",
                    action: onPrimaryAction
                )

                HStack(spacing: AppTheme.Space.l) {
                    Button("忽略此版本", action: onIgnore)
                        .font(AppTheme.Typography.rowTitle)
                        .foregroundStyle(.secondary)

                    Spacer()

                    Button("稍后提醒", action: onDismiss)
                        .font(AppTheme.Typography.rowTitle)
                        .foregroundStyle(.secondary)
                }
                .padding(.horizontal, AppTheme.Space.xs)
            }
        }
        .padding(.horizontal, AppTheme.pagePadding)
        .padding(.top, AppTheme.Space.l)
        .padding(.bottom, AppTheme.Space.m)
        .appPageBackground()
    }
}
