# 定制版修改记录（SpekLab相机 · 无限好运）

本文件按 GPL-3.0-or-later 第 2(c) 条要求，标注本工程为**修改版**及其主要改动。

- 上游：Theia（deepspyder），https://github.com/deepspyder/theia ，基线 v0.1.8（提交 `2841a3a`）。
- 引擎内核：沿用上游官方预编译产物与同步资源，本定制版**未修改引擎 C++/Vulkan 源码**；引擎素材按上游 CC BY-SA 4.0 / 相应许可继续署名共享（见 NOTICE.md）。
- 本定制版分发的每一个 APK，其完整对应源代码始终发布于本仓库。

## 应用名称与包名

- 显示名：「SpekLab相机」（`strings.xml` app_name），工程名 SpeklabCam。
- applicationId：早期为兼容覆盖安装与已有编辑数据保留上游的 `com.spektralab.android`；
  2026-11-23 起改为独立包名 `com.spektralabcam.android`，以便与原版 Theia 并存安装，
  作者本机旧包数据已用 `run-as` + tar 全量迁移进新包（编辑历史、配方、设置均在）。
- Java/Kotlin 包名 `com.spektralab.android` 与上游版权头保持不变。

## 版本口径（2026-11-24 重新定义）

- 对外版本号用语义化 SemVer 2.0：`MAJOR.MINOR.PATCH`；`versionName` **只用干净短号**（`1.0.0`），不带 `+` 后缀——
  手机「应用信息」与商店详情会原样显示这一串，加了尾巴会让人以为是构建号。上游基座（Theia v0.1.8）单独写在关于页、本文与 Release 正文里。
