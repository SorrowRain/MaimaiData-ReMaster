# MaimaiData-iOS 架构与开发规范 (Agent & Developer Guide)

本文档定义了 `MaimaiData-iOS` 的分层职责、设计令牌（Design Tokens）、标准组件库选型及核心业务/并发不变量。任何开发者或 AI Agent 在调整 UI、修复问题或新增功能时，**必须严格遵守以下规范**，严禁破坏分层边界、硬编码样式字面量或手写野生替代组件。

---

## 1. 目录结构与分层边界

```text
MaimaiData/
├── MaimaiDataApp.swift              # App 入口（注入全局 DataStore，跳过单元测试宿主启动网络请求）
├── Theme/
│   └── AppTheme.swift               # 全局设计令牌（品牌色、游戏语义调色板、圆角、间距阶梯、语义字阶、页面背景）
├── Components/                      # 跨页面复用的标准 UI 组件（严禁在 Views/ 下定义通用组件）
│   ├── Badges.swift                 # 胶囊标签、统计砖块、难度定数徽章、分类胶囊、谱面类型角标、封面框、筛选 Chip
│   ├── Buttons.swift                # 主按钮、次级玻璃按钮、行内胶囊按钮、菜单触发胶囊、圆形玻璃图标按钮
│   ├── Checklist.swift              # 进度表顶部完成度统计卡片与歌曲网格封面格
│   ├── Containers.swift             # 流式布局 FlowLayout、标准卡片 AppCard / AppSectionCard、分组头、内嵌分组与行、图片预览脚手架
│   ├── RatingTierTable.swift        # 分段达成率与单曲 Rating / B50 净涨分三列对照表（详情页与计算器共用）
│   ├── Feedback.swift               # 自适应玻璃封装 appGlass、底部状态 Toast、同步状态横幅、错误弹窗与键盘收起修饰符
│   └── Forms.swift                  # 标准单行输入框 AppTextField、密码框 AppSecureField、带粘贴图标按钮 AppPasteField、键盘收起工具 KeyboardDismiss
├── Bridges/
│   └── UIKitBridges.swift           # UIKit 桥接封装（相册保存 PhotoLibrarySaver、系统分享 ShareSheet、可缩放图片容器 ZoomableImageScrollView）
├── Models/
│   ├── SongData.swift               # 曲目与谱面模型、物量解析 ResolvedChart、国内 CDN 曲绘候选地址生成
│   ├── DifficultyType.swift         # 谱面难度枚举及浅色/深色模式对比度适配色彩
│   ├── MaimaiConstants.swift        # 分类、等级、国服/日服版本常量与配色/贴图映射
│   ├── RatingCalculator.swift       # 单曲 Rating 换算、目标总 Rating 二分反推、全曲库最大物量统计
│   ├── RecordModels.swift           # 成绩模型、进度表统计、筛选条件与旧版 ScoreProfile（升级用）
│   ├── SyncModels.swift             # v3 PlayerIdentity 多渠道绑定模型（含 B50 玩家图片名称）、同步规划 SyncPlan、国服直连 DTO、上传摘要与历史快照模型
│   ├── LXNSModels.swift             # 落雪咖啡屋 OAuth / 成绩模型、公共曲库与三区服通用牌子范围模型 PlateCheckScope
│   └── Extensions.swift             # Color(hex:) / 自适应与提亮扩展、安全下标 Collection[safe:]、版本号按段数值比较 VersionString
├── Services/
│   ├── DataStore.swift              # @MainActor @Observable 全局状态中心（本地缓存、双查分器并发最优合并、已删除国服曲目解析层、多区服切换、多渠道身份与单飞同步、B50 拆表）
│   ├── SyncDiagnostics.swift        # 脱敏同步/上传内存与磁盘诊断日志（设置页支持一键复制导出）
│   ├── SyncServiceConfig.swift      # 编译期注入的闭源后端配置解析（Info.plist <- Base/Local.xcconfig，不落盘、无 UI 入口）
│   ├── MaimaiAPI.swift              # 无状态网络层（国服直连后端、落雪公共/OAuth/上传、水鱼读写/拟合定数、Zetaraku & DXRating）
│   ├── ScoreSyncEngine.swift        # 纯函数成绩合并引擎（快速/完整/双端合并、水鱼/落雪纯净国服增量写回、CN Rating 快照、v2->v3 升级）
│   ├── SyncHistoryStore.swift       # 追加写 JSONL (`history_identity_<uuid>.jsonl`) 趋势快照存储
│   ├── LXNSCatalogConverter.swift   # 落雪公共曲库/双人宴谱转换、三区服牌子规则构建与落雪成绩转标准记录纯函数
│   ├── ZetarakuCatalogConverter.swift # 日服/国际服 Zetaraku 曲库转换器与已打过国服删除曲解析还原层 (`resolveDeletedCNSongs`)
│   ├── AccountCredentialStore.swift # Keychain 多渠道身份 (`player_identities_v3`) 与分槽位密钥存取，含 loadFailed 写保护
│   ├── ImageLoader.swift            # 内存 + 磁盘双层曲绘缓存 CoverImageCache 与异步曲绘视图 CoverImageView
│   ├── Best50Renderer.swift         # Best 50 成绩图离屏并发预取与 UIGraphicsImageRenderer 渲染引擎
│   ├── SongFilter.swift             # 纯函数曲库多维筛选与排序（支持 playedSongIds 过滤未打过的删除曲，供 DataStore 与单元测试共用）
│   └── PreferenceKeys.swift         # UserDefaults / Keychain 键名常量与默认布尔偏好注册
└── Views/                           # 业务页面视图（仅包含页面级布局组装与页面内私有子视图）
    ├── MainTabView.swift            # 底部三栏 Tab 导航（曲库 / Rating与查分 / 设置与数据）
    ├── SongListView.swift           # 曲目列表、标注国服已删除曲、液态玻璃搜索栏、搜索历史卡片与已筛选条件摘要
    ├── SearchFilterSheet.swift      # 等级/定数双滑块、分类、收录版本与难度排序筛选弹窗
    ├── SongDetailView.swift         # 曲目详情、难度切换、拟合定数、物量分布条与容错扣分参考
    ├── CoverZoomView.swift          # 曲目高清封面全屏缩放预览页
    ├── RatingToolsView.swift        # Rating 与查分主页（玩家总览、同步成绩、成绩牌子入口、算分工具入口；已移除首页旧框换算入口）
    ├── RatingCalculatorView.swift   # Rating 计算器二级页（双滑块定数分段算分 + 目标总分反推）
    ├── ProberView.swift             # B35 / B15 成绩列表与 Best 50 成绩图生成预览
    ├── VersionCheckView.swift       # 国服 / 国际服 / 日服统一牌子进度表（支持旧框舞/覇者与各代全曲牌子）
    ├── LevelCheckView.swift         # 按 1～15 级定数分组的全难度完成度进度表
    ├── FinaleToDxView.swift         # 旧框 (FiNALE) 音符判定换算 DX 达成率工具（保留实现，待后续重构挂载）
    ├── SettingsView.swift           # 极简两级目录式设置页（含区服选择、检索偏好、数据存储含诊断日志复制与致谢二级页）
    ├── AccountManagementView.swift  # L1 成绩档案列表（单行名称/当前标识/绑定摘要 + 添加档案与切换激活档案）
    ├── IdentityDetailView.swift     # L2 成绩档案详情页（ID、B50 玩家图片名称开关、上次同步时间、SyncPlan 动态同步区、上传查分器与渠道管理）
    └── LoginSheet.swift             # L3 弹窗集合（AddIdentitySheet 新增档案、主/次分层 ChannelBindSheet 渠道绑定、FullSyncSheet 完整同步）
```

