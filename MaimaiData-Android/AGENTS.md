# MaimaiData-Android 架构与开发规范 (Agent & Developer Guide)

本文档定义 `MaimaiData-Android`（Kotlin + Jetpack Compose + Material 3）的分层职责、设计令牌、公共组件选型及核心业务不变量。所有功能与业务规则与同级目录 `../MaimaiData-iOS` 及 `../SHARED_SPEC.md` 保持 1:1 严格对齐，同时在交互细节上遵循原生 Material Design 3 极简规范。

---

## 1. 目录结构与分层边界

```text
app/src/main/java/dev/sorrowrain/maimaidata/
├── MaimaiDataApplication.kt         # Application 入口（初始化全局 DataStore 单例与 Coil ImageLoader）
├── MainActivity.kt                  # Edge-to-Edge Activity 入口 + 落雪 OAuth Deep Link onNewIntent 分发
├── theme/
│   └── AppTheme.kt                  # Material 3 主题封装、锁定街机色板 Palette、间距 Space、圆角 Radius、字阶 AppTypography
├── components/                      # 跨页面复用的标准 Compose 组件（严禁在 views/ 内重复编写野生通用组件）
│   ├── Badges.kt                    # 胶囊标签、统计砖块、难度定数徽章、分类胶囊、谱面类型角标、封面框、筛选 Chip
│   ├── Buttons.kt                   # 主/次操作按钮、行内胶囊按钮 AppInlineButton、菜单触发胶囊 AppMenuCapsule、圆形图标按钮、统一分段选择器 AppSegmentedControl
│   ├── Checklist.kt                 # 进度表顶部完成度统计头 ChecklistStatsHeader、自适应网格 ChecklistSongGrid 与曲绘网格单元 ChecklistSongTile
│   ├── Containers.kt                # AppCard / AppSectionCard、内嵌分组 AppInsetGroup（通栏分割线 AppInsetDivider + 开关行 AppInsetToggleRow + 矩形行高亮）、档案单行标签
│   ├── RatingTierTable.kt           # 分段达成率与单曲分 / B50 净涨分三列对照表（详情页与计算器共用）
│   ├── Feedback.kt                  # 底部状态轻提示 ToastState / StatusToastHost、同步状态横幅、错误/确认弹窗 AppErrorDialog / AppConfirmDialog、加载/空状态占位 AppLoadingState / AppEmptyState
│   └── Forms.kt                     # 标准输入框 AppTextField / AppSecureField、无刻度极简离散滑条 AppSlider、带标签滑条行 AppLabeledSliderRow
├── bridges/
│   └── SystemBridges.kt             # MediaStore 保存相册、FileProvider 系统分享、外部链接打开器 ExternalLinkLauncher、双指/双击缩放图片容器 ZoomableImageBox 与全屏预览弹窗 PhotoPreviewDialog
├── models/
│   ├── SongData.kt                  # 曲目与谱面模型、物量解析 ResolvedChart、多源封面候选地址与同曲别名融合
│   ├── DifficultyType.kt            # 谱面难度枚举及浅色/深色对比度适配色
│   ├── MaimaiConstants.kt           # 分类、等级、三区服版本常量与 PRiSM / CiRCLE 评级贴图资源映射
│   ├── RatingCalculator.kt          # 单曲算分（含外服 AP +1）、目标总分二分反推、全曲库最大物量统计
│   ├── RecordModels.kt              # 多源成绩档案 ScoreProfile、成绩记录、进度统计与筛选条件模型
│   ├── LXNSModels.kt                # 落雪 OAuth / 成绩 / 曲库模型与三区服通用牌子范围模型 PlateCheckScope
│   └── Extensions.kt                # 颜色提亮扩展、版本号按段数值比较 VersionString
├── services/
│   ├── DataStore.kt                 # 全局状态中心（StateFlow 响应式状态、三级防刷全量同步、多区服与多源档案管理、B50 拆表）
│   ├── MaimaiAPI.kt                 # 无状态 OkHttp 网络层（落雪公共接口与 OAuth、水鱼账密/Import-Token/拟合定数、Zetaraku & DXRating）
│   ├── LXNSCatalogConverter.kt      # 落雪公共曲库/双人宴谱转换、三区服牌子规则构建与落雪成绩转换纯函数
│   ├── ZetarakuCatalogConverter.kt  # 日服/国际服曲库纯函数转换器（关联国服 ID/别名/旧定数、国际服覆写与两代 B15 判定）
│   ├── AccountCredentialStore.kt    # AndroidKeyStore (AES-256-GCM) 多源档案凭据加密存储与旧数据迁移辅助
│   ├── ImageLoader.kt               # Coil 3 多源降级曲绘加载组件 CoverImageView 与磁盘缓存体积管理
│   ├── Best50Renderer.kt            # Best 50 成绩图原生 Bitmap + Canvas 离屏渲染引擎
│   ├── SongFilter.kt                # 纯函数曲库多维筛选与排序
│   └── PreferenceKeys.kt            # SharedPreferences 键名常量
└── views/                           # 业务页面（仅负责页面级状态连接与标准组件组装）
    ├── MainTabView.kt               # Material 3 NavigationBar 底部三栏与全局 NavigationHost
    ├── SongListView.kt              # 曲目列表（隐藏行内 BPM）、胶囊搜索栏、最近搜索与已筛选摘要
    ├── SearchFilterSheet.kt         # ModalBottomSheet 多维筛选弹窗
    ├── SongDetailView.kt            # 曲目详情、难度切换、拟合定数、物量条、宴谱说明与三列分段算分表
    ├── CoverZoomView.kt             # 高清封面全屏缩放预览、保存相册与系统分享弹窗
    ├── RatingToolsView.kt           # Rating 与查分主页（静态玩家名称 + 同步成绩、成绩牌子入口、算分与旧框换算工具入口）
    ├── RatingCalculatorView.kt      # 定数双滑块分段算分 + 目标总分反推二级页
    ├── ProberView.kt                # B35 / B15 列表、PullToRefreshBox 下拉刷新与 B50 图片生成预览
    ├── VersionCheckView.kt          # 国服 / 国际服 / 日服统一牌子进度表（右上切换牌子、完成度栏切换难度）
    ├── LevelCheckView.kt            # 1～15 级定数分组全难度进度表
    ├── FinaleToDxView.kt            # 旧框判定换算 DX 达成率工具
    ├── SettingsView.kt              # 两级极简设置页（游戏区服 ->「曲库区服」、检索偏好、数据同步与存储、致谢）
    ├── AccountManagementView.kt     # 多源成绩档案管理（「成绩档案」+「B50 图片玩家名称」）
    └── LoginSheet.kt                # ModalBottomSheet 添加成绩档案弹窗（落雪 OAuth / 个人密钥、水鱼账密 / Import-Token）
```

---

## 2. 核心设计规范与不变量

1. **禁止野生通用组件**：凡在 2 个及以上页面复用的 UI 单元，一律沉淀在 `components/`；禁止在 `views/` 中散落手写 `Color(0xFF...)` 字面量，统一使用 `AppTheme` 与 `MaterialTheme.colorScheme`。
2. **内嵌列表几何规范**：`AppInsetGroup` 统一裁切 `Radius.inset`（`12.dp`），行间统一使用通栏 `HorizontalDivider`（禁止左侧缩进留白），选中态统一使用 `Modifier.appInsetRowHighlight` 绘制显式矩形底色。
3. **中文输入法与并发不变量**：
   - `SongFilterCriteria.isActive` 严禁包含 `keyword.isNotEmpty()`，防止中文输入法上屏瞬间筛选摘要卡片闪动。
   - 落雪 OAuth `refresh_token` 严格使用按档案 UUID 隔离的 `Mutex` 单飞刷新并立即落盘。
   - 下拉刷新网络请求运行在独立的 `SupervisorJob` 作用域中，防止界面重绘取消请求；异步加载本地成绩缓存受 `recordsEpoch` 保护。
