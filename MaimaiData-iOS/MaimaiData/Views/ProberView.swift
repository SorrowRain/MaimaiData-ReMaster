import SwiftUI

private struct B50PreviewItem: Identifiable {
    let id = UUID()
    let image: UIImage
}

struct ProberView: View {
    @Environment(DataStore.self) private var store

    @State private var selectedTab = 0 // 0 = B35, 1 = B15
    @State private var showLoginSheet = false
    @State private var errorMessage: String?
    @State private var isRenderingB50 = false
    @State private var b50PreviewItem: B50PreviewItem?
    @State private var toast = ToastState()

    var body: some View {
        let summary = store.best35AndBest15()
        let targetList = store.sortedRecords(isNewVersion: selectedTab == 1)
        let cutoff = selectedTab == 0 ? 35 : 15

        ScrollView {
            LazyVStack(spacing: AppTheme.itemSpacing) {
                headerPickerCard(
                    b35Sum: summary.b35Rating,
                    b15Sum: summary.b15Rating
                )

                if targetList.isEmpty {
                    if !store.isLoggedIn {
                        ContentUnavailableView {
                            Label("尚未添加成绩档案", systemImage: "person.crop.circle.badge.exclamationmark")
                        } description: {
                            let sources = store.isCNDirectAvailable ? "国服直连、落雪或水鱼" : "落雪或水鱼"
                            Text("绑定\(sources)后即可查看 B35 / B15 并生成 Best50 图")
                        } actions: {
                            AppInlineButton(title: "添加档案", icon: "person.badge.key.fill") {
                                showLoginSheet = true
                            }
                        }
                        .padding(.top, 48)
                    } else {
                        ContentUnavailableView(
                            "暂无成绩数据",
                            systemImage: "tray",
                            description: Text("下拉刷新获取最新成绩")
                        )
                        .padding(.top, 48)
                    }
                } else {
                    ForEach(Array(targetList.enumerated()), id: \.element.id) { idx, record in
                        if idx == cutoff {
                            HStack(spacing: AppTheme.Space.s) {
                                VStack { AppInsetDivider() }
                                Text("以下不计入 Best\(cutoff)")
                                    .font(AppTheme.Typography.inlineAction)
                                    .foregroundStyle(.secondary)
                                VStack { AppInsetDivider() }
                            }
                            .padding(.vertical, 6)
                        }

                        let song = store.songsById[record.songId]
                        if let song {
                            NavigationLink(value: SongDetailRoute(song: song, initialDifficulty: record.difficulty)) {
                                ProberRecordRow(rank: idx + 1, record: record, song: song, region: store.serverRegion)
                            }
                            .buttonStyle(.plain)
                        } else {
                            ProberRecordRow(rank: idx + 1, record: record, song: nil, region: store.serverRegion)
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .refreshable {
            guard store.isLoggedIn else { return }
            await refreshRecords(showToastOnSuccess: true)
        }
        .appPageBackground()
        .sensoryFeedback(.selection, trigger: selectedTab)
        .statusToast(toast)
        .navigationTitle("Best 50")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button {
                    Task { await generateBest50Image(b35: summary.b35, b15: summary.b15) }
                } label: {
                    if isRenderingB50 {
                        HStack(spacing: 6) {
                            ProgressView()
                                .controlSize(.small)
                            Text("生成中…")
                                .font(AppTheme.Typography.caption)
                        }
                    } else {
                        HStack(spacing: 4) {
                            Image(systemName: "photo.on.rectangle.angled")
                            Text("生成 B50")
                        }
                        .font(AppTheme.Typography.inlineAction)
                    }
                }
                .disabled(isRenderingB50 || (summary.b35.isEmpty && summary.b15.isEmpty))
            }
        }
        .sheet(isPresented: $showLoginSheet) {
            AddIdentitySheet { summaryText in
                toast.show(summaryText, duration: 2.0)
            }
        }
        .fullScreenCover(item: $b50PreviewItem) { item in
            B50PreviewSheet(image: item.image)
        }
        .errorAlert("提示", message: $errorMessage)
        .task(id: "\(store.isLoadingLocalData)_\(store.isRestoringCachedRecords)_\(store.activeIdentityId ?? "")") {
            if !store.isLoadingLocalData &&
                !store.isRestoringCachedRecords &&
                store.records.isEmpty &&
                store.activeIdentity?.hasAnyChannel == true {
                await refreshRecords()
            }
        }
    }

    private func headerPickerCard(b35Sum: Int, b15Sum: Int) -> some View {
        AppCard(padding: AppTheme.compactCardPadding) {
            Picker("版本分区", selection: $selectedTab) {
                Text("B35 · \(b35Sum)").tag(0)
                Text("B15 · \(b15Sum)").tag(1)
            }
            .pickerStyle(.segmented)
        }
    }

    private func refreshRecords(showToastOnSuccess: Bool = false) async {
        do {
            let outcome = try await store.syncActiveIdentityDefault()
            if let warning = outcome.warningMessage {
                try? await Task.sleep(for: .milliseconds(250))
                errorMessage = warning
            } else if showToastOnSuccess {
                toast.show("成绩已同步")
            }
        } catch is CancellationError {
            return
        } catch let urlError as URLError where urlError.code == .cancelled {
            return
        } catch MaimaiAPIError.syncInProgress {
            return
        } catch MaimaiAPIError.backendError(_, let code, _, _) where code == "SYNC_IN_PROGRESS" {
            return
        } catch MaimaiAPIError.unauthorized(let detail) {
            if !store.isLoggedIn {
                try? await Task.sleep(for: .milliseconds(350))
                showLoginSheet = true
            } else {
                let msg = "成绩刷新失败：\(detail)\n已有成绩已保留，请稍后重试。"
                try? await Task.sleep(for: .milliseconds(350))
                errorMessage = msg
            }
        } catch let apiError as MaimaiAPIError {
            let msg = "成绩刷新失败：\(apiError.recoveryMessage)\n已有成绩已保留，请稍后重试。"
            try? await Task.sleep(for: .milliseconds(350))
            errorMessage = msg
        } catch {
            let msg = "成绩刷新失败：\(error.localizedDescription)\n已有成绩已保留，请稍后重试。"
            try? await Task.sleep(for: .milliseconds(350))
            errorMessage = msg
        }
    }

    private func generateBest50Image(b35: [ProberRecord], b15: [ProberRecord]) async {
        isRenderingB50 = true
        defer { isRenderingB50 = false }
        if let image = await Best50Renderer.render(
            playerName: store.activeB50PlayerName,
            songsById: store.songsById,
            b35: b35,
            b15: b15,
            region: store.serverRegion
        ) {
            b50PreviewItem = B50PreviewItem(image: image)
        } else {
            errorMessage = "无法生成 Best50 图片，请先拉取成绩"
        }
    }
}

private struct ProberRecordRow: View {
    let rank: Int
    let record: ProberRecord
    let song: SongData?
    let region: MaimaiConstants.ServerRegion

    var body: some View {
        let diff = record.difficulty
        AppCard(padding: AppTheme.compactCardPadding) {
            HStack(spacing: AppTheme.Space.m) {
                SongCoverBadgeView(
                    song: song,
                    size: .compact,
                    borderColor: diff.boardDarkColor
                )

                VStack(alignment: .leading, spacing: 6) {
                    HStack(spacing: 6) {
                        AppCapsuleLabel(
                            text: "#\(rank)",
                            tint: .secondary,
                            style: .compact
                        )

                        Text(record.title)
                            .font(AppTheme.Typography.songTitle)
                            .foregroundStyle(.primary)
                            .lineLimit(1)

                        Spacer(minLength: 4)

                        ChartTypeBadge(isDX: record.type == MaimaiConstants.chartTypeDX, compact: true)
                    }

                    HStack(spacing: 7) {
                        DifficultyLevelBadge(
                            difficulty: diff,
                            text: String(format: "%.1f", record.ds)
                        )

                        Text(String(format: "%.4f%%", record.achievements))
                            .font(AppTheme.Typography.captionMetaBold)
                            .foregroundStyle(.primary)

                        RecordStatusBadges(record: record, size: .compact, region: region)

                        Spacer(minLength: 0)

                        AppCapsuleLabel(
                            text: "Ra \(record.computedRating)",
                            tint: AppTheme.accent,
                            isNeutral: false,
                            style: .count
                        )
                    }
                }
            }
        }
    }
}

private struct B50PreviewSheet: View {
    let image: UIImage

    var body: some View {
        PhotoPreviewScaffold(
            title: "Best 50 预览",
            image: image,
            accessibilityLabel: "Best50 成绩图，可双击或双指缩放预览",
            shareAccessibilityLabel: "分享 Best50 图片"
        ) {
            EmptyView()
        }
    }
}
