import SwiftUI

struct SearchFilterSheet: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Binding var criteria: SongFilterCriteria

    @State private var draft: SongFilterCriteria = SongFilterCriteria()
    /// Index into `MaimaiConstants.allLevels` (0 = "ALL"/Off, 1...23 = "1"..."15" including "+")
    @State private var levelSliderIndex: Double = 0
    /// 0 = 关闭 (Off), 1...10 = .0 ... .9
    @State private var decSliderIndex: Double = 0
    /// Preserves the user's chosen decimal index across `14.x <-> 15.0` moves.
    @State private var preferredDecIdx: Int = 0

    private var currentLevelIndex: Int {
        min(max(0, Int(levelSliderIndex.rounded())), MaimaiConstants.allLevels.count - 1)
    }

    private var currentDecIndex: Int {
        min(max(0, Int(decSliderIndex.rounded())), 10)
    }

    private var baseIntegerLevel: Int {
        MaimaiConstants.baseIntegerLevel(forLevelIndex: currentLevelIndex)
    }

    private var isLargeFont: Bool {
        dynamicTypeSize >= .xxLarge
    }

    var body: some View {
        let isLevel15 = currentLevelIndex > 0 && baseIntegerLevel >= MaimaiConstants.maxLevelInteger

        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: AppTheme.cardSpacing) {
                    // 1. 等级 / 定数双滑块 + 排序
                    AppSectionCard(title: levelHeaderText) {
                        Menu {
                            Picker("排序", selection: $draft.sortOption) {
                                ForEach(SongSortOption.allCases) { opt in
                                    Text(opt.rawValue).tag(opt)
                                }
                            }
                        } label: {
                            AppMenuCapsule(title: draft.sortOption.rawValue)
                        }
                    } content: {
                        AppInsetGroup(padding: AppTheme.Space.m) {
                            VStack(spacing: AppTheme.Space.m) {
                                // 第一条：等级 / 整数
                                AppLabeledSliderRow(
                                    label: "等级",
                                    valueText: firstSliderLabelText,
                                    value: $levelSliderIndex,
                                    range: 0...Double(MaimaiConstants.allLevels.count - 1),
                                    isActive: currentLevelIndex > 0,
                                    accessibilityLabel: "等级筛选"
                                )
                                .onChange(of: levelSliderIndex) { _, _ in
                                    syncFromSliders(changedDecimal: false)
                                }

                                // 第二条：小数（最左关闭，往右 .0 ~ .9；15 级锁 .0 / OFF）
                                AppLabeledSliderRow(
                                    label: "小数",
                                    valueText: secondSliderLabelText,
                                    value: $decSliderIndex,
                                    range: 0...10,
                                    isActive: currentDecIndex > 0,
                                    enabled: !isLevel15,
                                    accessibilityLabel: "定数小数位筛选"
                                )
                                .onChange(of: decSliderIndex) { _, _ in
                                    syncFromSliders(changedDecimal: true)
                                }
                            }
                        }
                    }

                    // 2. 分类
                    AppSectionCard(
                        title: "歌曲分类",
                        subtitle: draft.selectedGenres.isEmpty ? "默认包含除宴会场外的全部分类" : "已选 \(draft.selectedGenres.count) 项"
                    ) {
                        if !draft.selectedGenres.isEmpty {
                            AppInlineButton(title: "清空", tint: .secondary) {
                                draft.selectedGenres.removeAll()
                            }
                        }
                    } content: {
                        FlowLayout(spacing: AppTheme.Space.s, lineSpacing: AppTheme.Space.s) {
                            ForEach(MaimaiConstants.allGenres, id: \.self) { genre in
                                FilterCapsuleChip(
                                    title: genre,
                                    isSelected: draft.selectedGenres.contains(genre),
                                    selectedColor: MaimaiConstants.genreColors(for: genre).bg
                                ) {
                                    toggle(&draft.selectedGenres, value: genre)
                                }
                            }
                        }
                    }

                    // 3. 版本
                    AppSectionCard(
                        title: "收录版本",
                        subtitle: draft.selectedVersions.isEmpty ? "全部版本" : "已选 \(draft.selectedVersions.count) 个版本"
                    ) {
                        if !draft.selectedVersions.isEmpty {
                            AppInlineButton(title: "清空", tint: .secondary) {
                                draft.selectedVersions.removeAll()
                            }
                        }
                    } content: {
                        FlowLayout(spacing: AppTheme.Space.s, lineSpacing: AppTheme.Space.s) {
                            ForEach(MaimaiConstants.searchVersionChips(for: store.serverRegion), id: \.self) { ver in
                                FilterCapsuleChip(
                                    title: ver,
                                    isSelected: draft.selectedVersions.contains(ver),
                                    selectedColor: AppTheme.accent
                                ) {
                                    toggle(&draft.selectedVersions, value: ver)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
            }
            .sensoryFeedback(.selection, trigger: currentLevelIndex)
            .sensoryFeedback(.selection, trigger: currentDecIndex)
            .navigationTitle("筛选与排序")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom, spacing: 0) {
                VStack(spacing: 0) {
                    AppInsetDivider()
                    Group {
                        if isLargeFont {
                            VStack(spacing: AppTheme.Space.s) {
                                AppPrimaryButton(title: "应用筛选", icon: "checkmark") {
                                    criteria = SongFilter.prunedCriteria(draft, for: store.serverRegion)
                                    dismiss()
                                }
                                AppSecondaryButton(title: "重置选项", action: resetDraft)
                            }
                        } else {
                            HStack(spacing: AppTheme.Space.m) {
                                AppSecondaryButton(title: "重置选项", action: resetDraft)

                                AppPrimaryButton(title: "应用筛选", icon: "checkmark") {
                                    criteria = SongFilter.prunedCriteria(draft, for: store.serverRegion)
                                    dismiss()
                                }
                            }
                        }
                    }
                    .padding(.horizontal, AppTheme.pagePadding)
                    .padding(.top, AppTheme.itemSpacing)
                    .padding(.bottom, AppTheme.Space.s)
                }
                .background(AppTheme.Palette.sheetBackground)
            }
            .onAppear {
                draft = SongFilter.prunedCriteria(criteria, for: store.serverRegion)
                if draft.useExactDS {
                    let (intPart, decPart) = MaimaiConstants.splitExactDS(draft.exactDS)
                    levelSliderIndex = Double(MaimaiConstants.allLevels.firstIndex(of: "\(intPart)") ?? 1)
                    decSliderIndex = Double(decPart + 1)
                    preferredDecIdx = decPart + 1
                } else {
                    levelSliderIndex = Double(MaimaiConstants.allLevels.firstIndex(of: draft.selectedLevel) ?? 0)
                    decSliderIndex = 0
                    preferredDecIdx = 0
                }
            }
        }
        .appSheetContainer()
    }

    private var firstSliderLabelText: String {
        guard currentLevelIndex > 0 else { return "ALL" }
        if currentDecIndex > 0 {
            return "\(baseIntegerLevel)"
        }
        return MaimaiConstants.allLevels[currentLevelIndex]
    }

    private var secondSliderLabelText: String {
        guard currentDecIndex > 0 else { return "OFF" }
        if baseIntegerLevel >= MaimaiConstants.maxLevelInteger { return ".0" }
        return ".\(currentDecIndex - 1)"
    }

    private var levelHeaderText: String {
        if draft.useExactDS {
            return String(format: "定数筛选：%.1f", draft.exactDS)
        }
        if draft.selectedLevel == "ALL" {
            return "等级与定数筛选"
        }
        return "等级筛选：LEVEL \(draft.selectedLevel)"
    }

    private func syncFromSliders(changedDecimal: Bool) {
        if changedDecimal {
            if !(currentLevelIndex > 0 && baseIntegerLevel >= MaimaiConstants.maxLevelInteger) {
                preferredDecIdx = currentDecIndex
                if currentDecIndex > 0 && currentLevelIndex == 0 {
                    // 若直接拨动小数滑块而第一条还在 Off，自动将第一条拨到 1
                    levelSliderIndex = 1
                }
            }
        } else if currentLevelIndex == 0 {
            preferredDecIdx = 0
        }

        let lvIdx = currentLevelIndex
        let intVal = MaimaiConstants.baseIntegerLevel(forLevelIndex: lvIdx)
        let decIdx = MaimaiConstants.effectiveFilterDecimalIndex(
            levelIndex: lvIdx,
            baseIntLevel: intVal,
            preferredDecIdx: preferredDecIdx
        )
        if currentDecIndex != decIdx {
            decSliderIndex = Double(decIdx)
        }

        if lvIdx == 0 {
            draft.useExactDS = false
            draft.selectedLevel = "ALL"
        } else if decIdx == 0 {
            draft.useExactDS = false
            draft.selectedLevel = MaimaiConstants.allLevels[lvIdx]
        } else {
            draft.useExactDS = true
            draft.exactDS = MaimaiConstants.composeExactDS(intPart: intVal, decPart: decIdx - 1)
            draft.selectedLevel = "\(intVal)"
        }
    }

    private func resetDraft() {
        var reset = SongFilterCriteria()
        reset.keyword = draft.keyword
        reset.onlyFavorites = draft.onlyFavorites
        draft = reset
        levelSliderIndex = 0
        decSliderIndex = 0
        preferredDecIdx = 0
    }

    private func toggle(_ set: inout Set<String>, value: String) {
        if set.contains(value) {
            set.remove(value)
        } else {
            set.insert(value)
        }
    }
}
