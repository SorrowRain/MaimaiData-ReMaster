import SwiftUI

enum CheckDisplayMode: Int, CaseIterable {
    case sss = 0
    case fc = 1
    case ap = 2
    case fsd = 3

    var title: String {
        switch self {
        case .sss: return "SSS (将)"
        case .fc: return "FC (极)"
        case .ap: return "AP (神)"
        case .fsd: return "FSD (舞舞)"
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
        }
    }
}

private let checklistGridColumns = [GridItem(.adaptive(minimum: 72), spacing: AppTheme.itemSpacing)]
private let checklistStatColumns = Array(repeating: GridItem(.flexible(), spacing: AppTheme.Space.s), count: 4)

struct ChecklistStatsHeader<LeadingAccessory: View>: View {
    let stats: ChecklistStats
    @Binding var displayMode: CheckDisplayMode
    @Binding var onlyUnfinished: Bool
    private let leadingAccessory: LeadingAccessory

    init(
        stats: ChecklistStats,
        displayMode: Binding<CheckDisplayMode>,
        onlyUnfinished: Binding<Bool>,
        @ViewBuilder leadingAccessory: () -> LeadingAccessory
    ) {
        self.stats = stats
        self._displayMode = displayMode
        self._onlyUnfinished = onlyUnfinished
        self.leadingAccessory = leadingAccessory()
    }

    var body: some View {
        AppSectionCard(title: "完成度统计") {
            HStack(spacing: AppTheme.Space.s) {
                leadingAccessory
                AppInlineButton(
                    title: "仅看未达成",
                    icon: onlyUnfinished ? "line.3.horizontal.decrease.circle.fill" : "line.3.horizontal.decrease.circle",
                    tint: onlyUnfinished ? AppTheme.accent : .secondary
                ) {
                    withAnimation(.snappy(duration: 0.22)) {
                        onlyUnfinished.toggle()
                    }
                }
            }
        } content: {
            LazyVGrid(columns: checklistStatColumns, spacing: AppTheme.Space.s) {
                statButton(mode: .sss, title: "SSS", count: stats.sssCount, tint: AppTheme.Palette.statSSS)
                statButton(mode: .fc, title: "FC", count: stats.fcCount, tint: AppTheme.Palette.statFC)
                statButton(mode: .ap, title: "AP", count: stats.apCount, tint: AppTheme.Palette.statAP)
                statButton(mode: .fsd, title: "FSD", count: stats.fsdCount, tint: AppTheme.Palette.statFSD)
            }
        }
        .sensoryFeedback(.selection, trigger: displayMode)
        .sensoryFeedback(.selection, trigger: onlyUnfinished)
    }

    private func statButton(mode: CheckDisplayMode, title: String, count: Int, tint: Color) -> some View {
        let selected = displayMode == mode
        return Button {
            withAnimation(.snappy(duration: 0.2)) {
                displayMode = mode
            }
        } label: {
            AppStatTile(
                title: title,
                value: "\(count)/\(stats.totalCount)",
                tint: tint,
                isSelected: selected
            )
        }
        .buttonStyle(.plain)
        .accessibilityLabel("\(mode.title) 进度 \(count) / \(stats.totalCount)")
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

extension ChecklistStatsHeader where LeadingAccessory == EmptyView {
    init(
        stats: ChecklistStats,
        displayMode: Binding<CheckDisplayMode>,
        onlyUnfinished: Binding<Bool>
    ) {
        self.init(
            stats: stats,
            displayMode: displayMode,
            onlyUnfinished: onlyUnfinished
        ) {
            EmptyView()
        }
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
    let song: SongData
    let record: ProberRecord?
    let borderColor: Color
    let displayMode: CheckDisplayMode
    var showTypeBadge: Bool = false
    var region: MaimaiConstants.ServerRegion = .cn

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: AppTheme.Radius.tile, style: .continuous)
        ZStack(alignment: .topTrailing) {
            CoverImageView(song: song)
                .frame(width: 72, height: 72)
                .clipped()
                .overlay {
                    if record != nil {
                        Color.black.opacity(0.46)
                    }
                }
                .overlay {
                    if let record {
                        overlayMark(for: record)
                    }
                }
                .clipShape(shape)
                .overlay(
                    shape.strokeBorder(borderColor, lineWidth: 2.5)
                )

            if showTypeBadge {
                ChartTypeBadge(isDX: song.isDX, compact: true)
                    .padding(3)
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(tileAccessibilityLabel)
    }

    private var tileAccessibilityLabel: String {
        guard let record else { return song.title }
        switch displayMode {
        case .sss:
            return "\(song.title)，\(record.rankAccessibilityLabel)"
        case .fc, .ap:
            return record.fcAssetName != nil ? "\(song.title)，\(record.fcAccessibilityLabel)" : song.title
        case .fsd:
            return record.fsAssetName != nil ? "\(song.title)，\(record.fsAccessibilityLabel)" : song.title
        }
    }

    @ViewBuilder
    private func overlayMark(for record: ProberRecord) -> some View {
        switch displayMode {
        case .sss:
            Image(record.rankAssetName(for: region))
                .resizable()
                .scaledToFit()
                .frame(width: 52, height: 24)
                .accessibilityLabel(record.rankAccessibilityLabel)
        case .fc, .ap:
            if let fc = record.fcAssetName {
                Image(fc)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 32, height: 32)
                    .accessibilityLabel(record.fcAccessibilityLabel)
            }
        case .fsd:
            if let fs = record.fsAssetName {
                Image(fs)
                    .resizable()
                    .scaledToFit()
                    .frame(width: 32, height: 32)
                    .accessibilityLabel(record.fsAccessibilityLabel)
            }
        }
    }
}
