import SwiftUI

struct AccountManagementView: View {
    @Environment(DataStore.self) private var store

    @State private var showAddProfileSheet = false
    @State private var pendingDeleteProfile: ScoreProfile?
    @State private var toast = ToastState()
    @State private var errorMessage: String?

    private var isPendingDeleteActive: Bool {
        guard let target = pendingDeleteProfile else { return false }
        return target.id == store.activeProfileId
    }

    var body: some View {
        @Bindable var storeBindable = store

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppSectionCard(title: "成绩档案") {
                    if store.activeProfile != nil {
                        AppInlineButton(
                            title: "同步当前",
                            icon: "arrow.clockwise",
                            isLoading: store.isSyncingRecords
                        ) {
                            Task { await syncActiveProfile() }
                        }
                    }
                } content: {
                    if store.profiles.isEmpty {
                        AppInsetGroup(padding: AppTheme.cardPadding) {
                            Text("暂无成绩档案，点击下方按钮选择落雪或水鱼添加")
                                .font(AppTheme.Typography.caption)
                                .foregroundStyle(.secondary)
                        }
                    } else {
                        AppInsetGroup {
                            VStack(spacing: 0) {
                                ForEach(Array(store.profiles.enumerated()), id: \.element.id) { idx, profile in
                                    if idx > 0 {
                                        Divider()
                                    }
                                    profileRow(profile)
                                }
                            }
                        }
                    }
                }

                AppPrimaryButton(
                    title: "添加成绩档案",
                    icon: "plus.circle.fill",
                    isDisabled: store.isSyncingRecords
                ) {
                    showAddProfileSheet = true
                }

                AppSectionCard(title: "B50 图片玩家名称") {
                    VStack(spacing: AppTheme.itemSpacing) {
                        AppInsetGroup {
                            AppInsetToggleRow(
                                title: "使用查分器返回昵称",
                                isOn: $storeBindable.useProberNickname.animation(.snappy(duration: 0.2))
                            )
                        }

                        if !store.useProberNickname {
                            AppTextField(
                                placeholder: "输入自定义显示的玩家昵称",
                                text: $storeBindable.customNickname
                            )
                            .submitLabel(.done)
                            .transition(.opacity.combined(with: .move(edge: .top)))
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
            .dismissKeyboardOnTap()
        }
        .scrollDismissesKeyboard(.interactively)
        .appPageBackground()
        .sensoryFeedback(.selection, trigger: store.activeProfileId)
        .navigationTitle("成绩档案")
        .navigationBarTitleDisplayMode(.inline)
        .statusToast(toast)
        .sheet(isPresented: $showAddProfileSheet) {
            LoginSheet()
        }
        .alert(
            isPendingDeleteActive ? "移除当前成绩档案" : "移除成绩档案",
            isPresented: Binding(
                get: { pendingDeleteProfile != nil },
                set: { if !$0 { pendingDeleteProfile = nil } }
            ),
            presenting: pendingDeleteProfile
        ) { profile in
            Button("移除", role: .destructive) {
                store.removeProfile(id: profile.id)
                pendingDeleteProfile = nil
                toast.show("已移除 \(profile.displayTitle)", duration: 1.8)
            }
            Button("取消", role: .cancel) {
                pendingDeleteProfile = nil
            }
        } message: { profile in
            if profile.id == store.activeProfileId {
                Text("确定要移除当前使用的成绩档案「\(profile.displayTitle)」及其本地缓存成绩吗？")
            } else {
                Text("确定要移除成绩档案「\(profile.displayTitle)」及其本地缓存成绩吗？")
            }
        }
        .errorAlert("同步失败", message: $errorMessage)
    }

    private func profileRow(_ profile: ScoreProfile) -> some View {
        let isCurrent = (profile.id == store.activeProfileId)

        return HStack(spacing: AppTheme.Space.xs) {
            Button {
                guard !isCurrent else { return }
                store.switchProfile(to: profile.id)
                toast.show("已切换至 \(profile.displayTitle)", duration: 1.6)
            } label: {
                ScoreProfileRowLabel(profile: profile, isSelected: isCurrent)
                    .frame(maxWidth: .infinity, minHeight: AppTheme.rowMinHeight, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

            Button {
                pendingDeleteProfile = profile
            } label: {
                Image(systemName: "trash")
                    .font(AppTheme.Typography.body)
                    .foregroundStyle(AppTheme.destructive)
                    .frame(width: 34, height: 34)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(store.isSyncingRecords)
            .accessibilityLabel("移除成绩档案 \(profile.displayTitle)")
        }
        .padding(.leading, AppTheme.Space.m)
        .padding(.trailing, AppTheme.Space.s)
        .appInsetRowHighlight(isCurrent)
        .contextMenu {
            Button(role: .destructive) {
                pendingDeleteProfile = profile
            } label: {
                Label("移除档案", systemImage: "trash")
            }
            .disabled(store.isSyncingRecords)
        }
    }

    private func syncActiveProfile() async {
        do {
            try await store.syncActiveProfileRecords()
            toast.show("成绩已同步", duration: 1.8)
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "同步失败：\(error.localizedDescription)"
        }
    }
}
