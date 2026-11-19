import SwiftUI

struct VersionCheckView: View {
    @Environment(DataStore.self) private var store
    @State private var displayMode: CheckDisplayMode = .sss
    @State private var onlyUnfinished: Bool = false
    @State private var selectedDifficulty: DifficultyType = .master
    @State private var groupedSongs: [LevelGroup] = []

    private struct LevelGroup: Identifiable {
        var id: String { level }
        let level: String
        let songs: [SongData]
    }

    private var scopes: [PlateCheckScope] {
        store.activePlateScopes
    }

    private var activeScope: PlateCheckScope {
        let items = scopes
        guard !items.isEmpty else {
            return PlateCheckScope.fallback
        }
        let idx = min(max(0, store.activeVersionCheckIndex), items.count - 1)
        return items[idx]
    }

    private var availableDifficulties: [DifficultyType] {
        if activeScope.supportsRemaster {
            return [.basic, .advanced, .expert, .master, .remaster]
        }
        return [.basic, .advanced, .expert, .master]
    }

    private func rebuildGroups() {
        if !availableDifficulties.contains(selectedDifficulty) {
            selectedDifficulty = .master
        }
        let scope = activeScope
        let diff = selectedDifficulty
        let diffIdx = diff.rawValue

        let filtered = store.songs.filter { scope.contains($0, difficulty: diff) }
        let sorted = filtered.sorted { ($0.ds[safe: diffIdx] ?? 0) > ($1.ds[safe: diffIdx] ?? 0) }
        let dict = Dictionary(grouping: sorted) { $0.level[safe: diffIdx] ?? "?" }
        groupedSongs = dict.keys.sorted { MaimaiConstants.levelSortOrder($0) > MaimaiConstants.levelSortOrder($1) }
            .map { LevelGroup(level: $0, songs: dict[$0] ?? []) }
    }

    var body: some View {
        let diffIdx = selectedDifficulty.rawValue
        let diffTint = selectedDifficulty.color
        let items = scopes
        let clampedSelection = Binding<Int>(
            get: {
                guard !items.isEmpty else { return 0 }
                return min(max(0, store.activeVersionCheckIndex), items.count - 1)
            },
            set: { newValue in
                store.activeVersionCheckIndex = newValue
            }
        )

        let allSongs = groupedSongs.flatMap(\.songs)
        let matchedRecords = allSongs.compactMap { store.record(forSongId: $0.numericId, difficultyIndex: diffIdx) }
        let stats = ChecklistStats(records: matchedRecords, totalCount: allSongs.count)

        let visibleGroups: [LevelGroup] = onlyUnfinished
            ? groupedSongs.compactMap { group in
                let remaining = group.songs.filter { song in
                    !displayMode.isCompleted(store.record(forSongId: song.numericId, difficultyIndex: diffIdx))
                }
                return remaining.isEmpty ? nil : LevelGroup(level: group.level, songs: remaining)
            }
            : groupedSongs

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppCard(padding: AppTheme.cardPadding, tint: diffTint) {
                    HStack(spacing: AppTheme.Space.m) {
                        Image(activeScope.versionAsset)
                            .resizable()
                            .scaledToFit()
                            .frame(width: 92, height: 36, alignment: .center)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(activeScope.title)
                                .font(AppTheme.Typography.rowTitle)
                                .lineLimit(1)
                            Text("共 \(allSongs.count) 首歌曲")
                                .font(AppTheme.Typography.caption)
                                .foregroundStyle(.secondary)
                                .lineLimit(1)
                        }

                        Spacer(minLength: AppTheme.Space.xs)

                        Menu {
                            Picker("牌子", selection: clampedSelection) {
                                ForEach(Array(items.enumerated()), id: \.offset) { idx, item in
                                    Text(item.title).tag(idx)
                                }
                            }
                        } label: {
                            AppMenuCapsule(title: "切换牌子")
                        }
                    }
                }

                ChecklistStatsHeader(
                    stats: stats,
                    displayMode: $displayMode,
                    onlyUnfinished: $onlyUnfinished
                ) {
                    Menu {
                        Picker("难度", selection: $selectedDifficulty) {
                            ForEach(availableDifficulties, id: \.self) { diff in
                                Text(diff.shortName).tag(diff)
                            }
                        }
                    } label: {
                        AppMenuCapsule(title: selectedDifficulty.shortName, tint: diffTint)
                    }
                }

                if store.isSyncingSongs && store.songs.isEmpty {
                    ProgressView("正在同步\(store.serverRegion.shortName)曲库…")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                        .padding(.top, AppTheme.Space.xl)
                } else if store.songs.isEmpty {
                    ContentUnavailableView(
                        "暂无\(store.serverRegion.shortName)曲库数据",
                        systemImage: "arrow.triangle.2.circlepath",
                        description: Text("请前往「设置与数据 → 数据同步与存储」点击检查更新")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else if visibleGroups.isEmpty && !allSongs.isEmpty {
                    ContentUnavailableView(
                        "已全部达成 \(displayMode.title)",
                        systemImage: "checkmark.seal.fill",
                        description: Text("当前筛选下全部谱面均已达成该目标")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else {
                    ForEach(visibleGroups) { group in
                        AppSectionCard(title: "Level \(group.level)") {
                            AppCapsuleLabel(text: "\(group.songs.count) 首", tint: diffTint)
                        } content: {
                            ChecklistSongGrid {
                                ForEach(group.songs) { song in
                                    NavigationLink(value: SongDetailRoute(song: song, initialDifficulty: selectedDifficulty)) {
                                        ChecklistSongTile(
                                            song: song,
                                            record: store.record(forSongId: song.numericId, difficultyIndex: diffIdx),
                                            borderColor: diffTint,
                                            displayMode: displayMode,
                                            region: store.serverRegion
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .sensoryFeedback(.selection, trigger: store.activeVersionCheckIndex)
        .sensoryFeedback(.selection, trigger: selectedDifficulty)
        .navigationTitle("牌子进度表")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            rebuildGroups()
        }
        .onChange(of: store.activeVersionCheckIndex) { _, _ in rebuildGroups() }
        .onChange(of: selectedDifficulty) { _, _ in rebuildGroups() }
        .onChange(of: store.serverRegion) { _, _ in rebuildGroups() }
        .onChange(of: store.catalogRevision) { _, _ in rebuildGroups() }
    }
}
