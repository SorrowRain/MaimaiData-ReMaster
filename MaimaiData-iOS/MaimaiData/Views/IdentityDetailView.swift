import SwiftUI

struct IdentityDetailRoute: Hashable {
    let identityId: String
}

struct IdentityDetailView: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    let identityId: String

    @State private var rebindChannel: SyncChannelKind?
    @State private var showFullSyncSheet = false
    @State private var pendingUnbindChannel: SyncChannelKind?
    @State private var showDeleteConfirm = false
    @State private var b50NameDraft = ""

    @State private var toast = ToastState()
    @State private var alertTitle = "操作提示"
    @State private var errorMessage: String?

    private var normalizedId: String { identityId.lowercased() }

    private var identity: PlayerIdentity? {
        store.identities.first(where: { $0.id == normalizedId })
    }

    private var isMultiProfile: Bool {
        store.identities.count > 1
    }

    private var isCurrent: Bool {
        store.activeIdentityId == normalizedId
    }

    var body: some View {
        ScrollView {
            if let identity {
                VStack(spacing: AppTheme.cardSpacing) {
                    summaryCard(identity)
                    syncSection(identity)
                    channelsSection(identity)
                    b50NameSection(identity)

                    AppSecondaryButton(
                        title: "移除此档案",
                        tint: AppTheme.destructive,
                        isDisabled: store.isSyncingRecords || store.credentialStoreLoadFailed
                    ) {
                        showDeleteConfirm = true
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
                .dismissKeyboardOnTap()
            }
        }
        .scrollDismissesKeyboard(.interactively)
        .appPageBackground()
        .sensoryFeedback(.selection, trigger: store.activeIdentityId)
        .navigationTitle("档案详情")
        .navigationBarTitleDisplayMode(.inline)
        .statusToast(toast)
        .onAppear {
            if let identity {
                b50NameDraft = identity.customNickname
            } else {
                dismiss()
            }
        }
        .onDisappear {
            store.updateCustomNickname(b50NameDraft, for: normalizedId)
        }
        .onChange(of: identity == nil) { _, missing in
            if missing { dismiss() }
        }
        .sheet(item: $rebindChannel) { channel in
            ChannelBindSheet(channel: channel, targetIdentityId: normalizedId) { summaryText in
                toast.show(summaryText, duration: 2.0)
            }
        }
        .sheet(isPresented: $showFullSyncSheet) {
            if let identity {
                FullSyncSheet(identity: identity) { summaryText in
                    toast.show(summaryText, duration: 2.2)
                }
            }
        }
        .alert(
            "解除 \(pendingUnbindChannel?.displayName ?? "") 绑定",
            isPresented: Binding(
                get: { pendingUnbindChannel != nil },
                set: { if !$0 { pendingUnbindChannel = nil } }
            ),
            presenting: pendingUnbindChannel
        ) { channel in
            Button("解除绑定", role: .destructive) {
                pendingUnbindChannel = nil
                if let identity {
                    Task { await runUnbind(channel, from: identity) }
                }
            }
            Button("取消", role: .cancel) {
                pendingUnbindChannel = nil
            }
        } message: { channel in
            if channel == .selfHosted {
                Text("解除后，本地缓存成绩会保留；再次绑定需要重新获取微信玩家二维码。")
            } else {
                Text("确定要解除此档案的 \(channel.displayName) 绑定吗？本地缓存成绩会保留。")
            }
        }
        .alert(
            "移除此档案",
            isPresented: $showDeleteConfirm
        ) {
            Button("移除", role: .destructive) {
                store.removeIdentity(id: normalizedId)
                dismiss()
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("确定要移除玩家档案「\(identity?.primaryDisplayName ?? "")」、已绑定凭据及本地缓存成绩吗？")
        }
        .errorAlert(alertTitle, message: $errorMessage)
    }

    // MARK: - Sections

    private func summaryCard(_ identity: PlayerIdentity) -> some View {
        AppCard(padding: AppTheme.cardPadding, tint: AppTheme.accent) {
            VStack(alignment: .leading, spacing: AppTheme.Space.xs) {
                HStack(alignment: .center, spacing: AppTheme.Space.s) {
                    Text(identity.primaryDisplayName)
                        .font(AppTheme.Typography.cardTitle)
                        .foregroundStyle(.primary)
                        .lineLimit(1)
                        .transaction { $0.animation = nil }

                    Spacer(minLength: AppTheme.Space.s)

                    if isMultiProfile {
                        if isCurrent {
                            AppCapsuleLabel(text: "当前", style: .compact)
                        } else {
                            AppInlineButton(
                                title: "设为当前",
                                isDisabled: store.isSyncingRecords
                            ) {
                                store.switchIdentity(to: identity.id)
                                toast.show("已切换至 \(identity.primaryDisplayName)", duration: 1.6)
                            }
                            .accessibilityLabel("将 \(identity.primaryDisplayName) 设为当前档案")
                        }
                    }
                }

                Text(identity.lastSyncText() ?? "尚未同步")
                    .font(AppTheme.Typography.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private func syncSection(_ identity: PlayerIdentity) -> some View {
        AppSectionCard(title: "同步成绩") {
            if !identity.hasAnyChannel {
                AppInsetGroup(padding: AppTheme.cardPadding) {
                    Text("先在下方绑定一个账号")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                AppInsetGroup {
                    VStack(spacing: 0) {
                        switch identity.syncPlan {
                        case .cnDirectQuick:
                            AppInsetRow(
                                title: "快速同步",
                                subtitle: "仅更新达成率与 DX 分数"
                            ) {
                                AppInlineButton(
                                    title: "同步",
                                    isLoading: store.isSyncing(identityId: identity.id, operation: .quickSync),
                                    isDisabled: store.isSyncingRecords || store.isLoadingLocalData || !store.isCNDirectAvailable
                                ) {
                                    Task { await runQuickSync(identity) }
                                }
                                .accessibilityLabel(
                                    store.isSyncing(identityId: identity.id, operation: .quickSync)
                                        ? "正在同步" : "快速同步"
                                )
                            }

                            AppInsetDivider()

                            AppInsetRow(
                                title: "完整同步",
                                subtitle: "更新全量数据，需获取玩家二维码"
                            ) {
                                AppInlineButton(
                                    title: "同步",
                                    isLoading: store.isSyncing(identityId: identity.id, operation: .fullSync),
                                    isDisabled: store.isSyncingRecords || store.isLoadingLocalData || !store.isCNDirectAvailable
                                ) {
                                    showFullSyncSheet = true
                                }
                                .accessibilityLabel(
                                    store.isSyncing(identityId: identity.id, operation: .fullSync)
                                        ? "正在同步" : "完整同步"
                                )
                            }

                        case .singleProber(.lxns):
                            AppInsetRow(
                                title: "同步成绩",
                                subtitle: "从落雪咖啡屋获取"
                            ) {
                                AppInlineButton(
                                    title: "同步",
                                    isLoading: store.isSyncing(identityId: identity.id, operation: .pullLXNS),
                                    isDisabled: store.isSyncingRecords || store.isLoadingLocalData
                                ) {
                                    Task { await runImportLXNS(identity) }
                                }
                                .accessibilityLabel(
                                    store.isSyncing(identityId: identity.id, operation: .pullLXNS)
                                        ? "正在同步" : "同步成绩"
                                )
                            }

                        case .singleProber(.divingFish):
                            AppInsetRow(
                                title: "同步成绩",
                                subtitle: "从水鱼查分器获取"
                            ) {
                                AppInlineButton(
                                    title: "同步",
                                    isLoading: store.isSyncing(identityId: identity.id, operation: .pullDivingFish),
                                    isDisabled: store.isSyncingRecords || store.isLoadingLocalData
                                ) {
                                    Task { await runImportDivingFish(identity) }
                                }
                                .accessibilityLabel(
                                    store.isSyncing(identityId: identity.id, operation: .pullDivingFish)
                                        ? "正在同步" : "同步成绩"
                                )
                            }

                        case .mergedProbers:
                            AppInsetRow(
                                title: "同步成绩",
                                subtitle: "拉取并合并落雪与水鱼的最佳成绩"
                            ) {
                                AppInlineButton(
                                    title: "同步",
                                    isLoading: store.isSyncing(identityId: identity.id, operation: .pullMerged),
                                    isDisabled: store.isSyncingRecords || store.isLoadingLocalData
                                ) {
                                    Task { await runMergedSync(identity) }
                                }
                                .accessibilityLabel(
                                    store.isSyncing(identityId: identity.id, operation: .pullMerged)
                                        ? "正在同步" : "合并同步成绩"
                                )
                            }

                        case .singleProber, .none:
                            EmptyView()
                        }

                        if identity.canUploadToProbers {
                            AppInsetDivider()

                            AppInsetRow(
                                title: "上传到查分器",
                                subtitle: "将本地成绩同步至查分器"
                            ) {
                                AppInlineButton(
                                    title: "上传",
                                    tint: AppTheme.Palette.statFC,
                                    isLoading: store.isSyncing(identityId: identity.id, operation: .writeback),
                                    isDisabled: store.isSyncingRecords || store.isLoadingLocalData
                                ) {
                                    Task { await runUpload(identity) }
                                }
                                .accessibilityLabel(
                                    store.isSyncing(identityId: identity.id, operation: .writeback)
                                        ? "正在上传" : "上传到查分器"
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    private func channelsSection(_ identity: PlayerIdentity) -> some View {
        AppSectionCard(title: "账号绑定") {
            AppInsetGroup {
                VStack(spacing: 0) {
                    ForEach(Array(SyncChannelKind.allCases.enumerated()), id: \.element) { idx, channel in
                        if idx > 0 {
                            AppInsetDivider()
                        }
                        channelRow(channel, for: identity)
                    }
                }
            }
        }
    }

    private func channelRow(_ channel: SyncChannelKind, for identity: PlayerIdentity) -> some View {
        let status = identity.bindingStatus(for: channel, cnDirectAvailable: store.isCNDirectAvailable)
        let disabled = store.isSyncingRecords || store.credentialStoreLoadFailed
        let subtitle: String = switch status {
        case .unavailable: "此版本暂不可用"
        case .unbound: "未绑定"
        case .bound: "已绑定"
        case .needsReauth: "仅读取权限，无法上传"
        }

        return AppInsetRow(title: channel.displayName, subtitle: subtitle) {
            switch status {
            case .unavailable:
                EmptyView()
            case .unbound:
                AppInlineButton(
                    title: "绑定",
                    isDisabled: disabled
                ) {
                    rebindChannel = channel
                }
                .accessibilityLabel("绑定 \(channel.displayName)")
            case .bound:
                AppInlineButton(
                    title: "解绑",
                    tint: AppTheme.destructive,
                    isDisabled: disabled
                ) {
                    pendingUnbindChannel = channel
                }
                .accessibilityLabel("解绑 \(channel.displayName)")
            case .needsReauth:
                HStack(spacing: AppTheme.Space.s) {
                    AppInlineButton(
                        title: "重新授权",
                        tint: AppTheme.warning,
                        isDisabled: disabled
                    ) {
                        rebindChannel = channel
                    }
                    .accessibilityLabel("重新授权 \(channel.displayName)")

                    AppInlineButton(
                        title: "解绑",
                        tint: AppTheme.destructive,
                        isDisabled: disabled
                    ) {
                        pendingUnbindChannel = channel
                    }
                    .accessibilityLabel("解绑 \(channel.displayName)")
                }
            }
        }
    }

    private func b50NameSection(_ identity: PlayerIdentity) -> some View {
        AppSectionCard(title: "B50 图片玩家名称") {
            AppTextField(
                placeholder: identity.primaryDisplayName,
                text: $b50NameDraft
            )
            .submitLabel(.done)
            .disabled(store.credentialStoreLoadFailed)
            .opacity(store.credentialStoreLoadFailed ? 0.55 : 1)
            .onSubmit {
                store.updateCustomNickname(b50NameDraft, for: identity.id)
                KeyboardDismiss.hide()
            }
        }
    }

    // MARK: - Actions

    private func runAction(failureTitle: String, operation: () async throws -> Void) async {
        do {
            try await operation()
        } catch is CancellationError {
            return
        } catch let error as MaimaiAPIError {
            alertTitle = failureTitle
            errorMessage = error.recoveryMessage
        } catch {
            alertTitle = failureTitle
            errorMessage = error.localizedDescription
        }
    }

    private func runQuickSync(_ identity: PlayerIdentity) async {
        await runAction(failureTitle: "同步失败") {
            _ = try await store.quickSyncSelfHosted(identityId: identity.id, writeback: false)
            toast.show("成绩已同步", duration: 1.8)
        }
    }

    private func runImportLXNS(_ identity: PlayerIdentity) async {
        await runAction(failureTitle: "同步失败") {
            try await store.pullFromLXNS(identityId: identity.id)
            toast.show("成绩已同步", duration: 1.8)
        }
    }

    private func runImportDivingFish(_ identity: PlayerIdentity) async {
        await runAction(failureTitle: "同步失败") {
            try await store.pullFromDivingFish(identityId: identity.id)
            toast.show("成绩已同步", duration: 1.8)
        }
    }

    private func runMergedSync(_ identity: PlayerIdentity) async {
        await runAction(failureTitle: "同步失败") {
            let outcome = try await store.pullMergedProbers(identityId: identity.id)
            if let warning = outcome.warningMessage {
                alertTitle = "部分同步成功"
                errorMessage = warning
            } else {
                toast.show("成绩已同步", duration: 1.8)
            }
        }
    }

    private func runUpload(_ identity: PlayerIdentity) async {
        await runAction(failureTitle: "上传失败") {
            let summary = try await store.writebackToBoundProbers(identityId: identity.id)
            if summary.results.isEmpty {
                toast.show("未绑定可上传的查分器", duration: 1.8)
            } else if summary.isAllSucceeded {
                toast.show("上传成功", duration: 1.8)
            } else {
                alertTitle = summary.hasAnySucceeded ? "部分上传失败" : "上传失败"
                errorMessage = summary.summaryText
            }
        }
    }

    private func runUnbind(_ channel: SyncChannelKind, from identity: PlayerIdentity) async {
        await runAction(failureTitle: "解除绑定失败") {
            try await store.unbindChannel(channel, from: identity.id)
            toast.show("已解绑 \(channel.displayName)", duration: 1.6)
        }
    }
}
