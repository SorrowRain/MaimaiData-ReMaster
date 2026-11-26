import SwiftUI
import UIKit

struct SettingsView: View {
    @Environment(DataStore.self) private var store
    @State private var toast = ToastState()

    private static let qqGroupNumber = "1016143695"

    private enum SettingsRoute: Hashable {
        case scoreProfiles
        case serverRegion
        case preferences
        case dataAndStorage
        case about
        case acknowledgments
    }

    private var shortVersionText: String {
        let short = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
        return "v\(short)"
    }

    private var activeProfileSummary: String {
        guard let identity = store.activeIdentity else { return "未添加" }
        return identity.primaryDisplayName
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: AppTheme.cardSpacing) {
                    // 1. 账号与区服
                    AppSectionCard(title: "账号与区服") {
                        AppInsetGroup {
                            VStack(spacing: 0) {
                                NavigationLink(value: SettingsRoute.scoreProfiles) {
                                    directoryRow(title: "成绩档案", value: activeProfileSummary)
                                }
                                .buttonStyle(.plain)

                                AppInsetDivider()

                                NavigationLink(value: SettingsRoute.serverRegion) {
                                    AppInsetRow(title: "游戏区服", showsChevron: true) {
                                        HStack(spacing: AppTheme.Space.s) {
                                            if store.isSyncingSongs && store.serverRegion != .cn {
                                                ProgressView().controlSize(.small)
                                            }
                                            Text(store.serverRegion.shortName)
                                                .font(AppTheme.Typography.body)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }

                    // 2. 偏好与数据
                    AppSectionCard(title: "偏好与数据") {
                        AppInsetGroup {
                            VStack(spacing: 0) {
                                NavigationLink(value: SettingsRoute.preferences) {
                                    directoryRow(title: "检索与显示偏好")
                                }
                                .buttonStyle(.plain)

                                AppInsetDivider()

                                NavigationLink(value: SettingsRoute.dataAndStorage) {
                                    AppInsetRow(title: "数据同步与存储", showsChevron: true) {
                                        if store.isSyncingSongs || store.isSyncingStats {
                                            ProgressView().controlSize(.small)
                                        }
                                    }
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }

                    // 3. 关于
                    AppSectionCard(title: "关于") {
                        AppInsetGroup {
                            VStack(spacing: 0) {
                                NavigationLink(value: SettingsRoute.about) {
                                    directoryRow(title: "关于 Re:MaiData", value: shortVersionText)
                                }
                                .buttonStyle(.plain)

                                AppInsetDivider()

                                NavigationLink(value: SettingsRoute.acknowledgments) {
                                    directoryRow(title: "致谢与参考")
                                }
                                .buttonStyle(.plain)

                                AppInsetDivider()

                                Button {
                                    UIPasteboard.general.string = Self.qqGroupNumber
                                    toast.show("已复制 QQ 群号", duration: 1.8)
                                } label: {
                                    directoryRow(title: "反馈与交流群", value: Self.qqGroupNumber)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("复制反馈与交流群 QQ 群号 \(Self.qqGroupNumber)")
                            }
                        }
                    }
                }
                .padding(.horizontal, AppTheme.pagePadding)
                .padding(.vertical, AppTheme.Space.m)
            }
            .appPageBackground()
            .navigationTitle("设置与数据")
            .navigationBarTitleDisplayMode(.inline)
            .statusToast(toast)
            .navigationDestination(for: SettingsRoute.self) { route in
                switch route {
                case .scoreProfiles:
                    AccountManagementView()
                case .serverRegion:
                    ServerRegionSettingsView()
                case .preferences:
                    PreferencesSettingsView()
                case .dataAndStorage:
                    DataAndStorageSettingsView()
                case .about:
                    AboutView(versionText: shortVersionText)
                case .acknowledgments:
                    AcknowledgmentsView()
                }
            }
            .navigationDestination(for: IdentityDetailRoute.self) { route in
                IdentityDetailView(identityId: route.identityId)
            }
        }
    }

    private func directoryRow(title: String, value: String? = nil) -> some View {
        AppInsetRow(title: title, showsChevron: true) {
            if let value {
                Text(value)
                    .font(AppTheme.Typography.body)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
        }
    }
}

// MARK: - Secondary Page 1: 游戏区服选择

private struct ServerRegionSettingsView: View {
    @Environment(DataStore.self) private var store

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppSectionCard(title: "曲库区服") {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            ForEach(Array(MaimaiConstants.ServerRegion.allCases.enumerated()), id: \.element.id) { idx, region in
                                if idx > 0 {
                                    AppInsetDivider()
                                }
                                regionRow(region)
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .sensoryFeedback(.selection, trigger: store.serverRegion)
        .navigationTitle("游戏区服")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func regionRow(_ region: MaimaiConstants.ServerRegion) -> some View {
        let isSelected = (store.serverRegion == region)

        return Button {
            guard !isSelected else { return }
            Task { await store.switchServerRegion(region) }
        } label: {
            AppInsetRow(title: region.displayName) {
                if store.isSyncingSongs && isSelected && region != .cn {
                    ProgressView().controlSize(.small)
                } else if isSelected {
                    Image(systemName: "checkmark")
                        .font(AppTheme.Typography.subheadlineBold)
                        .foregroundStyle(AppTheme.accentText)
                        .accessibilityLabel("已选中")
                }
            }
        }
        .buttonStyle(.plain)
        .appInsetRowHighlight(isSelected)
    }
}

// MARK: - Secondary Page 2: 检索与显示偏好

private struct PreferencesSettingsView: View {
    @Environment(DataStore.self) private var store

    var body: some View {
        @Bindable var storeBindable = store

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppSectionCard(title: "曲库与搜索") {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            AppInsetToggleRow(
                                title: "曲目详情页显示别名",
                                isOn: $storeBindable.enableShowAlias
                            )
                            AppInsetDivider()
                            AppInsetToggleRow(
                                title: "搜索时匹配曲目别名",
                                isOn: $storeBindable.enableAliasSearch
                            )
                            AppInsetDivider()
                            AppInsetToggleRow(
                                title: "搜索时匹配谱师名称",
                                isOn: $storeBindable.enableCharterSearch
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .navigationTitle("检索与显示偏好")
        .navigationBarTitleDisplayMode(.inline)
    }
}

// MARK: - Secondary Page 3: 数据同步与存储

private struct DataAndStorageSettingsView: View {
    @Environment(DataStore.self) private var store
    @State private var cacheSizeText: String = "计算中..."
    @State private var toast = ToastState()

    var body: some View {
        @Bindable var storeBindable = store

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppSectionCard(title: "数据在线更新") {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            AppInsetRow(
                                title: "曲库与规则",
                                subtitle: store.lastCatalogSyncText
                            ) {
                                AppInlineButton(
                                    title: "检查更新",
                                    icon: "arrow.down.circle",
                                    isLoading: store.isSyncingSongs
                                ) {
                                    Task { await store.syncAllCatalogsAndRules(force: true) }
                                }
                            }

                            AppInsetDivider()

                            AppInsetRow(
                                title: "水鱼拟合定数",
                                subtitle: store.lastChartStatsSync?.formatted(date: .abbreviated, time: .shortened) ?? "尚未同步"
                            ) {
                                AppInlineButton(
                                    title: "立即刷新",
                                    icon: "arrow.clockwise",
                                    isLoading: store.isSyncingStats
                                ) {
                                    Task { await store.refreshChartStatsIfStale(force: true) }
                                }
                            }
                        }
                    }
                }

                AppSectionCard(title: "本地存储") {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            AppInsetRow(
                                title: "封面磁盘缓存",
                                subtitle: "当前占用 \(cacheSizeText)",
                                monospacedSubtitle: true
                            ) {
                                AppInlineButton(
                                    title: "清除缓存",
                                    icon: "trash",
                                    tint: AppTheme.destructive
                                ) {
                                    Task {
                                        await Task.detached(priority: .utility) {
                                            CoverImageCache.shared.clearCache()
                                        }.value
                                        await refreshCacheSize()
                                        toast.show("封面缓存已清除", duration: 1.8)
                                    }
                                }
                            }

                            AppInsetDivider()

                            AppInsetRow(
                                title: "同步诊断日志",
                                subtitle: "用于排查成绩同步或上传异常"
                            ) {
                                AppInlineButton(
                                    title: "复制日志",
                                    icon: "doc.on.doc"
                                ) {
                                    let logText = store.exportSyncDiagnosticLog()
                                    if logText.isEmpty {
                                        toast.show("暂无同步日志", duration: 1.6)
                                    } else {
                                        UIPasteboard.general.string = logText
                                        toast.show("已复制诊断日志", duration: 1.8)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .navigationTitle("数据同步与存储")
        .navigationBarTitleDisplayMode(.inline)
        .statusToast(toast)
        .task {
            await refreshCacheSize()
        }
        .errorAlert("同步结果", message: $storeBindable.statusMessage)
    }

    private func refreshCacheSize() async {
        let bytes = await Task.detached(priority: .utility) {
            CoverImageCache.shared.cacheSizeBytes()
        }.value
        cacheSizeText = ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

// MARK: - Secondary Page 4: 关于 Re:MaiData

private struct AboutView: View {
    @Environment(DataStore.self) private var store
    let versionText: String

    private static let repoURL = URL(string: "https://github.com/SorrowRain/MaimaiData-ReMaster")!
    private static let releasesURL = URL(string: "https://github.com/SorrowRain/MaimaiData-ReMaster/releases")!
    private static let issuesURL = URL(string: "https://github.com/SorrowRain/MaimaiData-ReMaster/issues")!
    private static let licenseURL = URL(string: "https://github.com/SorrowRain/MaimaiData-ReMaster/blob/main/LICENSE")!
    private static let authorURL = URL(string: "https://github.com/SorrowRain")!

    var body: some View {
        @Bindable var storeBindable = store
        let iconShape = RoundedRectangle(cornerRadius: AppTheme.Radius.card, style: .continuous)

        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                AppCard(padding: AppTheme.cardPadding, tint: AppTheme.accent) {
                    VStack(spacing: AppTheme.Space.s) {
                        Image("app_logo")
                            .resizable()
                            .scaledToFit()
                            .frame(width: 72, height: 72)
                            .clipShape(iconShape)
                            .overlay(
                                iconShape.strokeBorder(Color.primary.opacity(0.08), lineWidth: 1)
                            )

                        Text("Re:MaiData")
                            .font(AppTheme.Typography.heroTitle)
                            .foregroundStyle(.primary)

                        AppCapsuleLabel(text: versionText, style: .count)

                        Text("舞萌 DX 多区服曲库检索与成绩查询工具")
                            .font(AppTheme.Typography.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.top, 2)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, AppTheme.Space.xs)
                }

                AppSectionCard(title: "项目与反馈") {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            AppInsetRow(title: "检查版本更新") {
                                AppInlineButton(
                                    title: "检查更新",
                                    icon: "arrow.clockwise",
                                    isLoading: store.isCheckingAppUpdate
                                ) {
                                    Task { await store.checkAppUpdateIfNeeded(manual: true) }
                                }
                            }

                            Divider()

                            externalLinkRow(
                                title: "GitHub 开源仓库",
                                subtitle: "github.com/SorrowRain/MaimaiData-ReMaster",
                                url: Self.repoURL
                            )

                            Divider()

                            externalLinkRow(
                                title: "版本发布与下载",
                                subtitle: "GitHub Releases",
                                url: Self.releasesURL
                            )

                            Divider()

                            externalLinkRow(
                                title: "问题反馈与建议",
                                subtitle: "GitHub Issues",
                                url: Self.issuesURL
                            )
                        }
                    }
                }

                AppSectionCard(title: "开源信息") {
                    AppInsetGroup {
                        VStack(spacing: 0) {
                            externalValueRow(
                                title: "开源协议",
                                value: "Apache-2.0",
                                url: Self.licenseURL
                            )

                            Divider()

                            externalValueRow(
                                title: "开发者",
                                value: "SorrowRain",
                                url: Self.authorURL
                            )
                        }
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .navigationTitle("关于")
        .navigationBarTitleDisplayMode(.inline)
        .errorAlert("版本检查", message: $storeBindable.statusMessage)
    }

    private func externalLinkRow(title: String, subtitle: String, url: URL) -> some View {
        Link(destination: url) {
            AppInsetRow(
                title: title,
                subtitle: subtitle,
                monospacedSubtitle: true
            ) {
                Image(systemName: "arrow.up.right.square")
                    .font(AppTheme.Typography.captionBold)
                    .foregroundStyle(AppTheme.accentText)
            }
        }
        .buttonStyle(.plain)
    }

    private func externalValueRow(title: String, value: String, url: URL) -> some View {
        Link(destination: url) {
            AppInsetRow(title: title) {
                HStack(spacing: 6) {
                    Text(value)
                        .font(AppTheme.Typography.body)
                        .foregroundStyle(.secondary)
                    Image(systemName: "arrow.up.right.square")
                        .font(AppTheme.Typography.captionBold)
                        .foregroundStyle(AppTheme.accentText)
                }
            }
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Secondary Page 5: 致谢与参考

private struct AcknowledgmentsView: View {
    private struct ReferenceItem: Identifiable {
        var id: String { name }
        let name: String
        let tag: String
        let summary: String
        let urlString: String
        let displayHost: String
    }

    private static let proberReferences: [ReferenceItem] = [
        ReferenceItem(
            name: "LXNS（落雪咖啡屋）",
            tag: "查分与国服曲库",
            summary: "提供落雪账号成绩同步接口、国服曲库与别名公共数据、官方版本牌子曲目规则以及国内高速高清曲绘 CDN 镜像服务。",
            urlString: "https://maimai.lxns.net",
            displayHost: "maimai.lxns.net"
        ),
        ReferenceItem(
            name: "Diving-Fish（水鱼查分器）",
            tag: "查分与拟合统计",
            summary: "提供水鱼账号成绩同步接口、全服大数据拟合定数统计以及曲绘封面兜底服务。",
            urlString: "https://github.com/Diving-Fish/maimaidx-prober",
            displayHost: "diving-fish.com"
        )
    ]

    private static let multiRegionReferences: [ReferenceItem] = [
        ReferenceItem(
            name: "zetaraku / arcade-songs-fetch",
            tag: "多区服曲库",
            summary: "提供日服与国际服完整曲库、版本归属、区服定数覆写、外服曲绘镜像及宴会场谱面说明数据。",
            urlString: "https://github.com/zetaraku/arcade-songs-fetch",
            displayHost: "github.com/zetaraku/arcade-songs-fetch"
        ),
        ReferenceItem(
            name: "DXRating (gekichumai)",
            tag: "ID 与新规参考",
            summary: "提供日服与国际服曲目底层数字 ID 实时映射数据，以及 CiRCLE 起实行的两代 B15 与 AP+1 新规参考。",
            urlString: "https://github.com/gekichumai/dxrating",
            displayHost: "dxrating.net"
        ),
        ReferenceItem(
            name: "Yuri-YuzuChaN & 社区资源库",
            tag: "别名与贴图",
            summary: "提供社区共同维护的舞萌 DX 曲目中文别名库，以及各世代版本与评级贴图静态素材整理。",
            urlString: "https://github.com/Yuri-YuzuChaN/maimaiDX",
            displayHost: "github.com/Yuri-YuzuChaN/maimaiDX"
        )
    ]

    private static let designReferences: [ReferenceItem] = [
        ReferenceItem(
            name: "PaperPig / MaimaiData",
            tag: "早期客户端参考",
            summary: "Android 端开源舞萌 DX 查询工具，为本项目早期提供了功能形态与部分静态贴图素材参考。",
            urlString: "https://github.com/PaperPig/MaimaiData",
            displayHost: "github.com/PaperPig/MaimaiData"
        )
    ]

    var body: some View {
        ScrollView {
            VStack(spacing: AppTheme.cardSpacing) {
                referenceSection(title: "查分器与核心数据源", items: Self.proberReferences)
                referenceSection(title: "多区服数据与社区生态", items: Self.multiRegionReferences)
                referenceSection(title: "客户端形态参考", items: Self.designReferences)

                AppSectionCard(title: "版权与免责声明") {
                    AppInsetGroup(padding: AppTheme.Space.m) {
                        Text("《maimai でらっくす》与《舞萌 DX》游戏本体及其曲绘封面、版本标识、评级贴图等相关美术与音乐素材版权均归 SEGA 及华立科技（Wahlap）所有。本应用为非商用开源工具，仅作玩家个人谱面查询与算分辅助使用。")
                            .font(AppTheme.Typography.caption)
                            .foregroundStyle(.secondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
            }
            .padding(.horizontal, AppTheme.pagePadding)
            .padding(.vertical, AppTheme.Space.m)
        }
        .appPageBackground()
        .navigationTitle("致谢与参考")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func referenceSection(title: String, items: [ReferenceItem]) -> some View {
        AppSectionCard(title: title) {
            VStack(spacing: AppTheme.itemSpacing) {
                ForEach(items) { item in
                    if let url = URL(string: item.urlString) {
                        Link(destination: url) {
                            referenceCardContent(item)
                        }
                        .buttonStyle(.plain)
                        .accessibilityLabel("\(item.name)，\(item.tag)，点击在浏览器中打开")
                    } else {
                        referenceCardContent(item)
                    }
                }
            }
        }
    }

    private func referenceCardContent(_ item: ReferenceItem) -> some View {
        AppInsetGroup(padding: AppTheme.Space.m) {
            VStack(alignment: .leading, spacing: AppTheme.Space.s) {
                HStack(alignment: .center, spacing: AppTheme.Space.s) {
                    Text(item.name)
                        .font(AppTheme.Typography.songTitle)
                        .foregroundStyle(.primary)
                        .lineLimit(1)

                    Spacer(minLength: AppTheme.Space.xs)

                    AppCapsuleLabel(text: item.tag, style: .compact)
                }

                Text(item.summary)
                    .font(AppTheme.Typography.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)

                HStack(spacing: AppTheme.Space.xs) {
                    Image(systemName: "arrow.up.right.square")
                        .font(AppTheme.Typography.caption2Bold)
                    Text(item.displayHost)
                        .font(AppTheme.Typography.captionMono)
                        .lineLimit(1)
                }
                .foregroundStyle(AppTheme.accentText)
            }
        }
    }
}
