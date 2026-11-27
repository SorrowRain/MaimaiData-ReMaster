import SwiftUI
import UIKit

private enum ChannelSecondaryAuthRoute: Hashable {
    case lxnsPersonalToken
    case divingFishImportToken
}

/// Sheet for creating a new profile: chooses the score source first, then pushes the single-channel form.
struct AddIdentitySheet: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    var onCompleted: (String) -> Void = { _ in }

    @State private var isSubmitting = false

    private var availableChannels: [SyncChannelKind] {
        SyncChannelKind.allCases.filter {
            $0 != .selfHosted || store.isCNDirectAvailable
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: AppTheme.cardSpacing) {
                    AppSectionCard(
                        title: "选择成绩来源",
                        subtitle: "添加后还可以在档案详情里继续绑定其他账号"
                    ) {
                        AppInsetGroup {
                            VStack(spacing: 0) {
                                ForEach(Array(availableChannels.enumerated()), id: \.element) { idx, channel in
                                    if idx > 0 {
                                        AppInsetDivider()
                                    }
                                    NavigationLink(value: channel) {
                                        AppInsetRow(
                                            icon: channel.sourceIcon,
                                            iconColor: channel.sourceTint,
                                            title: channel.displayName,
                                            subtitle: channel.sourceSubtitle,
                                            showsChevron: true
                                        )
                                    }
                                    .buttonStyle(.plain)
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
            }
            .navigationTitle("添加档案")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .disabled(isSubmitting)
                }
            }
            .interactiveDismissDisabled(isSubmitting)
            .onAppear { store.beginAppUpdatePresentationBlock() }
            .onDisappear { store.endAppUpdatePresentationBlock() }
            .navigationDestination(for: SyncChannelKind.self) { channel in
                ChannelBindFormView(
                    channel: channel,
                    targetIdentityId: nil,
                    onSubmittingChange: { isSubmitting = $0 },
                    onCloseSheet: { dismiss() },
                    onCompleted: onCompleted
                )
            }
        }
        .appSheetContainer()
    }
}

/// Sheet opened from `IdentityDetailView` to bind or update a specific channel directly.
struct ChannelBindSheet: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    let channel: SyncChannelKind
    let targetIdentityId: String?
    var onCompleted: (String) -> Void = { _ in }

    @State private var isSubmitting = false

    var body: some View {
        NavigationStack {
            ChannelBindFormView(
                channel: channel,
                targetIdentityId: targetIdentityId,
                onSubmittingChange: { isSubmitting = $0 },
                onCloseSheet: { dismiss() },
                onCompleted: onCompleted
            )
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .disabled(isSubmitting)
                }
            }
            .interactiveDismissDisabled(isSubmitting)
            .onAppear { store.beginAppUpdatePresentationBlock() }
            .onDisappear { store.endAppUpdatePresentationBlock() }
        }
        .appSheetContainer()
    }
}

/// Modal sheet for pasting a fresh QR text/URL to perform an authoritative Full Sync.
struct FullSyncSheet: View {
    @Environment(DataStore.self) private var store
    @Environment(\.dismiss) private var dismiss

    let identity: PlayerIdentity
    var onCompleted: (String) -> Void = { _ in }

    @State private var qrContent: String = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private var cardSubtitle: String {
        if store.identities.count > 1 {
            return "将覆盖 \(identity.primaryDisplayName) 的本地成绩标记；获取玩家二维码，长按识别后复制文本"
        }
        return "获取玩家二维码，长按识别后复制文本"
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: AppTheme.cardSpacing) {
                    AppSectionCard(
                        title: "粘贴玩家二维码",
                        subtitle: cardSubtitle
                    ) {
                        VStack(spacing: AppTheme.Space.m) {
                            QRPasteInputRow(text: $qrContent) {
                                let trimmed = qrContent.trimmingCharacters(in: .whitespacesAndNewlines)
                                guard !isSubmitting, !trimmed.isEmpty else { return }
                                KeyboardDismiss.hide()
                                Task { await runFullSync() }
                            }

                            AppPrimaryButton(
                                title: isSubmitting ? "正在完整同步…" : "开始完整同步",
                                icon: "qrcode",
                                isLoading: isSubmitting,
                                isDisabled: isSubmitting || qrContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ) {
                                KeyboardDismiss.hide()
                                Task { await runFullSync() }
                            }
                        }
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
                .dismissKeyboardOnTap()
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("完整同步")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button("关闭") { dismiss() }
                        .disabled(isSubmitting)
                }
            }
            .interactiveDismissDisabled(isSubmitting)
            .onAppear { store.beginAppUpdatePresentationBlock() }
            .onDisappear { store.endAppUpdatePresentationBlock() }
            .errorAlert("完整同步提示", message: $errorMessage)
        }
        .appSheetContainer()
    }

    private func runFullSync() async {
        let trimmedQR = qrContent.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQR.isEmpty else { return }
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            _ = try await store.fullSyncSelfHosted(
                identityId: identity.id,
                qrContent: trimmedQR,
                writeback: false
            )
            onCompleted("完整同步完成")
            dismiss()
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = error.localizedDescription
        }
    }
}

