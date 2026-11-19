import SwiftUI

struct RatingCalculatorView: View {
    @Environment(DataStore.self) private var store

    private enum CalculatorMode: Int, CaseIterable {
        case singleSong = 0
        case targetReverse = 1

        var title: String {
            switch self {
            case .singleSong: return "单曲算分"
            case .targetReverse: return "目标反推"
            }
        }
    }

    @State private var mode: CalculatorMode = .singleSong

    // Single-song calculator state (1...15 + .0 ... .9, default 14.8)
    @State private var intLevelSlider: Double = 14
    @State private var decSlider: Double = 8
    @State private var customAchiInput: String = ""

    // Reverse target rating state
    @State private var targetRatingInput: String = "15000"
    @State private var targetRows: [RatingCalculator.TargetRow] = RatingCalculator.reverseCalculate(targetTotalRating: 15000)
    @State private var targetError: String?

    private var intPart: Int {
        min(max(1, Int(intLevelSlider.rounded())), 15)
    }

    private var decPart: Int {
        min(max(0, Int(decSlider.rounded())), 9)
    }

    private var selectedDS: Double {
        Double(intPart) + Double(decPart) / 10.0
    }

    private var parsedCustomAchi: Double? {
        let trimmed = customAchiInput.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty, let achi = Double(trimmed), achi >= 0 else { return nil }
        return achi
    }

    private var customRatingResult: Int? {
        guard let achi = parsedCustomAchi else { return nil }
        return RatingCalculator.singleSongRating(ds: selectedDS, achievement: achi)
    }

    private var customInputError: String? {
        let trimmed = customAchiInput.trimmingCharacters(in: .whitespaces)
        guard !trimmed.isEmpty else { return nil }
        return parsedCustomAchi == nil ? "请输入有效的达成率数值" : nil
    }

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                Picker("计算模式", selection: $mode.animation(.snappy(duration: 0.22))) {
                    ForEach(CalculatorMode.allCases, id: \.self) { item in
                        Text(item.title).tag(item)
                    }
                }
                .pickerStyle(.segmented)

                switch mode {
                case .singleSong:
                    singleSongSection
                case .targetReverse:
                    reverseTargetSection
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
            .dismissKeyboardOnTap()
        }
        .scrollDismissesKeyboard(.interactively)
        .appPageBackground()
        .navigationTitle("Rating 计算器")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.selection, trigger: mode)
        .sensoryFeedback(.selection, trigger: intPart)
        .sensoryFeedback(.selection, trigger: decPart)
    }

    // MARK: - Mode 1: Dual-Slider Constant Picker + Tier Reference Table

    private var singleSongSection: some View {
        VStack(spacing: AppTheme.cardSpacing) {
            AppSectionCard(
                title: String(format: "谱面定数：%.1f", selectedDS),
                subtitle: "拖动滑块选择定数，下方即时展示各档得分"
            ) {
                if let customRatingResult {
                    AppCapsuleLabel(
                        text: "\(customRatingResult)",
                        style: .metric(prefix: "Ra")
                    )
                }
            } content: {
                VStack(alignment: .leading, spacing: AppTheme.Space.m) {
                    AppInsetGroup(padding: AppTheme.Space.m) {
                        VStack(spacing: AppTheme.Space.m) {
                            AppLabeledSliderRow(
                                label: "整数",
                                valueText: "\(intPart)",
                                value: $intLevelSlider,
                                range: 1...15,
                                accessibilityLabel: "定数整数部分"
                            )

                            AppLabeledSliderRow(
                                label: "小数",
                                valueText: ".\(decPart)",
                                value: $decSlider,
                                range: 0...9,
                                accessibilityLabel: "定数小数部分"
                            )
                        }
                    }

                    AppTextField(
                        title: "精确达成率试算（可选）",
                        placeholder: "输入任意达成率，如 100.2350",
                        text: $customAchiInput,
                        keyboardType: .decimalPad
                    )

                    if let customInputError {
                        Label(customInputError, systemImage: "exclamationmark.circle.fill")
                            .font(AppTheme.Typography.caption)
                            .foregroundStyle(AppTheme.destructive)
                    }
                }
            }

            AppSectionCard(title: "分段得分对照") {
                RatingTierTable(
                    rows: RatingCalculator.buildTierRows(
                        ds: selectedDS,
                        currentAchievement: parsedCustomAchi,
                        includeAPBonusRow: store.serverRegion.usesAPBonus
                    ),
                    showB50Column: false,
                    region: store.serverRegion
                )
            }
        }
    }

    // MARK: - Mode 2: Target Total Rating Reverse Calculator

    private var reverseTargetSection: some View {
        AppSectionCard(
            title: "目标 Rating 达成率反推",
            subtitle: "按 50 首均分推算所需最低定数与达成率"
        ) {
            VStack(alignment: .leading, spacing: AppTheme.Space.m) {
                HStack(alignment: .bottom, spacing: AppTheme.itemSpacing) {
                    AppTextField(
                        title: "目标总 Rating",
                        placeholder: "如 15000",
                        text: Binding(
                            get: { targetRatingInput },
                            set: { targetRatingInput = String($0.filter(\.isNumber).prefix(6)) }
                        ),
                        keyboardType: .numberPad
                    )

                    AppPrimaryButton(
                        title: "推算",
                        style: .inputCompanion
                    ) {
                        KeyboardDismiss.hide()
                        let val = Int(targetRatingInput.trimmingCharacters(in: .whitespaces)) ?? 0
                        withAnimation(.snappy(duration: 0.24)) {
                            targetRows = RatingCalculator.reverseCalculate(targetTotalRating: val)
                            if val <= 0 {
                                targetError = "请输入大于 0 的目标 Rating"
                            } else if targetRows.isEmpty {
                                targetError = "当前目标无法生成达成率建议，请检查数值范围"
                            } else {
                                targetError = nil
                            }
                        }
                    }
                }

                if let targetError {
                    Label(targetError, systemImage: "exclamationmark.circle.fill")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(AppTheme.destructive)
                }

                if !targetRows.isEmpty {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            HStack {
                                Text("定数")
                                    .frame(width: 48, alignment: .leading)
                                Text("所需达成率")
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                Text("单曲 Ra")
                                    .frame(width: 64, alignment: .trailing)
                                Text("50首总计")
                                    .frame(width: 72, alignment: .trailing)
                            }
                            .font(AppTheme.Typography.caption2Bold)
                            .foregroundStyle(.secondary)
                            .padding(.horizontal, AppTheme.Space.m)
                            .padding(.vertical, AppTheme.Space.s)

                            ForEach(targetRows) { row in
                                Divider()
                                HStack {
                                    Text(String(format: "%.1f", row.level))
                                        .font(AppTheme.Typography.captionMeta)
                                        .frame(width: 48, alignment: .leading)
                                    Text(row.achievementText)
                                        .font(AppTheme.Typography.captionMono)
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    Text("\(row.singleRating)")
                                        .font(AppTheme.Typography.captionMeta)
                                        .foregroundStyle(AppTheme.accentText)
                                        .frame(width: 64, alignment: .trailing)
                                    Text("\(row.totalRating)")
                                        .font(AppTheme.Typography.captionMono)
                                        .foregroundStyle(.secondary)
                                        .frame(width: 72, alignment: .trailing)
                                }
                                .padding(.horizontal, AppTheme.Space.m)
                                .padding(.vertical, AppTheme.Space.s)
                            }
                        }
                    }
                }
            }
        }
    }
}