### 分层依赖规则
1. **单向依赖**：`Views/` 可依赖 `Components/`、`Theme/`、`Models/`、`Services/`；`Components/` 仅可依赖 `Theme/`、`Models/`、`Bridges/` 及 `CoverImageView`（除 `SyncStatusBanner` 外严禁耦合具体页面状态）。
2. **禁止野生通用组件**：凡在 **2 个及以上业务页面**复用的视觉结构，必须下沉至 `Components/` 对应分类文件，严禁在 `Views/` 中复制粘贴相似的 `private func` / `private struct`。
3. **纯逻辑可测化**：新增复杂计算、排序、换算或文本解析逻辑时，应优先抽离至 `Models/` 或 `Services/` 的无状态纯函数，并在 `MaimaiDataTests/` 补充单元测试，禁止将核心算法深埋在 `View` 私有方法中。

---

## 2. 设计系统令牌规范 (`AppTheme`)

所有颜色、圆角、间距、字号字重必须取自 `MaimaiData/Theme/AppTheme.swift`。

### 2.1 颜色令牌 (`AppTheme` & `AppTheme.Palette`)
- **全局强调与状态色**：主色调 `AppTheme.accent` (`#1668DC`)、亮色强调 `AppTheme.accentBright` (`#2B88FF`)、深浅色自适应蓝色文字前景 `AppTheme.accentText`（深色卡片上蓝色文字一律使用 `accentText` 保证 >= 4.5:1 对比度）、收藏红心 `AppTheme.favoritePink`、危险红 `AppTheme.destructive`、警告橙 `AppTheme.warning`。
- **游戏领域锁定色 (`AppTheme.Palette`)**：
  - 包含 4 种音符色（`noteTap` / `noteHold` / `noteSlide` / `noteBreak`）、6 种谱面难度底色/文字色/深色版面色（`diff*` / `boardMain*`）、7 种歌曲分类色与文字色（`genre*`）、判定扣分色（`judge*`）及完成度统计色（`stat*`）。
  - **锁定红线**：`Palette` 中的街机领域色值经过浅色/深色对比度校准，**严禁随意改动十六进制色值**，也严禁在业务视图中散落编写 `Color(hex:)`。
