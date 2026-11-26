import SwiftUI

@main
struct MaimaiDataApp: App {
    @Environment(\.scenePhase) private var scenePhase
    @State private var store = DataStore()

    var body: some Scene {
        WindowGroup {
            MainTabView()
                .environment(store)
                .task {
                    // Unit tests load the app as TEST_HOST; do not hit the network or
                    // replace fixtures while the test runner is attached.
                    guard ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil else { return }
                    await store.bootstrap()
                }
                .onChange(of: scenePhase) { _, newPhase in
                    guard newPhase == .active,
                          ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] == nil else { return }
                    Task { await store.checkAppUpdateIfNeeded(manual: false) }
                }
        }
    }
}
