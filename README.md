# MaimaiData

一款**双端纯原生**的舞萌 DX（maimai DX）曲库检索与查分工具，采用 **SwiftUI（iOS）** 与 **Jetpack Compose（Android）** 1:1 镜像同构开发，提供统一、克制、流畅的多区服曲库与成绩管理体验。

## 仓库结构

```text
MaimaiData/
├── MaimaiData-iOS/        # iOS 客户端（SwiftUI，iOS 17.0+，支持 iOS 26+ Liquid Glass）
├── MaimaiData-Android/    # Android 客户端（Jetpack Compose + Material 3，Android 8.0+ / API 26+）
├── SHARED_SPEC.md         # 双端共享数据源、三级节流、多区服算分与牌子规则契约
├── AGENTS.md              # 双端防偏移协同开发规范
└── LICENSE                # Apache License 2.0
```

## 核心功能

- **多区服曲库与多维检索**：支持 **国服（CN）/ 国际服（INTL）/ 日服（JP）** 实时切换；支持曲名、别名、谱师、曲目 ID 搜索，支持按等级、精确小数定数、歌曲分类、对应区服版本与收藏状态组合筛选及多难度排序。
- **谱面详情与容错分析**：展示各难度定数（含跨服国服定数对比与水鱼拟合定数）、物量分布条、Tap / Hold / Slide / Break 单项容错损失参考，以及高清曲绘缩放预览与相册保存。
- **多供应方成绩档案**：支持 **落雪咖啡屋（OAuth 2.0 + PKCE / 个人 API 密钥）** 与 **水鱼查分器（账号密码 / Import-Token）** 多账号接入与毫秒级本地切换；凭据分别由 iOS Keychain 与 Android 应用私有存储隔离保护。
- **Rating 计算与 Best 50 出图**：支持 B35 / B15 分段列表与 **Best 50 高清成绩图导出**；支持按日服 / 国际服新规（`AP +1` 与双代 B15）实时重排模拟。
- **版本牌子与定数进度表**：支持国服官方牌子、日服 / 国际服旧框（含真超紫菫白輝舞/覇者）与 DX 全世代版本完成度进度表，以及 1～15 级定数完成度清单。

## 下载与安装

前往 **[GitHub Releases](https://github.com/SorrowRain/MaimaiData/releases)** 下载对应平台安装包：

- **Android**：下载已签名的 `.apk` 安装包直接安装。
- **iOS**：支持 **TestFlight**（加 QQ 交流群 **`1016143695`** 获取）；Release 也提供未签名的 `-unsigned.ipa`，可使用 AltStore 或 SideStore 签名安装，也可用 Xcode 打开 `MaimaiData-iOS/MaimaiData.xcodeproj` 编译到真机。

## 本地构建与测试

### iOS (`MaimaiData-iOS/`)

- **环境要求**：Xcode 26+；应用支持 iOS 17.0+
- **单元测试**（27 项用例）：

```bash
cd MaimaiData-iOS
xcodebuild test \
  -project MaimaiData.xcodeproj \
  -scheme MaimaiData \
  -destination 'platform=iOS Simulator,name=iPhone 16'
```

### Android (`MaimaiData-Android/`)

- **环境要求**：JDK 17 / 21，Android SDK Platform 35
- **单元测试与构建**（27 项用例）：

```bash
cd MaimaiData-Android
./gradlew testDebugUnitTest assembleDebug
```

- **正式签名打包**：在 `MaimaiData-Android/` 下放置本地 `keystore.properties` 与 `.jks` 密钥库后执行 `./gradlew assembleRelease`（密钥文件已在 `.gitignore` 中排除）。

## 致谢与参考

- **PaperPig / MaimaiData**（[GitHub](https://github.com/PaperPig/MaimaiData)）：早期功能形态与部分静态贴图素材参考
- **LXNS（落雪咖啡屋）**（[maimai.lxns.net](https://maimai.lxns.net)）：国服官方曲库、别名、官方牌子规则、OAuth 接口与高清曲绘源
- **Diving-Fish（水鱼查分器）**（[GitHub](https://github.com/Diving-Fish/maimaidx-prober)）：成绩查分接口、全服拟合定数统计与曲绘兜底
- **zetaraku / arcade-songs-fetch**（[GitHub](https://github.com/zetaraku/arcade-songs-fetch)）：日服与国际服多区服曲库与外服封面源
- **DXRating**（[GitHub](https://github.com/gekichumai/dxrating)）与 **Yuri-YuzuChaN/maimaiDX**（[GitHub](https://github.com/Yuri-YuzuChaN/maimaiDX)）：底层数字 ID 映射与社区别名素材支持

## 开源许可

本项目基于 **[Apache License 2.0](./LICENSE)** 协议开源。

Copyright © 2026 SorrowRain.
