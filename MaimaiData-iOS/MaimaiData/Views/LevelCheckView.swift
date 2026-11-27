import SwiftUI

struct LevelCheckView: View {
    @Environment(DataStore.self) private var store
    @State private var sliderLevelIndex: Double = 0
    @State private var sliderCommitTask: Task<Void, Never>?

    private var levels: [String] {
        Array(MaimaiConstants.allLevels.dropFirst()) // Drop "ALL"
    }

    private var displayLevelIndex: Int {
        guard !levels.isEmpty else { return 0 }
        return min(max(0, Int(sliderLevelIndex.rounded())), levels.count - 1)
    }

    private var selectedLevel: String {
        levels[displayLevelIndex]
    }

    private func commitLevelIndex(_ idx: Int) {
        sliderCommitTask?.cancel()
        let clamped = min(max(0, idx), max(0, levels.count - 1))
        sliderLevelIndex = Double(clamped)
        if store.lastLevelCheckIndex != clamped {
            store.lastLevelCheckIndex = clamped
        }
    }

    private func scheduleDebouncedCommit(_ idx: Int) {
        sliderCommitTask?.cancel()
        let clamped = min(max(0, idx), max(0, levels.count - 1))
        sliderCommitTask = Task {
            try? await Task.sleep(for: .milliseconds(120))
            guard !Task.isCancelled else { return }
            if store.lastLevelCheckIndex != clamped {
                store.lastLevelCheckIndex = clamped
            }
        }
    }

    var body: some View {
        @Bindable var storeBindable = store
        let dsGroups = store.levelCheckBuckets[selectedLevel] ?? []
        let totalChartCount = dsGroups.reduce(0) { $0 + $1.items.count }
        let effectiveDisplayMode: CheckDisplayMode = store.checklistDisplayMode == .clear ? .sss : store.checklistDisplayMode

        let matchedRecords = dsGroups.flatMap(\.items).compactMap {
            store.record(forSongId: $0.song.numericId, difficultyIndex: $0.chart.index)
        }
        let stats = ChecklistStats(records: matchedRecords, totalCount: totalChartCount)

        let visibleGroups: [LevelCheckDSBucket] = store.checklistOnlyUnfinished
            ? dsGroups.compactMap { group in
                let remaining = group.items.filter { item in
                    !effectiveDisplayMode.isCompleted(
                        store.record(forSongId: item.song.numericId, difficultyIndex: item.chart.index)
                    )
                }
                return remaining.isEmpty ? nil : LevelCheckDSBucket(ds: group.ds, items: remaining)
            }
            : dsGroups

        ScrollView {
            LazyVStack(spacing: AppTheme.cardSpacing) {
                AppCard(padding: AppTheme.cardPadding) {
                    VStack(spacing: AppTheme.Space.m) {
                        HStack {
                            AppCircleIconButton(
                                systemImage: "chevron.left",
                                accessibilityLabel: "上一个等级",
                                isDisabled: displayLevelIndex == 0
                            ) {
                                if displayLevelIndex > 0 {
                                    commitLevelIndex(displayLevelIndex - 1)
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
                                isDisabled: displayLevelIndex == levels.count - 1
                            ) {
                                if displayLevelIndex < levels.count - 1 {
                                    commitLevelIndex(displayLevelIndex + 1)
                                }
                            }
                        }

                        Slider(
                            value: $sliderLevelIndex,
                            in: 0...Double(max(0, levels.count - 1)),
                            step: 1,
                            onEditingChanged: { editing in
                                if !editing {
                                    commitLevelIndex(Int(sliderLevelIndex.rounded()))
                                }
                            }
                        )
                        .tint(AppTheme.accent)
                    }
                }

                ChecklistStatsHeader(
                    stats: stats,
                    displayMode: Binding(
                        get: { effectiveDisplayMode },
                        set: { store.checklistDisplayMode = $0 }
                    ),
                    onlyUnfinished: $storeBindable.checklistOnlyUnfinished
                )

                if store.isSyncingSongs && store.songs.isEmpty {
                    AppLoadingState(message: "正在同步\(store.serverRegion.shortName)曲库…")
                } else if store.songs.isEmpty {
                    ContentUnavailableView(
                        "暂无\(store.serverRegion.shortName)曲库数据",
                        systemImage: "arrow.triangle.2.circlepath",
                        description: Text("请前往「设置与数据 → 数据同步与存储」点击检查更新")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else if visibleGroups.isEmpty && totalChartCount > 0 {
                    ContentUnavailableView(
                        "已全部达成 \(effectiveDisplayMode.title)",
                        systemImage: "checkmark.seal.fill",
                        description: Text("本等级全部谱面均已达成该目标")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else {
                    ForEach(visibleGroups) { group in
                        AppSectionCard(title: String(format: "定数 %.1f", group.ds)) {
                            AppCapsuleLabel(text: "\(group.items.count) 张")
                        } content: {
                            ChecklistSongGrid {
                                ForEach(group.items) { item in
                                    NavigationLink(
                                        value: SongDetailRoute(song: item.song, initialDifficulty: item.chart.difficulty)
                                    ) {
                                        ChecklistSongTile(
                                            song: item.song,
                                            record: store.record(forSongId: item.song.numericId, difficultyIndex: item.chart.index),
                                            difficulty: item.chart.difficulty,
                                            displayMode: effectiveDisplayMode,
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
        .sensoryFeedback(.selection, trigger: displayLevelIndex)
        .navigationTitle("等级进度表")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            sliderLevelIndex = Double(min(max(0, store.lastLevelCheckIndex), max(0, levels.count - 1)))
        }
        .onDisappear {
            sliderCommitTask?.cancel()
            let current = Int(sliderLevelIndex.rounded())
            if store.lastLevelCheckIndex != current {
                store.lastLevelCheckIndex = current
            }
        }
        .onChange(of: sliderLevelIndex) { _, newValue in
            let next = Int(newValue.rounded())
            if next != store.lastLevelCheckIndex {
                scheduleDebouncedCommit(next)
            }
        }
        .onChange(of: store.lastLevelCheckIndex) { _, newValue in
            if Int(sliderLevelIndex.rounded()) != newValue {
                sliderLevelIndex = Double(newValue)
            }
        }
    }
}
