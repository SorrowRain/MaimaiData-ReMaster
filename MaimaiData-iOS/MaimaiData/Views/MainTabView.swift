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
        .alert(
            store.availableAppUpdate.map { "发现新版本 v\($0.version)" } ?? "发现新版本",
            isPresented: Binding(
                get: { store.availableAppUpdate != nil },
                set: { if !$0 { store.availableAppUpdate = nil } }
            ),
            presenting: store.availableAppUpdate
        ) { release in
            if IOSInstallChannel.current == .testFlight,
               let tfURL = URL(string: "itms-beta://") {
                Button("打开 TestFlight") {
                    openURL(tfURL)
                    store.availableAppUpdate = nil
                }
                Button("忽略此版本") {
                    store.ignoreAppUpdate(version: release.version)
                }
                Button("稍后", role: .cancel) {
                    store.availableAppUpdate = nil
                }
            } else {
                if let url = URL(string: release.htmlURL) {
                    Button("前往下载") {
                        openURL(url)
                        store.availableAppUpdate = nil
                    }
                }
                Button("忽略此版本") {
                    store.ignoreAppUpdate(version: release.version)
                }
                Button("稍后", role: .cancel) {
                    store.availableAppUpdate = nil
                }
            }
        } message: { release in
            if release.notes.isEmpty {
                Text("已有新版本发布，TestFlight 用户可在 TestFlight 更新，自签用户可前往关于页版本发布处获取新包。")
            } else {
                Text(release.notes)
            }
        }
    }
}
