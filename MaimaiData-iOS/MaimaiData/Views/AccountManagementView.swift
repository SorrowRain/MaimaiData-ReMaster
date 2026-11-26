import SwiftUI

struct AccountManagementView: View {
    @Environment(DataStore.self) private var store

    @State private var showAddSheet = false
    @State private var showResetConfirm = false
    @State private var toast = ToastState()

    private var isMultiProfile: Bool {
        store.identities.count > 1
    }

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                if store.credentialStoreLoadFailed {
                    credentialStoreWarningBanner
                }

                identitiesSection

                AppPrimaryButton(
                    title: "添加档案",
                    icon: "plus.circle.fill",
                    isDisabled: store.isSyncingRecords || store.credentialStoreLoadFailed
                ) {
                    showAddSheet = true
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .sensoryFeedback(.selection, trigger: store.activeIdentityId)
        .navigationTitle("成绩档案")
        .navigationBarTitleDisplayMode(.inline)
        .statusToast(toast)
        .sheet(isPresented: $showAddSheet) {
            AddIdentitySheet { summaryText in
                toast.show(summaryText, duration: 2.0)
            }
        }
        .alert("重置安全存储档案", isPresented: $showResetConfirm) {
            Button("重置档案", role: .destructive) {
                store.resetCredentialStoreAfterLoadFailure()
                toast.show("已重置档案存储，请重新绑定账号", duration: 2.2)
            }
            Button("取消", role: .cancel) {}
        } message: {
            Text("将清空无法解码的旧档案索引记录，以便重新添加账号。此操作无法撤销。")
        }
    }

    private var credentialStoreWarningBanner: some View {
        AppCard(padding: AppTheme.cardPadding, tint: AppTheme.warning) {
            VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                HStack(alignment: .center, spacing: AppTheme.Space.s) {
                    Text("安全存储读取受限")
                        .font(AppTheme.Typography.songTitle)
                        .foregroundStyle(AppTheme.warning)
                    Spacer(minLength: AppTheme.Space.xs)
                    AppInlineButton(
                        title: "重置档案",
                        tint: AppTheme.destructive,
                        isDisabled: store.isSyncingRecords
                    ) {
                        showResetConfirm = true
                    }
                }
                Text("当前未能解码或读取 Keychain 中的玩家档案。为避免数据被覆盖，已暂时锁定修改操作；请重启应用或解锁设备后重试，如仍无法恢复可点击重置档案。")
                    .font(AppTheme.Typography.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
    }

    private var identitiesSection: some View {
        AppSectionCard(title: "成绩档案") {
            if store.identities.isEmpty {
                AppInsetGroup(padding: AppTheme.cardPadding) {
                    Text("还没有成绩档案，点击下方按钮添加")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                }
            } else {
                AppInsetGroup {
                    VStack(spacing: 0) {
                        ForEach(Array(store.identities.enumerated()), id: \.element.id) { idx, identity in
                            if idx > 0 {
                                AppInsetDivider()
                            }
                            identityRow(identity)
                        }
                    }
                }
            }
        }
    }

    private func identityRow(_ identity: PlayerIdentity) -> some View {
        let isCurrent = isMultiProfile && (identity.id == store.activeIdentityId)
        let showSwitchButton = isMultiProfile && (identity.id != store.activeIdentityId)

        return NavigationLink(value: IdentityDetailRoute(identityId: identity.id)) {
            AppInsetRow(
                title: identity.primaryDisplayName,
                subtitle: identity.boundChannelsSummary,
                showsChevron: true
            ) {
                if isCurrent {
                    AppCapsuleLabel(text: "当前", style: .compact)
                } else if showSwitchButton {
                    AppInlineButton(title: "设为当前") {}
                        .hidden()
                        .accessibilityHidden(true)
                }
            }
        }
        .buttonStyle(.plain)
        .overlay(alignment: .trailing) {
            if showSwitchButton {
                HStack(spacing: AppTheme.Space.m) {
                    AppInlineButton(
                        title: "设为当前",
                        isDisabled: store.isSyncingRecords
                    ) {
                        store.switchIdentity(to: identity.id)
                        toast.show("已切换至 \(identity.primaryDisplayName)", duration: 1.6)
                    }
                    .accessibilityLabel("将 \(identity.primaryDisplayName) 设为当前档案")

                    Image(systemName: "chevron.right")
                        .font(AppTheme.Typography.captionBold)
                        .opacity(0)
                        .allowsHitTesting(false)
                        .accessibilityHidden(true)
                }
                .padding(.trailing, AppTheme.Space.m)
            }
        }
        .appInsetRowHighlight(isCurrent)
        .accessibilityAddTraits(isCurrent ? .isSelected : [])
    }
}
