import SwiftUI

/// Shared 2-column / 3-column achievement-to-Rating reference table.
/// Used by both `SongDetailView` (3 columns when logged in, 2 columns when logged out)
/// and `RatingCalculatorView` (2 columns).
struct RatingTierTable: View {
    let rows: [RatingCalculator.TierRow]
    var showB50Column: Bool = false
    var region: MaimaiConstants.ServerRegion = .cn

    var body: some View {
        AppInsetGroup {
            VStack(spacing: 0) {
                HStack(spacing: AppTheme.Space.s) {
                    Text("达成率 / 等级")
                        .frame(maxWidth: .infinity, alignment: .center)
                    if showB50Column {
                        Text("单曲分")
                            .frame(width: 72, alignment: .center)
                        Text("B50 可加")
                            .frame(width: 72, alignment: .center)
                    } else {
                        Text("单曲分")
                            .frame(maxWidth: .infinity, alignment: .center)
                    }
                }
                .font(AppTheme.Typography.caption2Bold)
                .foregroundStyle(.secondary)
                .padding(.horizontal, AppTheme.Space.m)
                .padding(.vertical, AppTheme.Space.s)

                ForEach(rows) { row in
                    AppInsetDivider()
                    tierRowView(row)
                }
            }
        }
    }

    private func tierRowView(_ row: RatingCalculator.TierRow) -> some View {
        HStack(spacing: AppTheme.Space.s) {
            HStack(spacing: AppTheme.Space.xs) {
                if row.isAPRow {
                    Image(row.badgeAssetName(for: region))
                        .resizable()
                        .scaledToFit()
                        .frame(width: 20, height: 20)
                        .frame(width: 38.4, height: 18, alignment: .center)
                        .frame(width: 46, height: 18, alignment: .leading)
                        .accessibilityLabel("AP")
                } else {
                    // All PRiSM & CiRCLE rank PNGs share a 256x120 canvas (38.4x18pt at height 18).
                    // `+` variants shift base letters slightly left to center the trailing `+`;
                    // measured per-theme offsets align the base `S` glyphs identically within each tier.
                    Image(row.badgeAssetName(for: region))
                        .resizable()
                        .scaledToFit()
                        .frame(height: 18)
                        .offset(x: opticalLetterOffset(for: row.rate))
                        .frame(width: 46, height: 18, alignment: .leading)
                        .accessibilityLabel(row.rate.uppercased().replacingOccurrences(of: "P", with: "+"))
                }

                Text(row.achievementText)
                    .font(AppTheme.Typography.captionMono)
                    .foregroundStyle(.primary)
                    .frame(minWidth: 74, alignment: .trailing)
                    .fixedSize(horizontal: true, vertical: false)
                    .lineLimit(1)
            }
            .frame(maxWidth: .infinity, alignment: .center)

            if showB50Column {
                Text("\(row.singleRating)")
                    .font(AppTheme.Typography.captionMeta)
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText())
                    .frame(width: 72, alignment: .center)

                let gain = row.b50Gain ?? 0
                Group {
                    if gain > 0 {
                        Text("+\(gain)")
                            .font(AppTheme.Typography.captionMetaBold)
                            .foregroundStyle(AppTheme.accentText)
                            .contentTransition(.numericText())
                    } else {
                        Text("—")
                            .font(AppTheme.Typography.captionMono)
                            .foregroundStyle(.tertiary)
                    }
                }
                .frame(width: 72, alignment: .center)
            } else {
                Text("\(row.singleRating)")
                    .font(AppTheme.Typography.captionMeta)
                    .foregroundStyle(.primary)
                    .contentTransition(.numericText())
                    .frame(maxWidth: .infinity, alignment: .center)
            }
        }
        .padding(.horizontal, AppTheme.Space.m)
        .padding(.vertical, 9)
        .appInsetRowHighlight(row.isCurrentTier)
        .opacity(row.isPassed ? 0.52 : 1.0)
        .accessibilityElement(children: .combine)
    }

    private func opticalLetterOffset(for rate: String) -> CGFloat {
        switch MaimaiConstants.rankIconTheme(for: region) {
        case .prism:
            switch rate {
            case "sssp": return 2.25
            case "ssp": return 2.40
            case "sp": return 2.55
            default: return 0
            }
        case .circle:
            switch rate {
            case "sssp": return 2.55
            case "ssp": return 2.25
            case "sp": return 3.45
            default: return 0
            }
        }
    }
}
