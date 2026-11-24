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
| **曲绘 CDN 优先级** | 国服曲：`assets2.lxns.net/maimai/jacket/{baseId}.png` -> 水鱼 5 位补零兜底；外服独占曲：Zetaraku CloudFront `img/cover-m/{imageName}` | 内存 + 磁盘双层永久缓存 | 按需懒加载 | 手动清除缓存 |
| **客户端版本检查** | `https://api.github.com/repos/SorrowRain/MaimaiData-ReMaster/releases/latest` | `PreferenceKeys.appUpdateCheck` | 24 小时静默检测（Android 全渠道；iOS 仅自签/开发渠道） | 「关于」页手动点击即时检测 |

- 节流命中提示统一为 **`当前已是最新数据`**，全量曲库与规则更新成功提示统一为 **`已更新至最新数据`**，拟合定数刷新成功提示统一为 **`拟合定数已刷新`**。

---

## 2. 多源成绩档案与 OAuth / 凭据安全

- **支持供应方**：
  1. **落雪咖啡屋 (`lxns`)**：
     - **OAuth 2.0 + PKCE (S256)**：`client_id = "9e67172c-d7b9-4bcf-b8a9-185b8afe1b63"`，`redirect_uri = "maimaidata://oauth/lxns"`，Scopes = `read_user_profile+read_player`；`refresh_token` 严格执行**按档案 ID 单飞互斥轮换**并立即持久化。
     - **个人 API 密钥**：请求头 `X-User-Token`。
  2. **水鱼查分器 (`divingFish`)**：
     - **账号密码登录**：`POST /api/maimaidxprober/login` 提取 `jwt_token` Cookie，拉取 `/player/records`。
     - **成绩导入 Token**：请求头 `Import-Token` 拉取 `/player/records`。
- **档案与区服解耦**：成绩档案本地缓存为 `records_profile_<uuid>.json`，切换档案纯本地毫秒级切换；展示曲目、定数与算分规则由外层选定的游戏区服（国服 `cn` / 国际服 `intl` / 日服 `jp`）实时投射决定。

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