- **前景与状态色**：常规正文与次要信息统一使用 SwiftUI `.primary` / `.secondary` / `.tertiary`；深色卡片或内嵌容器上的彩色小字需确保足够对比度（优先通过标准组件内置提亮承载）。

### 2.2 布局间距与尺寸令牌 (`AppTheme` & `AppTheme.Space`)
- **页面与容器宏观常量**：
  - `AppTheme.pagePadding` (`16pt`)：页面水平外边距
  - `AppTheme.cardPadding` (`16pt`)：标准卡片内边距
  - `AppTheme.compactCardPadding` (`12pt`)：紧凑列表卡片、状态横幅内边距
  - `AppTheme.cardSpacing` (`12pt`)：页面内卡片纵向间距
  - `AppTheme.itemSpacing` (`10pt`)：长列表（如曲目列表、成绩列表）卡片间距
  - `AppTheme.rowMinHeight` (`50pt`)：标准表单输入框与设置行最小触控高度
- **通用间距阶梯 (`AppTheme.Space`)**：
  - `xs = 4` · `s = 8` · `m = 12` · `l = 16` · `xl = 24`
  - 编写新视图时应优先选用上述阶梯令牌，避免随意引入 `5 / 7 / 9 / 11 / 14` 等非标魔数。

### 2.3 圆角令牌 (`AppTheme.Radius`)
所有 `RoundedRectangle` **必须统一指定 `style: .continuous`**，并使用以下语义圆角：

| 令牌 | 数值 | 适用场景 |
| :--- | :--- | :--- |
| `Radius.card` | `16pt` | 页面级卡片（`AppCard` / `AppSectionCard` / 详情页头卡） |
| `Radius.button` | `14pt` | 底部主/次操作大按钮、详情页 100pt 封面框 |
| `Radius.inset` | `12pt` | 卡片内嵌浅底分组（`AppInsetGroup`）、列表 64pt 封面框 |
| `Radius.input` | `12pt` | 输入框（`AppTextField` / `AppSecureField`）及配套并排按钮 |
| `Radius.chip` | `12pt` | 难度切换块、全屏占位图圆角 |
| `Radius.tile` | `10pt` | 统计砖块（`AppStatTile`）、进度表 72pt 封面格、紧凑 56pt 封面框、工具行图标底块 |
| `Radius.badge` | `6pt` | 难度定数小徽章（`DifficultyLevelBadge`） |

