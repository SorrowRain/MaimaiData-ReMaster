import SwiftUI

struct RatingToolsView: View {
    @Environment(DataStore.self) private var store

    @State private var path = NavigationPath()
    @State private var toast = ToastState()
    @State private var syncErrorMessage: String?

    private enum ToolRoute: Hashable {
        case prober
        case versionCheck
        case levelCheck
        case calculator
        case finaleToDx
        case accountManagement
    }

    var body: some View {
        NavigationStack(path: $path) {
            ScrollView {
                VStack(spacing: AppTheme.cardSpacing) {
                    ratingSummaryCard
                    recordsAndProgressSection
                    calculatorsSection
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
            }
            .appPageBackground()
            .navigationTitle("Rating 与查分")
            .navigationBarTitleDisplayMode(.inline)
            .statusToast(toast)
            .errorAlert("同步失败", message: $syncErrorMessage)
            .navigationDestination(for: ToolRoute.self) { route in
                switch route {
                case .prober:
                    ProberView()
                case .versionCheck:
                    VersionCheckView()
                case .levelCheck:
                    LevelCheckView()
                case .calculator:
                    RatingCalculatorView()
                case .finaleToDx:
                    FinaleToDxView()
                case .accountManagement:
                    AccountManagementView()
                }
            }
            .navigationDestination(for: SongDetailRoute.self) { route in
                SongDetailView(song: route.song, initialDifficulty: route.initialDifficulty)
            }
            .navigationDestination(for: SongData.self) { song in
                SongDetailView(song: song)
            }
        }
    }

    // MARK: - 1. Player & Rating Summary Card

    private var ratingSummaryCard: some View {
        let summary = store.best35AndBest15()
        let isLoggedIn = store.isLoggedIn

        return AppCard(padding: AppTheme.cardPadding, tint: AppTheme.accent) {
            VStack(alignment: .leading, spacing: AppTheme.Space.m) {
                HStack(alignment: .center, spacing: AppTheme.Space.s) {
                    Text(store.activeProfile != nil ? store.displayPlayerName : "未添加档案")
                        .font(AppTheme.Typography.cardTitle)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .transaction { $0.animation = nil }

                    Spacer(minLength: 4)

                    if isLoggedIn {
                        AppInlineButton(
                            title: "同步成绩",
                            icon: "arrow.clockwise",
                            isLoading: store.isSyncingRecords
                        ) {
                            Task { await syncRecords() }
                        }
                    } else {
                        AppInlineButton(
                            title: "添加档案",
                            icon: "person.crop.circle.badge.plus"
                        ) {
                            path.append(ToolRoute.accountManagement)
                        }
                    }
                }

                Button {
                    path.append(isLoggedIn ? ToolRoute.prober : ToolRoute.accountManagement)
                } label: {
                    HStack(spacing: AppTheme.Space.s) {
                        AppMetricTile(
                            title: "总 Rating",
                            value: isLoggedIn ? "\(summary.totalRating)" : "—",
                            highlight: isLoggedIn
                        )
                        AppMetricTile(
                            title: "旧版本 B35",
                            value: isLoggedIn ? "\(summary.b35Rating)" : "—"
                        )
                        AppMetricTile(
                            title: "现行版本 B15",
                            value: isLoggedIn ? "\(summary.b15Rating)" : "—"
                        )
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isLoggedIn ? "查看 Best 50 成绩详情" : "前往添加成绩档案")
            }
        }
    }

    private func syncRecords() async {
        do {
            try await store.syncActiveProfileRecords()
            toast.show("成绩已同步", duration: 1.8)
        } catch let error as MaimaiAPIError {
            syncErrorMessage = error.recoveryMessage
        } catch {
            syncErrorMessage = error.localizedDescription
        }
    }

    // MARK: - 2. Records & Plate Progress Section

    private var recordsAndProgressSection: some View {
        AppSectionCard(title: "成绩与牌子进度") {
            AppInsetGroup {
                VStack(spacing: 0) {
                    NavigationLink(value: ToolRoute.prober) {
                        AppInsetRow(
                            icon: "trophy.fill",
                            iconColor: AppTheme.accent,
                            title: "Best 50"
                        )
                    }
                    .buttonStyle(.plain)

                    Divider()

                    NavigationLink(value: ToolRoute.versionCheck) {
                        AppInsetRow(
                            icon: "square.grid.3x3.fill",
                            iconColor: AppTheme.Palette.ratingPurple,
                            title: "牌子进度表"
                        )
                    }
                    .buttonStyle(.plain)

                    Divider()

                    NavigationLink(value: ToolRoute.levelCheck) {
                        AppInsetRow(
                            icon: "slider.horizontal.3",
                            iconColor: AppTheme.Palette.ratingOrange,
                            title: "等级进度表"
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - 3. Calculators Section

    private var calculatorsSection: some View {
        AppSectionCard(title: "算分与推分工具") {
            AppInsetGroup {
                VStack(spacing: 0) {
                    NavigationLink(value: ToolRoute.calculator) {
                        AppInsetRow(
                            icon: "function",
                            iconColor: AppTheme.Palette.ratingBlue,
                            title: "Rating 计算器"
                        )
                    }
                    .buttonStyle(.plain)

                    Divider()

                    NavigationLink(value: ToolRoute.finaleToDx) {
                        AppInsetRow(
                            icon: "arrow.left.arrow.right",
                            iconColor: AppTheme.Palette.noteSlide,
                            title: "旧框达成率换算"
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}
