import SwiftUI

struct LevelCheckView: View {
    @Environment(DataStore.self) private var store
    @State private var displayMode: CheckDisplayMode = .sss
    @State private var onlyUnfinished: Bool = false
    @State private var dsGroups: [DSGroup] = []
    @State private var totalChartCount: Int = 0

    private var levels: [String] {
        Array(MaimaiConstants.allLevels.dropFirst()) // Drop "ALL"
    }

    private var currentLevelIndex: Int {
        min(max(0, store.lastLevelCheckIndex), levels.count - 1)
    }

    private var selectedLevel: String {
        levels[currentLevelIndex]
    }

    private struct ChartItem: Identifiable {
        var id: String { "\(song.id)_\(chart.index)" }
        let song: SongData
        let chart: ResolvedChart
    }

    private struct DSGroup: Identifiable {
        var id: Int { Int((ds * 10.0).rounded()) }
        let ds: Double
        let items: [ChartItem]
    }

    private func rebuildGroups() {
        let level = selectedLevel
        let items = store.songs
            .filter { !$0.isUtage && $0.isDisabled != true }
            .flatMap { song in
                song.resolvedCharts
                    .filter { $0.level == level }
                    .map { ChartItem(song: song, chart: $0) }
            }
            .sorted { $0.chart.ds > $1.chart.ds }

        let grouped = Dictionary(grouping: items) { Int(($0.chart.ds * 10.0).rounded()) }
        dsGroups = grouped.keys.sorted(by: >).map { tenths in
            DSGroup(ds: Double(tenths) / 10.0, items: grouped[tenths] ?? [])
        }
        totalChartCount = items.count
    }

    var body: some View {
        @Bindable var storeBindable = store

        let matchedRecords = dsGroups.flatMap(\.items).compactMap {
            store.record(forSongId: $0.song.numericId, difficultyIndex: $0.chart.index)
        }
        let stats = ChecklistStats(records: matchedRecords, totalCount: totalChartCount)

        let visibleGroups: [DSGroup] = onlyUnfinished
            ? dsGroups.compactMap { group in
                let remaining = group.items.filter { item in
                    !displayMode.isCompleted(
                        store.record(forSongId: item.song.numericId, difficultyIndex: item.chart.index)
                    )
                }
                return remaining.isEmpty ? nil : DSGroup(ds: group.ds, items: remaining)
            }
            : dsGroups

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppCard(padding: AppTheme.cardPadding, tint: AppTheme.Palette.progressAccent) {
                    VStack(spacing: AppTheme.Space.m) {
                        HStack {
                            AppCircleIconButton(
                                systemImage: "chevron.left",
                                accessibilityLabel: "上一个等级",
                                isDisabled: currentLevelIndex == 0
                            ) {
                                if currentLevelIndex > 0 {
                                    store.lastLevelCheckIndex = currentLevelIndex - 1
                                }
                            }

                            Spacer()
                            VStack(spacing: 2) {
                                Text("LEVEL \(selectedLevel)")
                                    .font(AppTheme.Typography.sectionMetric)
                                    .contentTransition(.numericText())
                                Text("共 \(totalChartCount) 张谱面")
                                    .font(AppTheme.Typography.caption)
                                    .foregroundStyle(.secondary)
                                    .contentTransition(.numericText())
                            }
                            Spacer()

                            AppCircleIconButton(
                                systemImage: "chevron.right",
                                accessibilityLabel: "下一个等级",
                                isDisabled: currentLevelIndex == levels.count - 1
                            ) {
                                if currentLevelIndex < levels.count - 1 {
                                    store.lastLevelCheckIndex = currentLevelIndex + 1
                                }
                            }
                        }

                        Slider(
                            value: Binding(
                                get: { Double(currentLevelIndex) },
                                set: { storeBindable.lastLevelCheckIndex = Int($0.rounded()) }
                            ),
                            in: 0...Double(levels.count - 1),
                            step: 1
                        )
                        .tint(AppTheme.accent)
                    }
                }

                ChecklistStatsHeader(
                    stats: stats,
                    displayMode: $displayMode,
                    onlyUnfinished: $onlyUnfinished
                )

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
                } else if visibleGroups.isEmpty && totalChartCount > 0 {
                    ContentUnavailableView(
                        "已全部达成 \(displayMode.title)",
                        systemImage: "checkmark.seal.fill",
                        description: Text("本等级全部谱面均已达成该目标")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else {
                    ForEach(visibleGroups) { group in
                        AppSectionCard(title: String(format: "定数 %.1f", group.ds)) {
                            AppCapsuleLabel(text: "\(group.items.count) 首")
                        } content: {
                            ChecklistSongGrid {
                                ForEach(group.items) { item in
                                    NavigationLink(
                                        value: SongDetailRoute(song: item.song, initialDifficulty: item.chart.difficulty)
                                    ) {
                                        ChecklistSongTile(
                                            song: item.song,
                                            record: store.record(forSongId: item.song.numericId, difficultyIndex: item.chart.index),
                                            borderColor: item.chart.difficulty.color,
                                            displayMode: displayMode,
                                            showTypeBadge: true,
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
        .sensoryFeedback(.selection, trigger: currentLevelIndex)
        .navigationTitle("等级进度表")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            rebuildGroups()
        }
        .onChange(of: store.lastLevelCheckIndex) { _, _ in rebuildGroups() }
        .onChange(of: store.serverRegion) { _, _ in rebuildGroups() }
        .onChange(of: store.catalogRevision) { _, _ in rebuildGroups() }
    }
}