### 2.4 语义字阶 (`AppTheme.Typography`)
视觉层级严格遵循 **`20pt Hero > 17pt Card Title > 15pt Row / Input > 12pt Caption / Inline Action > 11pt Badge`**：

| 层级 | 令牌名 | 底层定义 | 标准用途 |
| :--- | :--- | :--- | :--- |
| **L1 标题与核心指标** | `heroTitle` | `title3.bold.monospacedDigit` (20pt) | 详情页曲名、核心换算结果、`.metric` 大胶囊数值 |
| | `metricDisplay` | `title3.bold` (20pt) | 居中指标砖块大数字（比例字宽，避免等宽 `1` 左侧留白偏移） |
| | `sectionMetric` | `headline.bold.monospacedDigit` (17pt) | 总览卡片大号数字指标、等级进度表标题 |
| | `cardTitle` | `headline` (17pt) | `AppSectionHeader` 卡片标题、玩家总览标题 |
| **L2 行内容与表单** | `songTitle` | `subheadline.semibold` (15pt) | 列表歌曲名、主/次大按钮文案 |
| | `subheadlineBold` | `subheadline.bold` (15pt) | 圆形图标按钮、工具行图标、小节粗体小标题 |
| | `rowTitle` | `subheadline.medium` (15pt) | 设置行/工具行标题、详情键值对正文 |
| | `input` | `subheadline.medium.monospacedDigit` (15pt) | `AppTextField` / `AppSecureField` 输入文本 |
| | `rowMetric` | `subheadline.bold.monospacedDigit` (15pt) | 行内达成率等醒目数字指标 |
| | `body` | `subheadline` (15pt) | 常规正文、搜索框输入、设置项副文本 |
| **L3 辅助说明与行内操作** | `inlineAction` | `caption.semibold` (12pt) | `AppInlineButton` / `AppMenuCapsule` / `StatusToast` 文案、表格左侧字段名 |
| | `captionBold` | `caption.bold` (12pt) | 难度切换简称、表格表头、右侧导航箭头 |
| | `tag` | `caption.medium` (12pt) | `FilterCapsuleChip`、输入框上方标签、同步状态提示 |
| | `caption` | `caption` (12pt) | 卡片与行副标题、辅助说明、错误提示 |
| | `captionMono` | `caption.monospacedDigit` (12pt) | BPM、旧定数、数据表格等宽数值行 |
| | `captionMeta` | `caption.semibold.monospacedDigit` (12pt) | `.count` 数量胶囊、`AppStatTile` 数值、物量条计数 |
| | `captionMetaBold` | `caption.bold.monospacedDigit` (12pt) | 成绩行达成率与单曲 `Ra` 值 |
| **L4 紧凑徽章与微标签** | `badge` | `caption2.bold.monospacedDigit` (11pt) | `DifficultyLevelBadge` 定数/等级文字 |
| | `caption2Bold` | `caption2.bold` (11pt) | `.compact` 胶囊、`AppStatTile` 标题、行内按钮小图标 |
| | `caption2Semibold` | `caption2.semibold` (11pt) | `GenreCapsuleTag`、`.filter` 胶囊、指标块小标题 |
| | `caption2` | `caption2` (11pt) | 收藏小心形角标、头卡微标签 |

> **字阶铁律**：
> 1. 严禁在业务视图中直接写 `.font(.system(...))` 或 `.font(.caption)` 等原生字面量（`Best50Renderer` 固定像素离屏画布除外）。
> 2. 严禁在已绑定 `AppTheme.Typography` 令牌的 `Text` 上再额外叠加 `.fontWeight(...)` 或 `.monospacedDigit()`，应直接选用匹配的语义令牌（如需要 12pt 半粗等宽数字时直接用 `Typography.captionMeta`，而非 `captionMono` + `.fontWeight(.semibold)`）。
> 3. 严禁为了单个视图微调而在 `Typography` 中堆砌一次性机械命名。

