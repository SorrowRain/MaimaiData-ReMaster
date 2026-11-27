import SwiftUI

struct VersionCheckView: View {
    @Environment(DataStore.self) private var store
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var scopeCache: ScopeCache = .empty
    @State private var showDifficultyPopover = false

    private struct LevelGroup: Identifiable {
        let id: String
        let level: String
        let songs: [SongData]
    }

    private struct ScopeCache {
        let cacheKey: String
        let songsByDifficulty: [DifficultyType: [SongData]]
        let groupedByDifficulty: [DifficultyType: [LevelGroup]]

        static let empty = ScopeCache(
            cacheKey: "",
            songsByDifficulty: [:],
            groupedByDifficulty: [:]
        )
    }

    private var scopes: [PlateCheckScope] {
        store.activePlateScopes
    }

    private var activeScope: PlateCheckScope {
        let items = scopes
        guard !items.isEmpty else {
            return .fallback
        }
        if let savedId = store.activeVersionCheckScopeId,
           let matched = items.first(where: { $0.id == savedId }) {
            return matched
        }
        return items[0]
    }

    private func availableDifficulties(for scope: PlateCheckScope) -> [DifficultyType] {
        if scope.supportsRemaster {
            return [.basic, .advanced, .expert, .master, .remaster]
        }
        return [.basic, .advanced, .expert, .master]
    }

    private func rebuildCache() {
        let scope = activeScope
        let key = "\(scope.id)|\(store.serverRegion.rawValue)|\(store.catalogRevision)|\(store.plateCatalog?.updatedAt ?? "")"
        guard scopeCache.cacheKey != key else { return }

        let diffs = availableDifficulties(for: scope)
        if !diffs.contains(store.versionCheckDifficulty) {
            store.versionCheckDifficulty = .master
        }

        var songsByDiff: [DifficultyType: [SongData]] = [:]
        var groupedByDiff: [DifficultyType: [LevelGroup]] = [:]

        for diff in diffs {
            let diffIdx = diff.rawValue
            let filtered = store.songs.filter { scope.contains($0, difficulty: diff) }
            let sorted = filtered.sorted { ($0.ds[safe: diffIdx] ?? 0) > ($1.ds[safe: diffIdx] ?? 0) }
            songsByDiff[diff] = sorted

            let dict = Dictionary(grouping: sorted) { $0.level[safe: diffIdx] ?? "?" }
            groupedByDiff[diff] = dict.keys
                .sorted { MaimaiConstants.levelSortOrder($0) > MaimaiConstants.levelSortOrder($1) }
                .map { LevelGroup(id: "\(scope.id)_\(diffIdx)_\($0)", level: $0, songs: dict[$0] ?? []) }
        }

        scopeCache = ScopeCache(
            cacheKey: key,
            songsByDifficulty: songsByDiff,
            groupedByDifficulty: groupedByDiff
        )
    }

