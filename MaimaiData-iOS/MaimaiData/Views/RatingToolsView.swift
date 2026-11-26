import SwiftUI

struct RatingToolsView: View {
    @Environment(DataStore.self) private var store

    @State private var path = NavigationPath()
    @State private var toast = ToastState()
    @State private var syncAlertTitle = "同步失败"
    @State private var syncErrorMessage: String?
    @State private var showAddSheet = false

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
            .errorAlert(syncAlertTitle, message: $syncErrorMessage)
            .sheet(isPresented: $showAddSheet) {
                AddIdentitySheet { summaryText in
                    toast.show(summaryText, duration: 2.0)
                }
            }
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
            .navigationDestination(for: IdentityDetailRoute.self) { route in
                IdentityDetailView(identityId: route.identityId)
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
                    if store.identities.count == 1, let identity = store.activeIdentity {
                        NavigationLink(value: IdentityDetailRoute(identityId: identity.id)) {
                            playerNameHeaderView(name: store.displayPlayerName)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("查看 \(store.displayPlayerName) 档案详情")
                    } else if store.activeIdentity != nil {
                        NavigationLink(value: ToolRoute.accountManagement) {
                            playerNameHeaderView(name: store.displayPlayerName)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("切换或管理成绩档案")
                    } else {
                        Text("未添加档案")
                            .font(AppTheme.Typography.cardTitle)
                            .foregroundStyle(.primary)
                            .lineLimit(1)
                            .transaction { $0.animation = nil }
                    }

                    Spacer(minLength: AppTheme.Space.xs)

                    if let identity = store.activeIdentity {
                        if identity.hasAnyChannel {
                            AppInlineButton(
                                title: "同步成绩",
                                isLoading: store.isSyncingRecords,
                                isDisabled: store.isLoadingLocalData
                            ) {
                                Task { await syncRecords() }
                            }
                        } else {
                            AppInlineButton(title: "绑定账号") {
                                path.append(IdentityDetailRoute(identityId: identity.id))
                            }
                        }
                    } else {
                        AppInlineButton(title: "添加档案") {
                            showAddSheet = true
                        }
                    }
                }

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
            }
        }
    }

    private func playerNameHeaderView(name: String) -> some View {
        HStack(spacing: AppTheme.Space.xs) {
            Text(name)
                .font(AppTheme.Typography.cardTitle)
                .foregroundStyle(.primary)
                .lineLimit(1)
                .transaction { $0.animation = nil }
            Image(systemName: "chevron.right")
                .font(AppTheme.Typography.captionBold)
                .foregroundStyle(.tertiary)
        }
        .padding(.vertical, 2)
        .contentShape(Rectangle())
    }

    private func syncRecords() async {
        do {
            let outcome = try await store.syncActiveIdentityDefault()
            if let warning = outcome.warningMessage {
                syncAlertTitle = "部分同步成功"
                syncErrorMessage = warning
            } else {
                toast.show("成绩已同步", duration: 1.8)
            }
        } catch let error as MaimaiAPIError {
            syncAlertTitle = "同步失败"
            syncErrorMessage = error.recoveryMessage
        } catch {
            syncAlertTitle = "同步失败"
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

                    AppInsetDivider()

                    NavigationLink(value: ToolRoute.versionCheck) {
                        AppInsetRow(
                            icon: "square.grid.3x3.fill",
                            iconColor: AppTheme.Palette.ratingPurple,
                            title: "牌子进度表"
                        )
                    }
                    .buttonStyle(.plain)

                    AppInsetDivider()

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
                NavigationLink(value: ToolRoute.calculator) {
                    AppInsetRow(
                        icon: "function",
                        iconColor: AppTheme.Palette.ratingBlue,
                        title: "Rating 计算器"
                    )
                }
                .buttonStyle(.plain)
            }
        }
    }
}