### 2.5 页面背景与自适应液态玻璃
- **页面背景**：所有业务页面根容器统一挂载 `.appPageBackground(tint:)`（默认 `AppTheme.accent`，歌曲详情页可传入分类色）。
- **玻璃质感 (`View.appGlass`)**：
  - 全项目唯一调用 `.glassEffect` 的入口为 `Components/Feedback.swift` 中的 `View.appGlass(in:tint:interactive:)`（iOS 26+ 使用原生 Liquid Glass，iOS 17/18 自动回退为 `.ultraThinMaterial` + 品牌色着色）。
  - **禁止**在业务视图中散落编写 `#available(iOS 26.0, *)` 分支。
  - **禁止**在长滚动列表的普通卡片（`AppCard`）上挂载实时模糊材质，确保 ProMotion 120fps 滚动帧率。

---

## 3. 标准组件库全量选型清单 (`Components/` & `Bridges/`)

开发新功能或修改界面时，**必须按以下清单优先复用标准组件**，禁止手写替代品，也禁止向标准组件塞入零散的 `padding` / `opacity` / `font` 透传参数（确需新增变体时，应在组件内部增加语义化 `Style` 枚举）：

### 3.1 容器与布局 (`Containers.swift` / `Checklist.swift`)
| 组件名 | 所在文件 | 职责与选型要求 |
| :--- | :--- | :--- |
| `AppCard` | `Containers.swift` | 页面基础圆角卡片（支持浅色白底/深色 `surfaceDark`、可选左上淡渐变 `tint` 与细描边） |
| `AppSectionHeader` | `Containers.swift` | 带左侧蓝色渐变竖条的标准分组标题栏（含可选 `subtitle` 与右侧 `trailing` 槽位） |
| `AppSectionCard` | `Containers.swift` | 组合 `AppCard` + `AppSectionHeader` 的页面主力业务卡片，凡带标题的卡片一律使用它 |
| `AppInsetGroup` | `Containers.swift` | 卡片内部的浅底圆角内嵌容器（内置 `clipShape` 自动裁切首尾行外侧圆角，内部统一用通栏 `AppInsetDivider()` 分割），用于承载设置组、工具入口列表、数据表格或指标块 |
| `AppInsetRow` / `AppInsetToggleRow` / `.appInsetRowHighlight` | `Containers.swift` | `AppInsetGroup` 内的标准行、开关设置行及显式 `Rectangle()` 通栏高亮修饰符（严禁对表格行裸调 `.background(color)` 以免继承外层圆角） |
| `FlowLayout` | `Containers.swift` | 自适应折行流式布局，用于筛选条件 Chip、歌曲别名胶囊等多标签排布 |
| `PhotoPreviewScaffold` | `Containers.swift` | 全屏纯黑底图片缩放预览、系统分享与保存相册通用脚手架（`CoverZoomView` 与 `B50PreviewSheet` 共用） |
| `RatingTierTable` | `RatingTierTable.swift` | 谱面各达成率分段（含 SSS 区间 +1 跳分线）单曲分与 B50 净涨分对照表（未登录自动退化为两列） |
| `ChecklistStatsHeader` | `Checklist.swift` | 进度表顶部「完成度统计」（SSS / FC / AP / FSD 四宫格点击切换角标 + 仅看未达成过滤开关） |
| `ChecklistSongGrid` / `ChecklistSongTile` | `Checklist.swift` | 进度表标准自适应网格与 72×72 曲绘网格单元（内置评级/连击/同步无障碍标签与难度边框） |