- `versionCode` 从 **1000** 起，每发一个包 +1，只增不减、永不复用。
- git tag 用干净短号（`v1.0.0`），不带 `+` 与上游信息。
- 内部调试号（v87、v88 …）只用于协作记录，不进 tag、不进 Release 标题、不写进 App。
- 首发定为 **1.0.0（versionCode 1000）**；此前所有内部开发版本一律收回为开发记录，不再对外引用。
- 配方 JSON 有独立的规范版本（顶层 `version: 1`），与 App 版本解耦。
- 完整规则表见 [README.md](README.md#版本规则)。

## 渠道与合规边界

- 作者渠道：抖音「无限好运」<https://v.douyin.com/r0yaZLMMTU8/>；源码仓库 <https://github.com/lzfon/SpeklabCam>。
  这两个链接是**联系方式**，不是许可的附加条款；再分发时可换成你自己的联系方式，
  但上游版权声明、许可证全文与 CC BY-SA 素材的署名要求必须原样保留（见 NOTICE.md）。
- 各品牌手机的安装风险提示与申诉路线、自证清白的核对方法，集中在 [docs/install-guidance.md](docs/install-guidance.md)。

## 主要修复

- 曝光补偿失效：引擎自动测光会归一化掉手动 EV，现改为客户端按 2^EV 叠加（冲印页滑轨与智能偏移同时生效）。
- 导出文件名丢失：Android 照片选择器返回的是脱敏替身 URI（纯数字 id）。修复链＝选择器 path 段识别 + `MediaStore.getMediaUri` 映射回真实媒体 + `setRequireOriginal` 读未脱敏 EXIF + MediaStore 反查/时间尺寸兜底；映射 API 门槛由 34 修正为 30。
- 导出 GPS 丢失：系统仅在「导入那一刻」允许读原图位置。现于打开选择器**之前**申请 ACCESS_MEDIA_LOCATION，导入时抓取并存入本地位置库（GeoTagStore），导出时三路合并写回 EXIF。
- 拍 RAW 自动导入报「Unsupported file format」：只收录 MediaStore 已写完（IS_PENDING=0、大小>0、文件头可辨）的条目，并延迟补扫同一次拍摄的 RAW。
- 导出照片在相册显示的拍摄时间比实际快 8 小时（晚上拍、相册显示次日凌晨）：EXIF 的日期时间字符串本身不带时区标记，系统媒体库按 UTC 记账。现导出时①向媒体库显式写入 DATE_TAKEN（按原片本地时间换算的毫秒值）；②为 JPEG/PNG/HEIF/TIFF 四条导出路径补齐 EXIF 时区偏移标签 OffsetTime(0x882A)/OffsetTimeOriginal(0x9011)/OffsetTimeDigitized(0x9012)，原片若已带偏移则照抄原值；日期时间字符串本身不改写。

## 主要新增

- 冲印页：胶片模拟总开关（关掉直接数字调色）；胶片前曲线（EV 坐标，作用于胶片模拟之前）。
- 调色页：垂直/水平透视校正（±30°，带整组重置）；成片裁切（扳手菜单，裁成片不重算胶片物理量）。
- 预览：全屏预览双指缩放（1–6 倍，可回正）；预览右上角悬浮工具点（前后对比/全屏/收起）。
- 设置：默认胶片/相纸真正生效并与自动显影分工（具体型号＝锁定材料，自动＝交给智能选片）；预览质量、渲染缓存、诊断报告导出。
- 导出：HEIF（sRGB 8bit）、TIFF Deflate、色域四选（sRGB/Display P3/Adobe RGB/ProPhoto，写 ICC）、自定义长边/短边/像素总数、文件命名模板、导出配方（保存/管理）。
- 配方：我的配方重命名/删除/导入/导出（JSON，同名更新、新名新增）。
- 其它：跨会话撤销重做、批量导出、RAW 白平衡预设与拍摄值沿用、日期后背、片边、诊断与中文错误提示。

## 界面隐藏（代码保留）

DI TIFF、软打样、高清预览、原尺寸细节（效果细节）四个低使用价值入口已从界面移除；相关实现仍保留在源码中，可随时恢复。

## 交互约定

- 长按预览查看原图，松手恢复；
- 滑轨（含曲线点位滑轨）右侧图标＝单条复位；
- 所有失败给中文明确反馈；版本号只增不减以保留用户数据。

## 2026-11-23 追加（开发期记录，并入 1.0.0 发布）

### 导出成片时间被算错 8 小时（跨到第二天）

EXIF 的日期时间字符串本身不带时区标记，媒体库会按 UTC 记账，导致相册显示比实际拍摄时间快 8 小时（晚上拍、显示次日凌晨）。修复：

- JPEG / PNG / HEIF / TIFF 四条导出路径都补齐 `OffsetTime`(0x882A) / `OffsetTimeOriginal`(0x9011) / `OffsetTimeDigitized`(0x9012)；原片自带偏移时照抄原值，时间字符串本身不改写；
- 写入媒体库时显式提供 `DATE_TAKEN`（按原片本地时间换算的毫秒值），批量导出与中断任务重放同样按各自原片取值。

### 导出页与提示

- 去掉「已导出到相册」浮层提示（含中断任务重放那条），批量完成提示保留；
- 导出页改为四栏折叠结构（文件格式 / 保留信息 / 导出配方 / 高级设置），折叠箭头用主题色，折叠后标题右侧显示当前取值摘要；四个格式选项按要求不折叠；
- 删除底部「导出到相册 · 会话不是永久图库」一行说明。

### 与上游区分的独立标识

- `applicationId`：`com.spektralab.android`（上游）→ `com.spektralabcam.android`，本定制版 APK 与原版 Theia 可在同一台手机并存安装；`EditVersionProvider` 的 authority 同步调整；
- Gradle 根工程名：`Theia` → `SpekLabCam`；
- 构建脚本不再写死作者本机的引擎路径，改为 `local.properties` / Gradle property 的 `spektraEngineDir`（默认使用随仓库附带的上游预编译 `libspektralab.so`）；
- Java/Kotlin 包名 `com.spektralab.android` 与全部上游版权头保持不变（改动风险低、便于继续跟进上游）；
- 发布版改用发布者自建签名密钥签名；`keystore.properties`、`*.jks`、`local.properties` 一律不进仓库。

### 文档

- `README.md` 重写为定制版门面，上游原文保留为 `docs/README.upstream.zh.md` / `docs/README.upstream.en.md`；
- 新增 `COMPLIANCE.md`，逐条对照 GPL-3.0-or-later 与 CC BY-SA 4.0 的署名、源码提供、修改声明义务。

## 2026-11-24 追加（1.0.0 首发口径，对外发布用这一版）

- **版本号重新定义**：对外只用语义化版本，首发 `1.0.0`、`versionCode = 1000`；此前开发期间的版本号收回为内部记录，不再对外引用
  （数据兼容：包名与签名不变，覆盖安装无损）。
  `versionName` 不带 `+theia-v0.1.8` 这类后缀——手机「应用详情」会原样显示它；上游基座改为在关于页单独一行标注。
- **关于页重写**（`feature/about/AboutScreen.kt`）：把 README 的项目介绍搬进 App，新增「与上游的区别（特色功能）」逐条对照、
  「上游与借用的开源项目」全清单（Theia / spektrafilm / SpektraLab 色彩代码 / libspektralab.so / AndroidX·Compose / LibRaw /
  Resource Han Rounded / Lucide / LLVM OpenMP / volk / stb / Liberation 字体，各自许可标注）、作者渠道（GitHub + 抖音）、
  正式签名证书 SHA-256 指纹与自证方法（哈希、权限、自行编译）；版本号改为运行时从安装包读取，界面上不会再出现写死的版本。
  排版调整：源码仓库块紧跟在抖音介绍之后；标题改「本版作者」；页面上不再出现「定制版」字样；版本区显示「上游内核 Theia v0.1.8」独立一行。
- **官方配方公开**：新增 `presets/speklabcam-recipes-v1.json`（8 支：港片 6 + 《山河故人》2）与 `docs/recipes.md`（逐支详细说明）。
  安装包仍然**不内置任何配方**（`BuiltinPresets.seeds()` 返回空表），配方一律由用户下载后自行导入。
  这些数值为本版作者手工调定，未复制上游任何 profile / LUT / .3fr 数值；仓库内不含任何剧照、海报或影片画面。
- **新增 `docs/install-guidance.md`**：各品牌安装风险提示的成因（含 2026 年 Google ADV 与小米侧载校验）、分品牌继续安装步骤、
  哈希与签名核对、VirusTotal 与逐厂商误报申诉渠道、长期解法（ADV 注册 / 厂商应用验证 / APP 备案 / 商店上架）与时间预期。
- **README 与 NOTICE**：仓库地址定为 `https://github.com/lzfon/SpeklabCam`，作者署名统一为「无限好运」，
  并明确个人渠道链接只是联系方式、不构成许可附加条款；新增「版本规则」「官方配方」「隐私」「安装」四节；NOTICE 增写配方文件的授权与"不含剧照"声明。
- 签名证书的 DN 字段（`CN=SpeklabCam, OU=Wireless Good Luck`）为已固化信息，改名需换密钥、会导致老用户无法覆盖升级，因此保持不变；
  对外署名一律以 README / NOTICE / 关于页为准。
