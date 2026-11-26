# MaimaiData Re:Master 双端共享业务与接口契约 (iOS & Android Parity Spec)

本文档约束 `MaimaiData-iOS`（SwiftUI）与 `MaimaiData-Android`（Jetpack Compose）在数据源、算分公式、多区服切换、牌子规则及防刷节流上的 **100% 行为一致性**。任何一端变更以下规则时，必须双端同步更新并通过对应单元测试。

---

## 1. 数据源与三级节流策略

| 数据类别 | 接口地址 | 本地种子 / 缓存 | 自动更新周期 | 手动触发冷却 |
| :--- | :--- | :--- | :--- | :--- |
| **国服曲库与物量** | `https://maimai.lxns.net/api/v0/maimai/song/list?notes=true` | 内置 `songdata_seed.json` -> `songdata.json` | 7 天 | 10 分钟 |
| **国服歌曲别名** | `https://maimai.lxns.net/api/v0/maimai/alias/list` | 合并入 `songdata.json` | 7 天 | 10 分钟 |
| **国服官方牌子规则** | `https://maimai.lxns.net/api/v0/maimai/plate/list` | 内置 `plate_rules_seed.json` -> `plate_rules.json` | 7 天 | 10 分钟 |
| **日服/国际服曲库** | `https://dp4p6x0xfi5o9.cloudfront.net/maimai/data.json` | `zetaraku_data.json` | 7 天（首次切外服自动拉取） | 10 分钟 |
| **外服独占曲目 ID** | `https://miruku.dxrating.net/api/v1/dxdata` | `dxdata_id_map.json` | 随 Zetaraku 并发更新 | 10 分钟 |
| **水鱼拟合定数** | `https://www.diving-fish.com/api/maimaidxprober/chart_stats` | `chart_stats.json` | 5 天 | 5 分钟 |
| **曲绘 CDN 优先级** | 国服曲：`assets2.lxns.net/maimai/jacket/{baseId}.png` -> 水鱼 5 位补零兜底；外服独占曲：Zetaraku CloudFront `img/cover/{imageName}` | 内存 + 磁盘双层永久缓存 | 按需懒加载 | 手动清除缓存 |
| **客户端版本检查** | `https://api.github.com/repos/SorrowRain/MaimaiData-ReMaster/releases/latest` | `PreferenceKeys.appUpdateCheck` | 冷启动即检 + 前台 2 小时节流（Android 全渠道；iOS TestFlight 与自签/开发渠道） | 「关于」页手动点击即时检测 |

- 节流命中提示统一为 **`当前已是最新数据`**，全量曲库与规则更新成功提示统一为 **`已更新至最新数据`**，拟合定数刷新成功提示统一为 **`拟合定数已刷新`**。

---

## 2. 多源玩家身份、国服直连与凭据安全

- **统一成绩档案 (`PlayerIdentity` v3)**：
  - 每个本地成绩档案拥有独立的 UUID（小写），并可同时绑定 **国服直连 (`selfHosted`)**、**落雪咖啡屋 (`lxns`)**、**水鱼查分器 (`divingFish`)** 三个渠道，共享同一份本地成绩单，支持按档案独立设置自定义名称 (`customNickname`) 与 B50 玩家图片名称 (`b50PlayerName`)。
  - **加密隔离存储与内置直连配置**：闭源同步后端域名与密钥于编译期通过 `Local.xcconfig`（iOS `Info.plist`）/ `local.properties`（Android `BuildConfig`）注入并由 `SyncServiceConfig` 解析，界面不暴露任何服务器地址/密钥输入；启动时主动清理历史遗留的 `self_hosted_endpoint_v1` 槽位。档案元数据存入 `player_identities_v3`，各渠道密钥/令牌分别隔离存入 `identity_sh_<uuid>` / `identity_lxns_<uuid>` / `identity_df_<uuid>`（iOS Keychain / AndroidKeyStore AES-256-GCM）。读取安全存储失败时触发 `loadFailed` 写保护锁，禁止覆写档案与凭据。
  - **成绩与趋势快照隔离**：本地成绩缓存按档案 UUID 隔离为 `records_identity_<uuid>.json`（由 v2 `ScoreProfile` 1:1 无启发式升级而来），每次同步后仅在国服曲库已加载时追加纯净国服 `cnSongsById` B50 趋势快照至 `history_identity_<uuid>.jsonl`（`SyncHistoryStore`，写入前修复残缺尾行，首次完整同步标记 `isBadgeBaseline = true`）；历史遗留全局 `customNickname` 通过 `migrateLegacyB50Nickname` 自动迁移至所有未设置自定义名称的档案（无覆写）；各档案通过 `hasMixedScoreSources` 标记防止解绑或切端后丢失其它渠道成绩。
  - **已删除国服曲目解析层与纯净国服上传过滤**：同步结果包含国服当前曲库缺失且 ID 已打过的曲目时，仅在国服曲库新鲜确认后通过 Zetaraku / DXDataIdMap 解析还原基础元信息（跳过 `regions.cn == true` 与 `CiRCLE`/`CiRCLE PLUS`/`MAGiCAL` 版本曲目，避免国服新曲误判删除）并缓存为 `deleted_cn_songs.json`（列表与详情标注「已删除」，国服 Rating 快照与上传自动跳过），仅展示 `playedSongIds` 中命中的删除曲；绑定查分器写入云端前严格 Fail-Closed 校验 `cnSongsById` 非空且单曲可上传（水鱼写回需先成功拉取 `music_data` 曲名映射；排除 `0%`、已删除曲与 `isDisabled` 谱面），并在 `WritebackSummary` 中统计跳过条数。