### 3.2 按钮与菜单触发器 (`Buttons.swift`)
| 组件名 | 所在文件 | 职责与选型要求 |
| :--- | :--- | :--- |
| `AppPrimaryButton` | `Buttons.swift` | 主操作玻璃按钮：`.standard` 用于页面/表单底部通栏主按钮（46pt）；`.inputCompanion` 用于与 `AppTextField` 并排的操作按钮（50pt） |
| `AppSecondaryButton` | `Buttons.swift` | 次级无底色玻璃按钮（46pt，如筛选弹窗底部的「重置选项」） |
| `AppInlineButton` | `Buttons.swift` | 卡片标题栏右侧或行内的 12pt 紧凑胶囊操作按钮（支持图标、`isLoading` 与深色对比度提亮；凡卡片内「清空 / 重置 / 刷新 / 同步」一律使用它，禁止手写裸文字 `Button`） |
| `AppMenuCapsule` | `Buttons.swift` | `Menu` 专用紧凑胶囊触发器（带上下箭头，与 `AppInlineButton` 共享描边与着色视觉） |
| `AppCircleIconButton` | `Buttons.swift` | 34×34 圆形玻璃图标按钮（如左右切换等级，**强制传入 `accessibilityLabel`**） |

### 3.3 徽章、标签与曲绘 (`Badges.swift` / `ImageLoader.swift`)
| 组件名 | 所在文件 | 职责与选型要求 |
| :--- | :--- | :--- |
| `AppCapsuleLabel` | `Badges.swift` | 标准着色胶囊标签：`.count`（分组计数如 `"24 首"`）、`.filter`（带描边的已筛选条件摘要）、`.compact`（行内状态/排名如 `"#1"` / `"当前"` / `"4x"`）、`.metric(prefix:)`（醒目指标如 `"Ra 333"`） |
| `AppMetricTile` | `Badges.swift` | 内嵌浅底指标块（上小标题 + 下数值或自定义定数视图，用于详情页与 Rating 总览） |
| `AppValueBadge` | `Badges.swift` | 滑块配套定宽数值徽章（内置深浅色对比度适配） |
| `AppStatTile` | `Badges.swift` | 居中上标题下数值的着色统计砖块（用于完成度四宫格统计） |
| `DifficultyLevelBadge` | `Badges.swift` | 难度定数/等级徽章（自动处理 Re:MASTER 白底紫框及浅/深色文字对比度） |
| `GenreCapsuleTag` | `Badges.swift` | 歌曲分类与详情页别名胶囊标签（`.compact` 11pt 分类 / `.regular` 12pt 别名） |
| `RecordStatusBadges` | `Badges.swift` | 成绩评级 + FC/FS 贴图组合横条（`.compact` / `.regular`，内置完整无障碍标签） |
| `ChartTypeBadge` | `Badges.swift` | 标准 / DX 谱面贴图角标（内置无障碍标签） |
| `SongCoverBadgeView` | `Badges.swift` | 标准圆角带描边曲绘框（`.compact` 56pt / `.list` 64pt / `.hero` 100pt，可选右下角谱面类型角标） |
| `CoverImageView` | `ImageLoader.swift` | 底层异步曲绘视图（接入内存+磁盘双层缓存与国内 CDN 兜底；**严禁使用系统 `AsyncImage` 加载曲绘**） |
| `FilterCapsuleChip` | `Badges.swift` | 筛选弹窗与最近搜索中的可点击选中态胶囊 Chip（内置无障碍选中态声明） |