    var body: some View {
        @Bindable var storeBindable = store
        let scope = activeScope
        let diffs = availableDifficulties(for: scope)
        let effectiveDifficulty: DifficultyType = diffs.contains(store.versionCheckDifficulty)
            ? store.versionCheckDifficulty
            : .master
        let effectiveMode: CheckDisplayMode = scope.goal == .clear
            ? .clear
            : (store.checklistDisplayMode == .clear ? .sss : store.checklistDisplayMode)
        let diffIdx = effectiveDifficulty.rawValue
        let diffTint = effectiveDifficulty.color

        let scopeSelection = Binding<String>(
            get: { scope.id },
            set: { newId in
                store.activeVersionCheckScopeId = newId
                rebuildCache()
            }
        )

        let activeSongs = scopeCache.songsByDifficulty[effectiveDifficulty] ?? []
        let activeMatchedRecords = activeSongs.compactMap {
            store.record(forSongId: $0.numericId, difficultyIndex: diffIdx)
        }
        let activeStats = ChecklistStats(
            records: activeMatchedRecords,
            totalCount: activeSongs.count
        )

        let activeGroups = scopeCache.groupedByDifficulty[effectiveDifficulty] ?? []
        let visibleGroups: [LevelGroup] = store.checklistOnlyUnfinished
            ? activeGroups.compactMap { group in
                let remaining = group.songs.filter { song in
                    !effectiveMode.isCompleted(store.record(forSongId: song.numericId, difficultyIndex: diffIdx))
                }
                return remaining.isEmpty ? nil : LevelGroup(id: group.id, level: group.level, songs: remaining)
            }
            : activeGroups

        let countSummary = "共 \(activeSongs.count) 首"

        let footnoteText: String? = (!scope.hasJiangBadge && scope.goal == .standard)
            ? "真代无「将」牌，SSS 仅作统计"
            : nil

        ScrollView {
            LazyVStack(spacing: AppTheme.cardSpacing) {
                AppCard(padding: AppTheme.cardPadding) {
                    if dynamicTypeSize >= .xxLarge {
                        VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                            HStack(spacing: AppTheme.Space.m) {
                                Image(scope.versionAsset)
                                    .resizable()
                                    .scaledToFit()
                                    .frame(width: 76, height: 34, alignment: .leading)

                                Spacer(minLength: AppTheme.Space.s)

                                Menu {
                                    Picker("牌子", selection: scopeSelection) {
                                        ForEach(scopes) { item in
                                            Text(item.title).tag(item.id)
                                        }
                                    }
                                } label: {
                                    AppMenuCapsule(title: "切换牌子")
                                }
                            }

                            VStack(alignment: .leading, spacing: 2) {
                                Text(scope.title)
                                    .font(AppTheme.Typography.rowTitle)
                                    .foregroundStyle(.primary)
                                    .lineLimit(2)
                                Text(countSummary)
                                    .font(AppTheme.Typography.caption)
                                    .foregroundStyle(.secondary)
                                    .contentTransition(.numericText())
                            }
                        }
                    } else {
                        HStack(alignment: .center, spacing: AppTheme.Space.m) {
                            Image(scope.versionAsset)
                                .resizable()
                                .scaledToFit()
                                .frame(width: 76, height: 34, alignment: .center)

                            VStack(alignment: .leading, spacing: 2) {
                                Text(scope.title)
                                    .font(AppTheme.Typography.rowTitle)
                                    .foregroundStyle(.primary)
                                    .lineLimit(2)
                                    .minimumScaleFactor(0.88)
                                Text(countSummary)
                                    .font(AppTheme.Typography.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                                    .contentTransition(.numericText())
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)

                            Menu {
                                Picker("牌子", selection: scopeSelection) {
                                    ForEach(scopes) { item in
                                        Text(item.title).tag(item.id)
                                    }
                                }
                            } label: {
                                AppMenuCapsule(title: "切换牌子")
                            }
                            .fixedSize()
                        }
                    }
                }

                ChecklistStatsHeader(
                    stats: activeStats,
                    displayMode: Binding(
                        get: { effectiveMode },
                        set: { next in
                            if scope.goal != .clear {
                                store.checklistDisplayMode = next
                            }
                        }
                    ),
                    onlyUnfinished: $storeBindable.checklistOnlyUnfinished,
                    isClearGoal: scope.goal == .clear,
                    footnote: footnoteText
                ) {
                    Button {
                        showDifficultyPopover = true
                    } label: {
                        AppMenuCapsule(title: effectiveDifficulty.shortName, tint: diffTint)
                    }
                    .buttonStyle(ScalePressButtonStyle())
                    .popover(isPresented: $showDifficultyPopover, arrowEdge: .top) {
                        let isDark = colorScheme == .dark
                        VStack(spacing: 4) {
                            ForEach(diffs, id: \.self) { diff in
                                let isSelected = diff == effectiveDifficulty
                                let isRemaster = diff == .remaster
                                let pillFill: Color = if isSelected {
                                    if isRemaster {
                                        isDark ? AppTheme.Palette.remasterDarkFill : Color.white
                                    } else {
                                        diff.solidFill(isDark: isDark)
                                    }
                                } else {
                                    Color.clear
                                }
                                let pillFg: Color = if isSelected {
                                    if isRemaster {
                                        isDark ? AppTheme.Palette.remasterDarkText : AppTheme.Palette.chartRemaster
                                    } else {
                                        diff.readableForeground
                                    }
                                } else {
                                    .primary
                                }
                                Button {
                                    store.versionCheckDifficulty = diff
                                    showDifficultyPopover = false
                                } label: {
                                    Text(diff.shortName)
                                        .font(isSelected ? AppTheme.Typography.captionBold : AppTheme.Typography.body)
                                        .foregroundStyle(pillFg)
                                        .frame(width: 92, height: 34)
                                        .background(pillFill, in: Capsule())
                                        .overlay {
                                            if isSelected && isRemaster {
                                                Capsule().strokeBorder(diff.color, lineWidth: 1.2)
                                            }
                                        }
                                        .contentShape(Capsule())
                                }
                                .buttonStyle(.plain)
                            }
                        }
                        .padding(8)
                        .fixedSize(horizontal: true, vertical: true)
                        .presentationCornerRadius(16)
                        .presentationCompactAdaptation(.popover)
                    }
                }

                if store.isSyncingSongs && store.songs.isEmpty {
                    AppLoadingState(message: "正在同步\(store.serverRegion.shortName)曲库…")
                } else if store.songs.isEmpty {
                    ContentUnavailableView(
                        "暂无\(store.serverRegion.shortName)曲库数据",
                        systemImage: "arrow.triangle.2.circlepath",
                        description: Text("请前往「设置与数据 → 数据同步与存储」点击检查更新")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else if visibleGroups.isEmpty && !activeGroups.isEmpty {
                    ContentUnavailableView(
                        "已全部达成 \(effectiveMode.title)",
                        systemImage: "checkmark.seal.fill",
                        description: Text("当前筛选下全部谱面均已达成该目标")
                    )
                    .padding(.top, AppTheme.Space.xl)
                } else {
                    ForEach(visibleGroups) { group in
                        AppSectionCard(title: "Level \(group.level)") {
                            AppCapsuleLabel(text: "\(group.songs.count) 首", tint: diffTint, isNeutral: false)
                        } content: {
                            ChecklistSongGrid {
                                ForEach(group.songs) { song in
                                    NavigationLink(value: SongDetailRoute(song: song, initialDifficulty: effectiveDifficulty)) {
                                        ChecklistSongTile(
                                            song: song,
                                            record: store.record(forSongId: song.numericId, difficultyIndex: diffIdx),
                                            difficulty: effectiveDifficulty,
                                            displayMode: effectiveMode,
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
        .sensoryFeedback(.selection, trigger: scope.id)
        .sensoryFeedback(.selection, trigger: effectiveDifficulty)
        .navigationTitle("牌子进度表")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            rebuildCache()
        }
        .onChange(of: store.activeVersionCheckScopeId) { _, _ in rebuildCache() }
        .onChange(of: store.plateCatalog) { _, _ in rebuildCache() }
        .onChange(of: store.serverRegion) { _, _ in rebuildCache() }
        .onChange(of: store.catalogRevision) { _, _ in rebuildCache() }
    }
}