- **同步模式、多端并发合并与管理层级**：
  1. **统一同步调度 (`SyncPlan` / `pullMergedProbers`)**：
     - 已绑定国服直连时，「同步成绩」默认执行国服直连 **快速同步**；仅绑定双查分器且未绑定国服直连时，并发拉取落雪与水鱼成绩并执行本地最优合并 (`PULL_MERGED`)，单一渠道失败仍保留另一端成功结果。
     - **绑定保护与诊断日志**：已绑定国服直连状态下绑定或刷新查分器时禁止覆写国服本地权威成绩；同步与写回过程关键事件与脱敏摘要统一记录至 `SyncDiagnostics`（可在设置页复制导出）。
  2. **国服直连 (`selfHosted`)**：
     - 首次绑定默认开启 **完整同步 (`full`)**；后续在档案详情页 (`IdentityDetailView`) 显式区分 **快速同步** 与 **完整同步**。
     - **快速同步 (`quick`)**：通过 `syncToken` 拉取成绩与基础档案，由 `ScoreSyncEngine.mergeQuickSync` 与本地已有成绩合并，保留较高达成率、DX 分数与本地已有 FC/FS 徽章（若 `achievements >= 101.0000%` 自动提升为 `app`）。
     - **完整同步 (`full`)**：携带有效二维码内容覆盖本地成绩状态（含权威 FC/FS）。
     - **未知新曲兜底**：同步结果含本地未知 `songId` 时，触发带 **12 小时冷却**的国服曲库刷新与删除曲解析并重投射当前激活成绩。
  3. **落雪咖啡屋 (`lxns`)**：
     - 主认证方式为 **OAuth 2.0 + PKCE (S256)**（`client_id = "9e67172c-d7b9-4bcf-b8a9-185b8afe1b63"`，`redirect_uri = "maimaidata://oauth/lxns"`，Scopes = `read_player write_player openid profile`），二级入口提供 **个人 API 密钥**（`X-User-Token`）；`refresh_token` 严格执行**按档案 ID 单飞互斥轮换**并立即持久化；若仅授予只读 Scope 则禁止上传并提示重授。
     - **增量上传**：`POST /api/v0/user/maimai/player/scores`，支持宴谱 `id >= 100000` (`type = "utage"`, `level_index = 0`，Buddy 1P/2P 归并去重)、仅上传严格优于云端的记录且绝不降级云端徽章，跳过 `0%` 及国服删除曲。
  4. **水鱼查分器 (`divingFish`)**：
     - 主认证方式为 **账号密码登录**（`POST /api/maimaidxprober/login` 提取 `jwt_token` Cookie，拉取 `/player/records`），二级入口提供 **成绩导入密钥**（请求头 `Import-Token`）；上传调用 `POST /api/maimaidxprober/player/update_records`（自动过滤宴谱、`0%` 及国服删除曲，`400` 数据错误不重试登录，仅 `401/403` 尝试重登）。
- **宴谱规范索引与区服投射**：宴谱记录统一使用 `levelIndex = 10`（单人/1P）与 `11`（Buddy 2P）、`type = "utage"`、`computedRating = 0`；展示曲目、定数与算分规则仍由外层游戏区服（国服 `cn` / 国际服 `intl` / 日服 `jp`）实时投射决定，切服查曲不污染本地国服成绩归属。

---

## 3. 多区服算分与牌子规则

1. **B15 与 AP 加分**：
   - 国服（`cn`）：`isNew == true`（现行大版本），无 AP 额外加分。
   - 日服（`jp`）：`MAGiCAL` + `CiRCLE PLUS` 两代半版本计入 B15，`AP / AP+` 单曲 Rating 额外 `+1`。
   - 国际服（`intl`）：`CiRCLE PLUS` + `CiRCLE`（及未来 `MAGiCAL`）计入 B15，`AP / AP+` 单曲 Rating 额外 `+1`。
   - 只要存在激活成绩档案（`isLoggedIn`），三个区服下的歌曲详情页「分段 Rating 对照」均完整展示三列（`达成率 / 等级`、`单曲分`、`B50 可加`）。
2. **三区服牌子进度表**：
   - 国服直接读取 `plate_rules.json` 官方规则 + 当代在稼动版本预览。
   - 日服与国际服完整支持旧框 `真`（排除 `70` `ジングルベル`）、`超`～`紫`、`菫`（排除 `853` `前前前世`）、`白`～`輝`、`舞 / 覇者`（排除 `前前前世`，且仅开放旧框时代 65 首 `Re:MASTER` = 国服 59 首 `∪ [146, 668, 731, 792, 826, 827]`），以及 DX 时代每半代一牌（`熊`、`華`、`爽`、`煌`、`宙`、`星`、`祭`、`祝`、`双`、`宴`、`鏡`、`彩`、`丸`、`廻`）与当代新作预览。