### 3.4 表单、反馈与桥接 (`Forms.swift` / `Feedback.swift` / `UIKitBridges.swift`)
| 组件名 / 修饰符 | 所在文件 | 职责与选型要求 |
| :--- | :--- | :--- |
| `AppTextField` / `AppSecureField` | `Forms.swift` | 标准单行输入框与带显隐切换的密码框（内置 50pt 最小高度、整框点击热区与聚焦高亮边框；除顶部胶囊搜索栏外，表单与工具页严禁直接使用裸 `TextField`） |
| `AppLabeledSliderRow` | `Forms.swift` | 标准「左侧标签 + `AppValueBadge` + 离散 `Slider`」组合行（筛选弹窗与 Rating 计算器共用，内置 VoiceOver 无障碍标签） |
| `KeyboardDismiss.hide()` / `.dismissKeyboardOnTap()` | `Forms.swift` / `Feedback.swift` | 收起第一响应者键盘及点击空白区域收起键盘修饰符 |
| `ToastState` / `.statusToast(_:)` | `Feedback.swift` | 底部液态玻璃轻提示控制器（自动取消上一次倒计时防连点截断）与挂载修饰符 |
| `SyncStatusBanner` | `Feedback.swift` | 曲库读取/同步进度及失败精准重试横幅 |
| `.errorAlert(_:message:)` | `Feedback.swift` | 绑定 `String?` 错误消息的标准系统弹窗修饰符（关闭自动置 `nil`） |
| `PhotoLibrarySaver` / `ShareSheet` / `ZoomableImageScrollView` | `UIKitBridges.swift` | 系统相册只写权限保存、`UIActivityViewController` 分享桥接、6 约束双指/双击缩放视图 |

---

## 4. 核心业务、并发与平台规范（防回归必读）

1. **曲绘封面源与缓存 (`SongData.coverCandidateURLs` & `CoverImageCache`)**：
   - 官方源 `maimaidx.jp` 的 TLS 证书链不完整，在 iOS `URLSession` 下会因 SSL 验证失败直接报错。
   - 国服曲目（`numericId > 0`）优先走国内高可用 CDN：`https://assets2.lxns.net/maimai/jacket/{baseJacketId}.png` 优先，水鱼 5 位补零地址兜底，缓存 Key 统一按 `jacket_{baseJacketId}` 归并；日服/国际服独占曲目（`numericId == 0`）走 Zetaraku CloudFront 封面 CDN，缓存 Key 按 `jacket_remote_{stem}` 隔离，严禁与国服取模 ID 混用。
2. **多区服切换与算分新规一致性 (`ZetarakuCatalogConverter` & `DataStore`)**：
   - 国服（`.cn`）保持水鱼原始定数与算分规则；日服（`.jp`）与国际服（`.intl`）由 `ZetarakuCatalogConverter` 融合国服歌曲 ID 与社区别名，B15 采用 `CiRCLE` 起实行的**两代半版本规则**（`.jp`：`MAGiCAL` + `CiRCLE PLUS`；`.intl`：`CiRCLE PLUS` + `CiRCLE`），且达成 `AP / AP+` 单曲 Rating 额外 `+1`。
   - 只要存在激活成绩档案（`store.isLoggedIn`），三个区服下的曲目详情页「分段 Rating 对照」统一展示全部三列（含按当前区服 B35/B15 底线计算的 `B50 可加` 净涨分）。
3. **搜索框中文输入法防闪屏 (`SongFilterCriteria.isActive` & `SongListView`)**：
   - `SongFilterCriteria.isActive` **绝对不能包含 `!keyword.isEmpty`**。中文输入法选词上屏瞬间会先清空拼音再填入汉字，若 `keyword` 计入 `isActive` 会导致「已筛选」摘要卡片在选词瞬间反复挂载/卸载引起列表剧烈跳动。
   - 搜索历史卡片统一由 `.onChange(of: isSearchFocused)` 驱动展开收起，并在 `onDisappear` 中关闭动画重置焦点，防止键盘弹起时点进详情页再返回出现布局跳动。
4. **多渠道身份凭据安全与单飞轮换一致性 (`AccountCredentialStore` & `DataStore`)**：
   - 自建服务器配置（`self_hosted_endpoint_v1`）、档案列表（`player_identities_v3`）及分渠道凭据槽位（`identity_sh_/lxns_/df_<uuid>`）**只能存入 Keychain**（`AccountCredentialStore`），读取失败时触发 `loadFailed` 写保护锁定写操作；本地成绩缓存按档案 UUID 隔离为 `records_identity_<uuid>.json`，趋势快照按行追加至 `history_identity_<uuid>.jsonl`，成绩档案与外部游戏区服完全解耦。
   - 落雪 OAuth 的 `refresh_token` 采用单次轮换机制（刷新成功后原令牌立即失效）：`DataStore.validLXNSAccessToken` 必须按档案 ID 做单飞互斥（`inFlightTokenRefreshTasks`），并在拿到新令牌瞬间立即落盘 Keychain，严禁并发刷新。
   - 添加档案时必须严格区分**认证本身失败**与**认证成功后首次拉取成绩失败**（`MaimaiAPIError.recordSyncFailedAfterLogin`）；最终展示名称统一由 `DataStore.displayPlayerName` 根据 `useProberNickname` 开关裁决。
   - 宴谱记录统一使用 `levelIndex = 10`（单人/1P）与 `11`（Buddy 2P）、`type = "utage"`、`computedRating = 0`。
