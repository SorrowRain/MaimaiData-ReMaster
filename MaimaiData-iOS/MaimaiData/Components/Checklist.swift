import SwiftUI

enum CheckDisplayMode: Int, CaseIterable {
    case sss = 0
    case fc = 1
    case ap = 2
    case fsd = 3
    case clear = 4

    static let standardModes: [CheckDisplayMode] = [.sss, .fc, .ap, .fsd]

    var title: String {
        switch self {
        case .sss: return "SSS"
        case .fc: return "FC"
        case .ap: return "AP"
        case .fsd: return "FSD"
        case .clear: return "CLEAR"
        }
    }

    func isCompleted(_ record: ProberRecord?) -> Bool {
        guard let record else { return false }
        switch self {
        case .sss:
            return record.achievements >= 100.0
        case .fc:
            return !record.fc.isEmpty
        case .ap:
            return record.fc == "ap" || record.fc == "app"
        case .fsd:
            return record.fs == "fsd" || record.fs == "fsdp"
        case .clear:
            return record.achievements >= 80.0
        }
    }
}

private let checklistGridColumns = [GridItem(.adaptive(minimum: 72), spacing: AppTheme.itemSpacing)]

struct ChecklistStatsHeader<ExtraTrailing: View>: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    let stats: ChecklistStats
    @Binding var displayMode: CheckDisplayMode
    @Binding var onlyUnfinished: Bool
    var isClearGoal: Bool = false
    var footnote: String? = nil
    @ViewBuilder let extraTrailing: () -> ExtraTrailing

    init(
        stats: ChecklistStats,
        displayMode: Binding<CheckDisplayMode>,
        onlyUnfinished: Binding<Bool>,
        isClearGoal: Bool = false,
        footnote: String? = nil,
        @ViewBuilder extraTrailing: @escaping () -> ExtraTrailing = { EmptyView() }
    ) {
        self.stats = stats
        self._displayMode = displayMode
        self._onlyUnfinished = onlyUnfinished
        self.isClearGoal = isClearGoal
        self.footnote = footnote
        self.extraTrailing = extraTrailing
    }

    private var statColumns: [GridItem] {
        let count = dynamicTypeSize >= .xxLarge ? 2 : 4
        return Array(repeating: GridItem(.flexible(), spacing: AppTheme.Space.s), count: count)
    }

    var body: some View {
        AppSectionCard(title: "完成度统计") {
            HStack(spacing: AppTheme.Space.s) {
                extraTrailing()

                AppInlineButton(
                    title: "仅看未达成",
                    icon: onlyUnfinished ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle",
                    tint: AppTheme.accent,
                    isNeutral: !onlyUnfinished,
                    isSolidActive: onlyUnfinished
                ) {
                    withAnimation(.snappy(duration: 0.22)) {
                        onlyUnfinished.toggle()
                    }
                }
            }
        } content: {
            VStack(alignment: .leading, spacing: AppTheme.Space.m) {
                if isClearGoal {
                    AppStatTile(
                        title: "CLEAR (≥80%)",
                        value: "\(stats.clearCount)/\(stats.totalCount)",
                        tint: AppTheme.accent,
                        fillTint: AppTheme.accent,
                        isSelected: true
                    )
                    .accessibilityLabel("CLEAR 进度 \(stats.clearCount) / \(stats.totalCount)")
                } else {
                    LazyVGrid(columns: statColumns, spacing: AppTheme.Space.s) {
                        ForEach(CheckDisplayMode.standardModes, id: \.self) { mode in
                            statButton(for: mode)
                        }
                    }
                }

                if let footnote, !footnote.isEmpty {
                    Text(footnote)
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .sensoryFeedback(.selection, trigger: displayMode)
        .sensoryFeedback(.selection, trigger: onlyUnfinished)
    }

    private func statButton(for mode: CheckDisplayMode) -> some View {
        let count: Int
        let tint: Color
        let fillTint: Color?
        switch mode {
        case .sss:
            count = stats.sssCount
            tint = AppTheme.Palette.statSSS
            fillTint = AppTheme.Palette.statSSSFill
        case .fc:
            count = stats.fcCount
            tint = AppTheme.Palette.statFC
            fillTint = nil
        case .ap:
            count = stats.apCount
            tint = AppTheme.Palette.statAP
            fillTint = AppTheme.Palette.statAPFill
        case .fsd:
            count = stats.fsdCount
            tint = AppTheme.Palette.statFSD
            fillTint = nil
        case .clear:
            count = stats.clearCount
            tint = AppTheme.accent
            fillTint = AppTheme.accent
        }
        let selected = displayMode == mode
        return Button {
            withAnimation(.snappy(duration: 0.2)) {
                displayMode = mode
            }
        } label: {
            AppStatTile(
                title: mode.title,
                value: "\(count)/\(stats.totalCount)",
                tint: tint,
                fillTint: fillTint,
                isSelected: selected
            )
        }
        .buttonStyle(ScalePressButtonStyle())
        .accessibilityLabel("\(mode.title) 进度 \(count) / \(stats.totalCount)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct ChecklistSongGrid<Content: View>: View {
    @ViewBuilder let content: () -> Content

    var body: some View {
        LazyVGrid(columns: checklistGridColumns, spacing: AppTheme.itemSpacing) {
            content()
        }
    }
}

struct ChecklistSongTile: View {
    @Environment(\.colorScheme) private var colorScheme

    let song: SongData
    let record: ProberRecord?
    let difficulty: DifficultyType
    let displayMode: CheckDisplayMode
    var showTypeBadge: Bool = false
    var region: MaimaiConstants.ServerRegion = .cn

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.tile, style: .continuous)
        let isRemaster = difficulty == .remaster
        let isDark = colorScheme == .dark
        let isCompleted = record.map { displayMode.isCompleted($0) } ?? false
        let scrimOpacity: Double = record == nil ? 0 : (isCompleted ? 0.60 : 0.34)

        ZStack(alignment: .topTrailing) {
            CoverImageView(song: song)
                .frame(width: 72, height: 72)
                .clipped()
                .overlay {
                    if scrimOpacity > 0 {
                        Color.black.opacity(scrimOpacity)
                    }
                }
                .overlay {
                    if let record {
                        overlayMark(for: record, isCompleted: isCompleted)
                    }
                }
                .clipShape(shape)
                .overlay {
                    if isRemaster {
                        let outerColor = isDark ? AppTheme.Palette.diffRemaster : AppTheme.Palette.chartRemaster
                        let innerShape = RoundedRectangle(cornerRadius: max(0, AppTheme.Radius.tile - 1), style: .continuous)
                        ZStack {
                            shape.strokeBorder(outerColor, lineWidth: 1)
                            innerShape
                                .strokeBorder(AppTheme.Palette.boardMainRemaster, lineWidth: 2)
                                .padding(1)
                        }
                    } else {
                        shape.strokeBorder(difficulty.color, lineWidth: 2.5)
                    }
                }
                .overlay(alignment: .bottomTrailing) {
                    if isCompleted {
                        Image(systemName: "checkmark")
                            .font(.system(size: 9, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 16, height: 16)
                            .background(AppTheme.Palette.statFC, in: Circle())
                            .overlay(
                                Circle().strokeBorder(Color.white.opacity(0.90), lineWidth: 1)
                            )
                            .padding(4)
                    }
                }

            if showTypeBadge {
                ChartTypeBadge(isDX: song.isDX, compact: true)
                    .padding(3)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tileAccessibilityLabel(isCompleted: isCompleted))
    }

    private func tileAccessibilityLabel(isCompleted: Bool) -> String {
        guard let record else { return "\(song.title)，未游玩" }
        let completionText = isCompleted ? "已达成 \(displayMode.title)" : "未达成 \(displayMode.title)"
        switch displayMode {
        case .sss, .clear:
            return "\(song.title)，\(completionText)，\(record.rankAccessibilityLabel)"
        case .fc, .ap:
            if isCompleted, record.fcAssetName != nil {
                return "\(song.title)，\(completionText)，\(record.fcAccessibilityLabel)"
            }
            return "\(song.title)，\(completionText)"
        case .fsd:
            if isCompleted, record.fsAssetName != nil {
                return "\(song.title)，\(completionText)，\(record.fsAccessibilityLabel)"
            }
            return "\(song.title)，\(completionText)"
        }
    }

    @ViewBuilder
    private func overlayMark(for record: ProberRecord, isCompleted: Bool) -> some View {
        switch displayMode {
        case .sss, .clear:
            Image(record.rankAssetName(for: region))
                .resizable()
                .scaledToFit()
                .frame(width: 52, height: 24)
        case .fc, .ap:
            if isCompleted, let badgeAsset = record.fcAssetName {
                Image(badgeAsset)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 32, height: 32)
            }
        case .fsd:
            if isCompleted, let badgeAsset = record.fsAssetName {
                Image(badgeAsset)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 32, height: 32)
            }
        }
    }
}
