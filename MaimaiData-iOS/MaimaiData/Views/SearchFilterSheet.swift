import SwiftUI

struct SearchFilterSheet: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss
    @Binding var criteria: SongFilterCriteria

    @State private var draft: SongFilterCriteria = SongFilterCriteria()
    /// Index into `MaimaiConstants.allLevels` (0 = "ALL"/Off, 1...23 = "1"..."15" including "+")
    @State private var levelSliderIndex: Double = 0
    /// 0 = 关闭 (Off), 1...10 = .0 ... .9
    @State private var decSliderIndex: Double = 0

    private var currentLevelIndex: Int {
        min(max(0, Int(levelSliderIndex.rounded())), MaimaiConstants.allLevels.count - 1)
    }

    private var currentDecIndex: Int {
        min(max(0, Int(decSliderIndex.rounded())), 10)
    }

    private var baseIntegerLevel: Int {
        guard currentLevelIndex > 0 else { return 1 }
        let raw = MaimaiConstants.allLevels[currentLevelIndex].replacingOccurrences(of: "+", with: "")
        return Int(raw) ?? 1
    }

    var body: some View {
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

                                // 第二条：小数（最左关闭，往右 .0 ~ .9）
                                AppLabeledSliderRow(
                                    label: "小数",
                                    valueText: secondSliderLabelText,
                                    value: $decSliderIndex,
                                    range: 0...10,
                                    isActive: currentDecIndex > 0,
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
            .appPageBackground()
            .sensoryFeedback(.selection, trigger: currentLevelIndex)
            .sensoryFeedback(.selection, trigger: currentDecIndex)
            .navigationTitle("筛选与排序")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("关闭") { dismiss() }
                }
            }
            .safeAreaInset(edge: .bottom) {
                HStack(spacing: AppTheme.Space.m) {
                    AppSecondaryButton(title: "重置选项", action: resetDraft)

                    AppPrimaryButton(title: "应用筛选", icon: "checkmark") {
                        criteria = draft
                        dismiss()
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.top, AppTheme.itemSpacing)
                .padding(.bottom, AppTheme.Space.s)
            }
            .onAppear {
                draft = criteria
                if criteria.useExactDS {
                    let tenths = Int((criteria.exactDS * 10).rounded())
                    let intPart = max(1, min(15, tenths / 10))
                    let decPart = max(0, min(9, tenths % 10))
                    levelSliderIndex = Double(MaimaiConstants.allLevels.firstIndex(of: "\(intPart)") ?? 1)
                    decSliderIndex = Double(decPart + 1)
                } else {
                    levelSliderIndex = Double(MaimaiConstants.allLevels.firstIndex(of: criteria.selectedLevel) ?? 0)
                    decSliderIndex = 0
                }
            }
        }
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
        if changedDecimal && currentDecIndex > 0 && currentLevelIndex == 0 {
            // 若直接拨动小数滑块而第一条还在 Off，自动将第一条拨到 1
            levelSliderIndex = 1
        }
        if !changedDecimal && currentLevelIndex == 0 && currentDecIndex > 0 {
            // 若将第一条拨回最左 Off，同时关闭小数滑块
            decSliderIndex = 0
        }

        let lvIdx = currentLevelIndex
        let decIdx = currentDecIndex

        if lvIdx == 0 {
            draft.useExactDS = false
            draft.selectedLevel = "ALL"
        } else if decIdx == 0 {
            draft.useExactDS = false
            draft.selectedLevel = MaimaiConstants.allLevels[lvIdx]
        } else {
            draft.useExactDS = true
            let intVal = baseIntegerLevel
            let decVal = decIdx - 1
            draft.exactDS = Double(intVal) + Double(decVal) / 10.0
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
    }

    private func toggle(_ set: inout Set<String>, value: String) {
        if set.contains(value) {
            set.remove(value)
        } else {
            set.insert(value)
        }
    }
}