5. **下拉刷新防取消与缓存竞态防护 (`DataStore`)**：
   - `DataStore.performSyncRecords` 内部使用非结构化 `Task` 承载网络请求，防止 SwiftUI `.refreshable` 在状态更新重绘瞬间取消底层 `URLSession` 请求（`NSURLErrorCancelled`）。
   - 切换档案异步回填本地成绩缓存（`restoreCachedRecords`）受单调递增令牌 `recordsEpoch` 保护，绝不能覆盖网络刚拉回的新鲜成绩。
6. **导航栈复用与图片缩放约束 (`RatingToolsView` & `ZoomableImageScrollView`)**：
   - 「Rating 与查分」Tab 下的二级工具页（`ProberView`、`VersionCheckView`、`LevelCheckView`）点击曲目进入详情页时，统一复用根视图 `RatingToolsView` 注册的 `.navigationDestination(for: SongDetailRoute.self)`，**严禁在子页面重复注册同类型 `navigationDestination`**。
   - `ZoomableImageScrollView` 内的 `UIImageView` 必须同时绑定 `contentLayoutGuide`（四边）与 `frameLayoutGuide`（宽高）共 6 条约束，确保初始自适应居中且支持 `1.0x–4.5x` 双指与双击缩放。
7. **工程配置同步与测试宿主隔离 (`project.yml` & `MaimaiData.xcodeproj` & `MaimaiDataApp`)**：
   - 增删源码/资源文件或修改构建配置时，必须同步维护 `project.yml` 与 `MaimaiData.xcodeproj/project.pbxproj`。
   - 当前工程固定为 **iPhone 竖屏应用**（`TARGETED_DEVICE_FAMILY = 1`），最低部署目标 `iOS 17.0`。
   - `MaimaiDataApp` 启动时通过 `XCTestConfigurationFilePath` 环境变量跳过 `store.bootstrap()`，确保单元测试宿主纯净、不发起真实网络请求。

---

## 5. 新功能开发准入自检清单 (Definition of Done)

每次提交新功能或重构代码前，请逐项核对：

- [ ] **组件零手写重复**：是否复用了第 3 节的标准组件？若有跨页复用的新视觉单元，是否已提炼至 `Components/` 而非散落在 `Views/`？
- [ ] **令牌零字面量逃逸**：圆角是否全部走 `AppTheme.Radius`（且带 `.continuous`）？字号字重是否全部走 `AppTheme.Typography`（且未额外叠加 `.fontWeight` / `.monospacedDigit`）？颜色是否全部走 `AppTheme` 或语义系统色？
- [ ] **深浅色与对比度**：在 Light Mode 与 Dark Mode 下检查新增视图，彩色文字与胶囊在深色背景（`#172135`）上是否清晰易读？
- [ ] **液态玻璃合规**：是否仅通过 `.appGlass(...)` 使用玻璃材质，且未在长滚动列表行卡片上滥用实时模糊？
- [ ] **无障碍支持**：所有纯图标按钮（如 `AppCircleIconButton`、Toolbar 图标）与状态贴图是否均提供了准确的中文 `accessibilityLabel`？
- [ ] **工程与测试**：新增文件是否同时加入 `project.yml` 与 `MaimaiData.xcodeproj`？核心计算/筛选逻辑是否补充了 `MaimaiDataTests` 单元测试且 `xcodebuild build-for-testing` 零警告通过？