// MARK: - Single-Channel Form

private struct ChannelBindFormView: View {
    @Environment(DataStore.self) private var store

    let channel: SyncChannelKind
    let targetIdentityId: String?
    var onSubmittingChange: (Bool) -> Void = { _ in }
    var onCloseSheet: () -> Void = {}
    var onCompleted: (String) -> Void = { _ in }

    private enum DivingFishField: Hashable {
        case username
        case password
    }

    @State private var qrContent: String = ""
    @State private var dfUsername: String = ""
    @State private var dfPassword: String = ""
    @FocusState private var focusedDivingFishField: DivingFishField?

    @State private var webAuthRunner = WebAuthSessionRunner()
    @State private var isSubmitting = false
    @State private var errorMessage: String?
    @State private var shouldDismissAfterAlert = false

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                switch channel {
                case .selfHosted:
                    cnDirectSection
                case .lxns:
                    lxnsSection
                case .divingFish:
                    divingFishSection
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
            .dismissKeyboardOnTap()
        }
        .scrollDismissesKeyboard(.interactively)
        .disabled(store.isLoadingLocalData || isSubmitting)
        .navigationTitle("绑定\(channel.displayName)")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(isSubmitting)
        .interactiveDismissDisabled(isSubmitting)
        .errorAlert("授权或同步提示", message: $errorMessage)
        .onChange(of: isSubmitting) { _, newValue in
            onSubmittingChange(newValue)
        }
        .onChange(of: errorMessage) { oldVal, newVal in
            if oldVal != nil && newVal == nil && shouldDismissAfterAlert {
                onCloseSheet()
            }
        }
        .navigationDestination(for: ChannelSecondaryAuthRoute.self) { route in
            ChannelSecondaryAuthView(
                route: route,
                targetIdentityId: targetIdentityId,
                onSubmittingChange: onSubmittingChange,
                onCloseSheet: onCloseSheet,
                onCompleted: onCompleted
            )
        }
    }

    private var cnDirectSection: some View {
        AppSectionCard(
            title: "粘贴玩家二维码",
            subtitle: "获取玩家二维码，长按识别后复制文本"
        ) {
            VStack(spacing: AppTheme.Space.m) {
                QRPasteInputRow(text: $qrContent) {
                    let trimmed = qrContent.trimmingCharacters(in: .whitespacesAndNewlines)
                    guard !isSubmitting, !trimmed.isEmpty else { return }
                    KeyboardDismiss.hide()
                    Task { await submitCNDirectBind() }
                }

                AppPrimaryButton(
                    title: isSubmitting ? "正在绑定并同步…" : "绑定并同步成绩",
                    icon: "qrcode",
                    isLoading: isSubmitting,
                    isDisabled: isSubmitting || qrContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                ) {
                    KeyboardDismiss.hide()
                    Task { await submitCNDirectBind() }
                }
            }
        }
    }

    private var lxnsSection: some View {
        AppSectionCard(
            title: "连接落雪咖啡屋",
            subtitle: "凭据仅加密保存在本机"
        ) {
            VStack(spacing: AppTheme.Space.m) {
                AppInsetGroup(padding: AppTheme.Space.m) {
                    Text("将跳转到落雪网页登录，并授权读取和上传成绩。")
                        .font(AppTheme.Typography.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }

                AppPrimaryButton(
                    title: isSubmitting ? "正在授权并同步…" : "前往落雪授权",
                    icon: "checkmark.shield.fill",
                    isLoading: isSubmitting,
                    isDisabled: isSubmitting
                ) {
                    KeyboardDismiss.hide()
                    Task { await submitLXNSOAuth() }
                }

                AppInsetDivider()

                AppInsetGroup {
                    NavigationLink(value: ChannelSecondaryAuthRoute.lxnsPersonalToken) {
                        AppInsetRow(
                            title: "使用个人 API 密钥",
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    private var divingFishSection: some View {
        AppSectionCard(
            title: "连接水鱼查分器",
            subtitle: "凭据仅加密保存在本机"
        ) {
            VStack(spacing: AppTheme.Space.m) {
                AppTextField(
                    title: "用户名",
                    placeholder: "输入水鱼用户名",
                    text: $dfUsername
                )
                .focused($focusedDivingFishField, equals: .username)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .textContentType(.username)
                .submitLabel(.next)
                .onSubmit {
                    focusedDivingFishField = .password
                }

                AppSecureField(
                    title: "密码",
                    placeholder: "输入水鱼密码",
                    text: $dfPassword
                )
                .focused($focusedDivingFishField, equals: .password)
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
                    title: isSubmitting ? "正在登录并同步…" : "登录并绑定",
                    icon: "arrow.right.circle.fill",
                    isLoading: isSubmitting,
                    isDisabled: isSubmitting
                        || dfUsername.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        || dfPassword.isEmpty
                ) {
                    KeyboardDismiss.hide()
                    Task { await submitDivingFishPassword() }
                }

                AppInsetDivider()

                AppInsetGroup {
                    NavigationLink(value: ChannelSecondaryAuthRoute.divingFishImportToken) {
                        AppInsetRow(
                            title: "使用成绩导入密钥",
                            showsChevron: true
                        )
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }

    // MARK: - Actions

    private func submitCNDirectBind() async {
        let trimmedQR = qrContent.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedQR.isEmpty else { return }
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            _ = try await store.bindSelfHosted(
                qrContent: trimmedQR,
                initialFullSync: true,
                targetIdentityId: targetIdentityId
            )
            onCompleted("已绑定 \(SyncChannelKind.selfHosted.displayName)")
            onCloseSheet()
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = error.localizedDescription
        }
    }

    private func submitLXNSOAuth() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectLXNSOAuth(using: webAuthRunner, targetIdentityId: targetIdentityId)
            onCompleted("已绑定 \(SyncChannelKind.lxns.displayName)")
            onCloseSheet()
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

    private func submitDivingFishPassword() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectDivingFishPassword(
                username: dfUsername,
                password: dfPassword,
                targetIdentityId: targetIdentityId
            )
            onCompleted("已绑定 \(SyncChannelKind.divingFish.displayName)")
            onCloseSheet()
        } catch let error as MaimaiAPIError {
            if case .recordSyncFailedAfterLogin = error {
                shouldDismissAfterAlert = true
            }
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "登录失败：\(error.localizedDescription)"
        }
    }
}

private struct ChannelSecondaryAuthView: View {
    @Environment(DataStore.self) private var store

    let route: ChannelSecondaryAuthRoute
    let targetIdentityId: String?
    var onSubmittingChange: (Bool) -> Void
    var onCloseSheet: () -> Void
    var onCompleted: (String) -> Void

    @State private var token: String = ""
    @State private var remarkName: String = ""
    @State private var isSubmitting = false
    @State private var errorMessage: String?

    private var navTitle: String {
        switch route {
        case .lxnsPersonalToken: return "使用个人 API 密钥"
        case .divingFishImportToken: return "使用成绩导入密钥"
        }
    }

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                switch route {
                case .lxnsPersonalToken:
                    AppSectionCard(
                        title: "连接落雪咖啡屋",
                        subtitle: "凭据仅加密保存在本机"
                    ) {
                        VStack(spacing: AppTheme.Space.m) {
                            AppSecureField(
                                title: "个人 API 密钥",
                                placeholder: "粘贴落雪「账号详情」生成的个人 API 密钥",
                                text: $token
                            )
                            .submitLabel(.done)
                            .onSubmit {
                                if !isSubmitting && !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
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

                            AppPrimaryButton(
                                title: isSubmitting ? "正在验证并同步…" : "验证并绑定",
                                icon: "arrow.down.circle.fill",
                                isLoading: isSubmitting,
                                isDisabled: isSubmitting || token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ) {
                                KeyboardDismiss.hide()
                                Task { await submitLXNSPersonalToken() }
                            }
                        }
                    }

                case .divingFishImportToken:
                    AppSectionCard(
                        title: "连接水鱼查分器",
                        subtitle: "凭据仅加密保存在本机"
                    ) {
                        VStack(spacing: AppTheme.Space.m) {
                            AppSecureField(
                                title: "成绩导入密钥",
                                placeholder: "粘贴水鱼「编辑个人资料」生成的成绩导入密钥",
                                text: $token
                            )
                            .submitLabel(.done)
                            .onSubmit {
                                if !isSubmitting && !token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                                    KeyboardDismiss.hide()
                                    Task { await submitDivingFishToken() }
                                }
                            }

                            AppInsetGroup(padding: AppTheme.Space.m) {
                                Text("成绩导入密钥可同时用于获取和上传成绩。")
                                    .font(AppTheme.Typography.caption)
                                    .foregroundStyle(.secondary)
                                    .fixedSize(horizontal: false, vertical: true)
                            }

                            AppPrimaryButton(
                                title: isSubmitting ? "正在验证并同步…" : "验证并绑定",
                                icon: "arrow.down.circle.fill",
                                isLoading: isSubmitting,
                                isDisabled: isSubmitting || token.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                            ) {
                                KeyboardDismiss.hide()
                                Task { await submitDivingFishToken() }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
            .dismissKeyboardOnTap()
        }
        .scrollDismissesKeyboard(.interactively)
        .disabled(store.isLoadingLocalData || isSubmitting)
        .navigationTitle(navTitle)
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(isSubmitting)
        .interactiveDismissDisabled(isSubmitting)
        .errorAlert("授权或同步提示", message: $errorMessage)
        .onChange(of: isSubmitting) { _, newValue in
            onSubmittingChange(newValue)
        }
    }

    private func submitLXNSPersonalToken() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectLXNSPersonalToken(
                token,
                remarkName: remarkName,
                targetIdentityId: targetIdentityId
            )
            onCompleted("已绑定 \(SyncChannelKind.lxns.displayName)")
            onCloseSheet()
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "验证失败：\(error.localizedDescription)"
        }
    }

    private func submitDivingFishToken() async {
        isSubmitting = true
        defer { isSubmitting = false }
        do {
            try await store.connectDivingFishImportToken(
                token,
                targetIdentityId: targetIdentityId
            )
            onCompleted("已绑定 \(SyncChannelKind.divingFish.displayName)")
            onCloseSheet()
        } catch let error as MaimaiAPIError {
            errorMessage = error.recoveryMessage
        } catch {
            errorMessage = "验证失败：\(error.localizedDescription)"
        }
    }
}

private struct QRPasteInputRow: View {
    @Binding var text: String
    var onSubmit: (() -> Void)? = nil

    var body: some View {
        AppPasteField(
            placeholder: "粘贴链接或 SGWCMAID 开头的文本",
            text: $text,
            onSubmit: onSubmit
        )
    }
}

private extension SyncChannelKind {
    var sourceIcon: String {
        switch self {
        case .selfHosted: return "qrcode"
        case .lxns: return "person.crop.circle.badge.checkmark"
        case .divingFish: return "chart.bar.doc.horizontal"
        }
    }

    var sourceTint: Color {
        switch self {
        case .selfHosted: return AppTheme.accent
        case .lxns: return AppTheme.Palette.ratingPurple
        case .divingFish: return AppTheme.Palette.statFC
        }
    }

    var sourceSubtitle: String {
        switch self {
        case .selfHosted: return "用微信玩家二维码同步"
        case .lxns: return "用落雪账号授权"
        case .divingFish: return "用水鱼账号登录"
        }
    }
}
