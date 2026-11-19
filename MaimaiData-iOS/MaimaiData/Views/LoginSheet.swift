import SwiftUI

struct LoginSheet: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    @State private var selectedProvider: ScoreProvider = .lxns
    @State private var lxnsUsePersonalToken = false
    @State private var dfUseImportToken = false

    @State private var dfUsername: String = ""
    @State private var dfPassword: String = ""
    @State private var dfImportToken: String = ""
    @State private var lxnsPersonalToken: String = ""
    @State private var remarkName: String = ""

    @State private var webAuthRunner = WebAuthSessionRunner()
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var shouldDismissAfterAlert = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: AppTheme.cardSpacing) {
                    AppSectionCard(
                        title: "成绩来源",
                        subtitle: "成绩档案仅用于拉取并本地保存成绩单，与外面的游戏区服算分规则互不绑定"
                    ) {
                        Picker("成绩来源", selection: $selectedProvider.animation(.snappy(duration: 0.2))) {
                            ForEach(ScoreProvider.allCases) { provider in
                                Text(provider.displayName).tag(provider)
                            }
                        }
                        .pickerStyle(.segmented)
                    }

                    if selectedProvider == .lxns {
                        lxnsSection
                    } else {
                        divingFishSection
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
                .dismissKeyboardOnTap()
            }
            .scrollDismissesKeyboard(.interactively)
            .disabled(store.isLoadingLocalData || isSubmitting)
            .appPageBackground()
            .navigationTitle("添加成绩档案")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .disabled(isSubmitting)
                }
            }
            .interactiveDismissDisabled(isSubmitting)
            .errorAlert("授权或同步提示", message: $errorMessage)
            .onChange(of: errorMessage) { oldVal, newVal in
                if oldVal != nil && newVal == nil && shouldDismissAfterAlert {
                    dismiss()
                }
            }
        }
    }

    // MARK: - LXNS Section

    private var lxnsSection: some View {
        AppSectionCard(
            title: "连接落雪咖啡屋",
            subtitle: "凭据与令牌仅加密存储在设备本机 Keychain"
        ) {
            VStack(spacing: AppTheme.Space.m) {
                Picker("认证方式", selection: $lxnsUsePersonalToken.animation(.snappy(duration: 0.2))) {
                    Text("OAuth 授权").tag(false)
                    Text("个人 API 密钥").tag(true)
                }
                .pickerStyle(.segmented)

                if !lxnsUsePersonalToken {
                    AppInsetGroup(padding: AppTheme.Space.m) {
                        Text("点击下方按钮在系统安全网页中登录落雪账号并确认只读授权，授权完成后将自动跳回应用并拉取全量成绩单。")
                            .font(AppTheme.Typography.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    AppPrimaryButton(
                        title: isSubmitting ? "正在授权并同步成绩…" : "前往落雪咖啡屋授权",
                        icon: "checkmark.shield.fill",
                        isLoading: isSubmitting,
                        isDisabled: isSubmitting
                    ) {
                        KeyboardDismiss.hide()
                        Task { await submitLXNSOAuth() }
                    }
                } else {
                    AppSecureField(
                        title: "个人 API 密钥",
                        placeholder: "粘贴落雪「账号详情」生成的个人 API 密钥",
                        text: $lxnsPersonalToken
                    )
                    .submitLabel(.done)
                    .onSubmit {
                        if !isSubmitting && !lxnsPersonalToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            KeyboardDismiss.hide()
                            Task { await submitLXNSPersonalToken() }
                        }
                    }

                    AppTextField(
                        title: "备注名称（选填）",
                        placeholder: "未同步过游戏数据时的备用名称",
                        text: $remarkName
                    )
                    .submitLabel(.done)
                    .onSubmit {
                        if !isSubmitting && !lxnsPersonalToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            KeyboardDismiss.hide()
                            Task { await submitLXNSPersonalToken() }
                        }
                    }

                    AppPrimaryButton(
                        title: isSubmitting ? "正在验证并同步成绩…" : "验证并保存档案",
                        icon: "arrow.down.circle.fill",
                        isLoading: isSubmitting,
                        isDisabled: isSubmitting || lxnsPersonalToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ) {
                        KeyboardDismiss.hide()
                        Task { await submitLXNSPersonalToken() }
                    }
                    .padding(.top, AppTheme.Space.xs)
                }
            }
        }
    }

    // MARK: - Diving-Fish Section

    private var divingFishSection: some View {
        AppSectionCard(
            title: "连接水鱼查分器",
            subtitle: "凭据仅加密存储在设备本机 Keychain"
        ) {
            VStack(spacing: AppTheme.Space.m) {
                Picker("认证方式", selection: $dfUseImportToken.animation(.snappy(duration: 0.2))) {
                    Text("账号密码").tag(false)
                    Text("成绩导入 Token").tag(true)
                }
                .pickerStyle(.segmented)

                if !dfUseImportToken {
                    AppTextField(
                        title: "用户名",
                        placeholder: "输入水鱼用户名",
                        text: $dfUsername
                    )
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .textContentType(.username)
                    .submitLabel(.next)

                    AppSecureField(
                        title: "密码",
                        placeholder: "输入水鱼密码",
                        text: $dfPassword
                    )
                    .submitLabel(.done)
                    .onSubmit {
                        if !isSubmitting
                            && !dfUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            && !dfPassword.isEmpty {
                            KeyboardDismiss.hide()
                            Task { await submitDivingFishPassword() }
                        }
                    }

                    AppPrimaryButton(
                        title: isSubmitting ? "正在登录并同步成绩…" : "登录并保存档案",
                        icon: "arrow.right.circle.fill",
                        isLoading: isSubmitting,
                        isDisabled: isSubmitting
                            || dfUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            || dfPassword.isEmpty
                    ) {
                        KeyboardDismiss.hide()
                        Task { await submitDivingFishPassword() }
                    }
                    .padding(.top, AppTheme.Space.xs)
                } else {
                    AppSecureField(
                        title: "成绩导入 Token",
                        placeholder: "粘贴水鱼「编辑个人资料」生成的成绩导入 Token",
                        text: $dfImportToken
                    )
                    .submitLabel(.done)
                    .onSubmit {
                        if !isSubmitting && !dfImportToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            KeyboardDismiss.hide()
                            Task { await submitDivingFishToken() }
                        }
                    }

                    AppInsetGroup(padding: AppTheme.Space.m) {
                        Text("无需输入水鱼账号密码，直接通过 Import-Token 拉取完整成绩单与查分器昵称。")
                            .font(AppTheme.Typography.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    AppPrimaryButton(
                        title: isSubmitting ? "正在验证并同步成绩…" : "验证并保存档案",
                        icon: "arrow.down.circle.fill",
                        isLoading: isSubmitting,
                        isDisabled: isSubmitting || dfImportToken.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                    ) {
                        KeyboardDismiss.hide()
                        Task { await submitDivingFishToken() }
                    }
                    .padding(.top, AppTheme.Space.xs)
                }
            }
        }
    }

    // MARK: - Actions

    private func submitLXNSOAuth() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectLXNSOAuth(using: webAuthRunner)
            dismiss()
        } catch WebAuthSessionError.cancelled {
            return
        } catch let error as MaimaiAPIError {
            if case .recordSyncFailedAfterLogin = error {
                shouldDismissAfterAlert = true
            }
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "落雪授权失败：\(error.localizedDescription)"
        }
    }

    private func submitLXNSPersonalToken() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectLXNSPersonalToken(lxnsPersonalToken, remarkName: remarkName)
            dismiss()
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "验证失败：\(error.localizedDescription)\n请检查网络连接或个人密钥是否正确。"
        }
    }

    private func submitDivingFishPassword() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectDivingFishPassword(username: dfUsername, password: dfPassword)
            dismiss()
        } catch let error as MaimaiAPIError {
            if case .recordSyncFailedAfterLogin = error {
                shouldDismissAfterAlert = true
            }
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "登录失败：\(error.localizedDescription)\n请检查网络连接后重试。"
        }
    }

    private func submitDivingFishToken() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectDivingFishImportToken(dfImportToken)
            dismiss()
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "验证失败：\(error.localizedDescription)\n请检查导入 Token 是否正确。"
        }
    }
}
