# 本版改动记录（SpekLab相机 · 无限好运）

本文件按 GPL-3.0-or-later 第 2(c) 条要求，标注本工程为**修改版**及其主要改动。

- 上游：Theia（deepspyder），https://github.com/deepspyder/theia ，基线 v0.1.8（提交 `2841a3a`）。
- 引擎内核：沿用上游官方预编译产物与同步资源，本版**未修改引擎 C++/Vulkan 源码**；引擎素材按上游 CC BY-SA 4.0 / 相应许可继续署名共享（见 NOTICE.md）。
- 本版分发的每一个 APK，其完整对应源代码始终发布于本仓库。

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
- 当前对外发布：**1.1.0（versionCode 1003）**，tag `v1.1.0`，2026-11-28。MINOR 版：界面重排
  （首页与底栏、编辑页三层菜单与控制条、图库计数行）加一批行为与数据安全修正（单击不再清空工作台、
  缩略图显示成品效果图、直方图改开根号刻度、工作台默认不再自动收起），影像算法、配方文件与侧车格式未改，
  明细见下面「2026-11-28 追加」与 `docs/release-notes-v1.1.0.md`。
  上一版 1.0.2（versionCode 1002，2026-11-26）只修两个 bug（RAW 被当位图导出只出内嵌预览小图、
  相册名不回显并补「未分组」筛选），见再上面「2026-11-26 追加」。
- 配方 JSON **不编版本号**：固定一个文件名 `presets/speklabcam-recipes.json`，内容改了就直接改它，历史交给提交记录，不跟着 App 发版另开 `-v2`。
  顶层 `kind` 是 app 判定「是不是本软件配方文件」的格式标识（`version: 1` 是内部存储格式字段，导入时不校验），都不表示配方的迭代版本。
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

- `applicationId`：`com.spektralab.android`（上游）→ `com.spektralabcam.android`，本版 APK 与原版 Theia 可在同一台手机并存安装；`EditVersionProvider` 的 authority 同步调整；
- Gradle 根工程名：`Theia` → `SpekLabCam`；
- 构建脚本不再写死作者本机的引擎路径，改为 `local.properties` / Gradle property 的 `spektraEngineDir`（默认使用随仓库附带的上游预编译 `libspektralab.so`）；
- Java/Kotlin 包名 `com.spektralab.android` 与全部上游版权头保持不变（改动风险低、便于继续跟进上游）；
- 发布版改用发布者自建签名密钥签名；`keystore.properties`、`*.jks`、`local.properties` 一律不进仓库。

### 文档

- `README.md` 重写为本版门面，上游原文保留为 `docs/README.upstream.zh.md` / `docs/README.upstream.en.md`；
- 新增 `COMPLIANCE.md`，逐条对照 GPL-3.0-or-later 与 CC BY-SA 4.0 的署名、源码提供、修改声明义务。

## 2026-11-24 追加（1.0.0 首发口径，对外发布用这一版）

- **版本号重新定义**：对外只用语义化版本，首发 `1.0.0`、`versionCode = 1000`；此前开发期间的版本号收回为内部记录，不再对外引用
  （数据兼容：包名与签名不变，覆盖安装无损）。
  `versionName` 不带 `+theia-v0.1.8` 这类后缀——手机「应用详情」会原样显示它；上游基座改为在关于页单独一行标注。
- **关于页重写**（`feature/about/AboutScreen.kt`）：把 README 的项目介绍搬进 App，新增「与上游的区别（特色功能）」逐条对照、
  「上游与借用的开源项目」全清单（Theia / spektrafilm / SpektraLab 色彩代码 / libspektralab.so / AndroidX·Compose / LibRaw /
  Resource Han Rounded / Lucide / LLVM OpenMP / volk / stb / Liberation 字体，各自许可标注）、作者渠道（GitHub + 抖音）、
  正式签名证书 SHA-256 指纹与自证方法（哈希、权限、自行编译）；版本号改为运行时从安装包读取，界面上不会再出现写死的版本。
  排版调整：源码仓库块紧跟在抖音介绍之后；标题改「本版作者」；副标题不再附加版本限定词；版本区显示「上游内核 Theia v0.1.8」独立一行。
- **官方配方公开**：新增 `presets/speklabcam-recipes-v1.json`（8 支：港片 6 + 《山河故人》2）与 `docs/recipes.md`（逐支详细说明）。
  安装包仍然**不内置任何配方**（`BuiltinPresets.seeds()` 返回空表），配方一律由用户下载后自行导入。
  这些数值为本版作者手工调定，未复制上游任何 profile / LUT / .3fr 数值；仓库内不含任何剧照、海报或影片画面。
- **新增 `docs/install-guidance.md`**：各品牌安装风险提示的成因（含 2026 年 Google ADV 与小米侧载校验）、分品牌继续安装步骤、
  哈希与签名核对、VirusTotal 与逐厂商误报申诉渠道、长期解法（ADV 注册 / 厂商应用验证 / APP 备案 / 商店上架）与时间预期。
- **README 与 NOTICE**：仓库地址定为 `https://github.com/lzfon/SpeklabCam`，作者署名统一为「无限好运」，
  并明确个人渠道链接只是联系方式、不构成许可附加条款；新增「版本规则」「官方配方」「隐私」「安装」四节；NOTICE 增写配方文件的授权与"不含剧照"声明。
- 签名证书的 DN 字段（`CN=SpeklabCam, OU=Wireless Good Luck`）为已固化信息，改名需换密钥、会导致老用户无法覆盖升级，因此保持不变；
  对外署名一律以 README / NOTICE / 关于页为准。
- **文档措辞统一**：`NOTICE.md` / `CHANGELOG-custom.md` / `COMPLIANCE.md` 里旧的自称用词一律改为「本版 / 修改版」，
  与关于页口径一致。纯文档改动，不影响 1.0.0 安装包，也不移动 tag `v1.0.0`。

## 2026-11-24 追加二（已发布 · 随 `1.0.1` / versionCode 1001，tag `v1.0.1`）

- **修复：日期后背把「导入当天」当成拍摄日期。**
  现象：当天导入的照片，印出来的是今天而不是实际拍摄日期；把日期后背关掉再打开才显示正确。
  根因：`EditorViewModel.openPhoto()` 里先对临时 `params` 调了一次 `DateImprintMetadata.applyTo()`，
  随后 `params` 又被「恢复侧车」或「新建默认参数」整对象替换掉，填进去的日期跟着一起被丢弃；
  字段于是为空，`EditorParams.effectiveDateText()` 的「空 = 退回系统当天」兜底生效，印的就是今天。
  关掉再打开会调用 `setDateImprintEnabled(true)` → 再次 `applyTo()`，这次作用在最终 `params` 上，所以看起来"动一下就对了"。
  改动：① `openPhoto()` 把 `applyTo()` 挪到 `params` 定稿之后、`seedHistory()` 之前（撤销栈与侧车从第一帧起就带正确日期）；
  ② `resetAll()`（重置全部参数）同样补一次 `applyTo()`，避免重置后退回当天；
  ③ EXIF 完全没有拍摄日期时（部分 RAW 解析失败、元数据被聊天工具剥掉），退回相册记录的 `DATE_TAKEN`，
  两者都读不到才用系统当天——绝不拿文件修改时间冒充拍摄日期；
  ④ 参数说明（`HelpText` 9.1）同步这一取数顺序。
  本条随 `1.0.1`（tag `v1.0.1`）发布；`v1.0.0` 那个 APK **不含**本修复，用旧包的用户装上 1.0.1 即可。


## 2026-11-24 追加三（已发布 · 随 `1.0.1` / versionCode 1001，tag `v1.0.1`）

- **片边随机身份重做：相机种子 / 过片种子 / 画幅编号统一成「0 = 随机，1-N = 按设定输出」。**
  - 相机种子、过片种子：滑轨 0..999，默认 0。0 = 这一张在 1..999 里随机一套磨损；拖回 0 再随机一次；
    手动停在 1..999 就按这个数输出（同一颗机身的磨损可复现）。
  - 画幅编号：滑轨 0..36，默认 0。0 = 这一张在 1..36 里随机一号；手动停在 1..36 按设定输出。
  - **删除**原来的「按导入数量整卷 36 帧自动递增」与「手动画幅编号」开关（`overscanFrameNumberAuto` 那套预排逻辑一并移除）。
  - 随机到的那一号会写进侧车（`overscan_frame_number_auto` / `overscan_camera_seed_auto` / `overscan_frame_seed_auto`），
    所以预览看到什么、导出就是什么；老侧车里没这三项时，用照片 uri 派生的稳定身份补齐，重开照片不会换一套磨损。
  - 「同步设置到其它照片」不再搬运这三号，否则全组照片会变成同一套磨损、同一个编号。
- **批量导出的片边重新编号。** 点「导出所选照片」不再直接开跑：先统计这批勾选里有多少张开了片边，
  弹框告知「勾选 N 张，其中 M 张带片边」并预览编号方案，带片边的按勾选顺序从 01 开始连号、排到 36 回到 01 继续，
  不带片边的不参与；点「开始导出」才把新号写进各照片自己的参数并开始导出，点「取消」什么都不改。
  单张导出不受影响，仍按这一张自己的「随机 / 手动」结果输出。
- **参数说明同步**：`HelpText` 8.10 / 8.11 重写为新口径。
- 本节与下一节（2026-11-25）一起随 `1.0.1`（tag `v1.0.1`）发布；`v1.0.0` 那个 APK **不含**这两节改动。


## 2026-11-25 追加一（已发布 · 随 `1.0.1` / versionCode 1001，tag `v1.0.1`）

接上一节的两点收口：名称改回、批量重编号改成显式开关。

- **名称：「画幅编号」改回「帧号」。** 冲印页片边栏的滑轨标题、参数说明 8.10、批量导出确认框、代码注释全部统一为「帧号」，
  与栏目标题「胶片格式 · 齿孔 · 雾化 · 漏光 · 帧号」一致；`overscan_frame_number` 这一字段名不变（侧车兼容）。
- **批量重编号改为显式开关，默认关闭。** 导出页（批量）在「导出所选照片」按钮上方新增开关「带片边照片按帧号顺序导出」：
  - **关闭（默认）**：点「导出所选照片」直接按每张照片自己的设定导出，不统计、不改写任何参数；
  - **打开**：点按钮才先统计带片边的张数，弹框确认后把这几张按勾选顺序从 01 连号（排到 36 回到 01 继续），
    不带片边的不参与。
  上一节里「批量导出必做重编号」的行为就此取消，重编号不再默默改写用户手动设过的号。
- **打开该开关时，相机种子与过片种子一并强制按 1 输出**（`EditorParams.SEQUENTIAL_SEED`）：
  整批同一颗机身、同一次扫描，连起来的片边才接得上；不带片边的照片两颗种子不动。
  改写落在各照片自己的侧车里，导出后重开照片看到的就是新号与种子 1。
- **确认框文案定稿**（标题「片边连续帧号」）：
  > 勾选 x 张，其中 y 张带片边。
  > 这 y 张按勾选顺序从 01 开始连号，排到 36 回到 01 继续；不带片边的 z 张不参与重新编号。
  > 重新编号会覆盖这些照片原设定帧号。
  原先的逐张编号预览（`#3→02`）按定稿文案删除。
- **导出页四栏默认全部收起**（文件格式 / 保留信息 / 导出配方 / 高级设置），点标题展开；
  四个格式按钮仍常显在栏头外，切格式不需要先展开。
- **配方与这三项的关系明确化**：仓库 `presets/` 里 8 支自带配方经核对**一个 `overscan_*` 键都没写**，
  而 `EditGroup.LOCAL_FIELDS` 本来就把 `overscan_frame_number` / `overscan_camera_seed` / `overscan_frame_seed`
  排除在配方携带范围之外；本轮再在「套用我的配方」路径上显式把这三项复位为 0（= 这一张自己随机），
  避免旧版本按整卷预排留下的非 0 值在套完配方后继续挂着。
- **参数说明同步**：8.10 / 8.11 改名为「帧号」并补上开关与新口径；「四、导出与配方」第 1 节、第 12 节（折叠结构）同步；
  `docs/help-params.md` 一并更新（该文件此前还停留在「自动 / 手动画幅编号 / 重置编号」的旧描述）。
- 本节随 `1.0.1`（tag `v1.0.1`）发布，即本条所在这一版的正式包。

## 2026-11-25 追加二（仓库数据与口径修正 · 不升 App 版本）

本轮只动 `presets/`、`docs/`、`README.md`、`NOTICE.md`、`COMPLIANCE.md` 与本文件，**未改任何 Kotlin / C++ 代码，不重打 APK，版本号保持 `1.0.1` / versionCode `1001`**。

- **配方文件改名**：`presets/speklabcam-recipes-v1.json` → `presets/speklabcam-recipes.json`（`git mv`，历史仍可追）。
  按新口径配方不编版本号，`-v1` 这类尾巴以后不再出现；仓库内所有引用（README / NOTICE / COMPLIANCE / docs/recipes.md /
  两份历史 release notes）已同步。
- **第 7 支 山河故人·冰河烟花 的数据修正**：查证到该场是**白天**（1999 段春节，黄河浮冰滩上点烟花），据此改为
  `kodak_vision3_250d`、测光 `protect_highlights`、光晕 2.8 → 1.6、散射 0.8 → 0.55、提升 2EV → 1.2EV、DIR 0.90 → 1.00。
  此前仓库里发布的始终是修正前的旧值（500T + 中央重点），与作者手机里已生效的配方不一致，本轮补齐。
- **8 支 note 全部换成带「适用环境 / 慎用」的新版**：原 note 只写了一句影片画面描述，没提示什么场合会翻车；
  同时补 `best_for` / `avoid` 两个数组字段（app 解析时安全忽略，供人与工具看）。
- **`docs/recipes.md` 同步**：速查表第 7 行的材料 / 测光 / 光晕 / DIR 四列改为新值；第 7 节详解按白天取景重写
  （含「光晕 2.8 会把天空糊住」那条避坑建议改成 1.6 口径）；顶部删掉「配方规范版本」的说法，改成「配方不编版本号」。
- **口径冲突修正**：`COMPLIANCE.md` 发布自查清单第 2 条原文要求 `+theia-vX.Y.Z` 标注上游基座，与 `README.md`
  「`versionName` 不带任何后缀」互相矛盾，按后者改正；自查清单新增第 8 条：改配方只动 `presets/` 与 `docs/recipes.md`，
  不新建带版本号的配方文件，不因为改了配方就升 App 版本，且 note 里的读数必须与 `template.values` 逐条核对。
- **校验依据**：按 app 契约（`EditPresetController.importPresets` 只认顶层 `kind = spektralab-edit-presets`，
  逐条走 `EditPreset.fromJson`，字段越界或枚举非法即整条跳过）核对 8 支——七组必填字段齐全、`note` 均 ≤120 字、
  材料与素材目录一致，仅 1 条有意为之的提示（英雄本色用 2393 高反差相纸）；
  note 中的材料 / 测光 / 颗粒 / 光晕 / DIR / 打印读数与 `template.values` 实际值 8/8 一致。
- **待办（需要网页操作）**：GitHub Release 页上的附件仍叫 `SpeklabCam-recipes-v1.json`（1.0.0 与 1.0.1 两页都挂过），
  下次编辑 Release 时删掉旧附件、改挂与仓库同名的 `speklabcam-recipes.json`，别让两份内容不同的文件并存误导用户。


## 2026-11-26 追加（已发布 · 随 `1.0.2` / versionCode 1002，tag `v1.0.2`）

只修两个 bug，不改影像算法、不动配方文件、不动编辑数据与侧车格式。

### 1. RAW 被当成位图导出，只出内嵌预览的小图

- **现象**：Lightroom 由尼康 NEF 转档的 `20260806-Z50_4245.DNG`（真 RAW 5600×3728 / 20.7MP）导出成 1066×744 的 JPG，
  片边、机身、镜头、拍摄时间全对，只有画面是 DNG 内嵌预览放大后的尺寸。
- **根因**：`RawDecoder.isRaw()` 只看文件名扩展名，而系统相册选择器给的 `DISPLAY_NAME` 并不可靠——
  ColorOS 会把这颗 `.dng` 报成以 `.jpg` 结尾的名字（字节仍是完整的 21MB DNG），vivo 常直接给相册行号（`1000125791.tiff`）。
  名字判成位图 → 走 `ImageDecoder / BitmapFactory` → Android 只交 DNG 里的预览图（1024×683），加完片边正好 1066×744。
- **修法：改为文件头优先。** `isRaw()` 先读文件前 64KiB，找 TIFF 魔数（`II*\0` / `MM\0*`）加 DNG 版本标签 `50706`，
  命中即按 RAW 处理——改扩展名、报假名字一律失效；只有头部读不出 RAW 时才退回扩展名判断。
  `RAW_EXTENSIONS` 补 `tif` / `tiff`（vivo 实测按行号命名的 RAW 能走通并输出全尺寸）。
- **两处兜底**：`ExportSourceDecoder.open()` 即使拿到的是「按位图」的判定（例如复用了旧任务的导出凭据），
  只要文件头是 TIFF/DNG 就自动改走 RAW；`refuseRawContainer()` 留作最后一道护栏——
  万一仍以位图路径解出 DNG/NEF 容器，**直接报错也不把内嵌预览当成品导出**。
- **验证**（同一颗文件、同一台 ColorOS 机型 A/B）：旧包 1066×744 / 282,396 B → 新包 **5820×4062 / 11,775,496 B**
  （= RAW 全幅 5600×3728 加当前配方片边），EXIF 机身 `NIKON Z 50`、拍摄时间 `2026:08:06 16:56:15` 原样保留。
  本包内置 LibRaw 能解 `compression=7 / photometric=32803` 的无损 JPEG 压缩 DNG，**不需要退回「先在 Lightroom 导未压缩 DNG」**。
- **已知遗留（仅显示层）**：图库与照片信息读尺寸走 `SourceProbe` + `BitmapFactory`，对 DNG 仍显示内嵌预览的尺寸；
  不影响导出成品，留待下版。

### 2. 照片图库：相册名不回显 + 新增「未分组」筛选

- **相册名回填**：照片行的「相册」格以前冷启动一律显示「选择相册」，只有点开过相册菜单才刷出真名。
  根因不在显示逻辑，而在页内归属表 `inlineMembership` 初始为空、仅靠菜单展开时回填。
  现在 `SavedPhotoRepository.page()` 一次带回整页的 `memberships()`——**没有归属任何相册的资产也返回空集合**，
  防止取消分组后旧名字赖在缓存里；控制器新增 `primeInlineMembership()`，进页面与翻页时预热。
- **「未分组」筛选**：`PhotoLibraryFilter` 新增 `ungroupedOnly`，与 `albumId` 互斥（同时给出即抛 `ALBUM_FILTER_CONFLICT`）；
  查询侧加 `NOT EXISTS (album_members)`；页面新增 `ungroupedCount`；相册下拉改成「全部照片 / 未分组 / 各相册」三档，
  带勾选圆点与各自张数，选中未分组时按钮文案即显示「未分组」。
- **验证**（ColorOS 机型，293 张 / 4 个相册）：菜单计数自洽（未分组 1 + 127 + 38 + 86 + 41 覆盖 293 张）；
  选「未分组」显示「1 张匹配，共 293 张」，只剩 `IMG20261122171652.dng` 且该行相册格正确为「选择相册」；
  **冷启动一次没点开过相册菜单**，首屏与翻页后每行相册格都直接是「山西 8-6 手机」。

### 另：上一版调试记录里的一处更正

「批量导出 RAW 受 `MAX_INPUT_EDGE=4096` 限制」的判断**作废**：同机器导入 50 颗同类型 DNG 后，
单张原始尺寸与批量导出均正常；当时不落文件用的是从未保存过参数的一张，命中的是 `TABLE_EXPORT_RAW_UNEDITED`，
与输入边长上限无关。

## 2026-11-28 追加（已发布 · 随 `1.1.0` / versionCode 1003，tag `v1.1.0`）

MINOR 版：界面重排 + 行为与数据安全修正。**未改影像算法、未改配方文件、未改编辑数据与侧车格式**，
引擎 C++/Vulkan 源码一行没动，仍用上游官方预编译产物。逐条用户向描述见 `docs/release-notes-v1.1.0.md`。

### 1. 数据安全：单击一格不再清空工作台

- `EditorViewModel.openPhotos(listOf(uri))` 会把 `photos` 整组替换并立刻 `persistWorkbench()` 覆盖存档，
  首页宫格、首页瀑布流、图库三处单击都走这条路：10 张的工作台点一下就只剩 1 张，且没有回头路。
- 现在分三层：这张已在工作台上 → 只 `selectPhoto` 切换；工作台空着 → 直接打开；
  这张不在工作台上 → 挂起请求并弹框问「追加至工作台 / 新建工作台（替换当前 N 张）/ 取消」
  （`WorkbenchJoinDialog.kt`，首页路由与图库路由共用同一个挂起状态，不会弹两层）。

### 2. 缩略图显示已渲染的成品（方案 A）

- 新增 `data/cache/EffectThumbnailStore.kt`：`cacheDir/effect-thumbs-512-v1/`，
  文件名 `<assetId>-<revision>.webp`，WEBP quality 85，总量 64MB LRU，同一张照片写入后清掉它的旧修订号文件。
- 首页宫格、照片图库、观片台的格子优先取这张效果图，取不到才解原片。
- 三个故障的修法都写在这里：落盘条件从「参数 JSON 逐字相等」（RAW / 智能显影下恒不成立）改成手势判据；
  失效从「先删再取」改成「到了才替换」；从「单个 600ms 去抖任务、切照片就取消」改成
  「静帧一到先复制一张 ≤512 小图排队，后台串行写，写完主动让列表失效重取」。
- `SavedPhotosController.cancelThumbnail` 的记账错位修正：位图仍留在 16 张内存缓存时不能把它的修订号记录删掉，
  否则该行滚出屏幕期间改了参数，回到视口会命中缓存短路、又查不到记录，旧效果图一直挂着。
- 图库原片解码目标 384 → 512（`THUMB_EDGE`，与 `EffectThumbnailStore.MAX_EDGE` 同值）。

### 3. 默认值与一次性对齐

- `UiPrefs.workbenchAutoHide` 出厂默认由 true 改 false（工作台不再闲置自动收起）；
  直方图、首页显示方式这两项代码默认本来就正确，问题在老设备 SharedPreferences 存的是被手动切过的旧值。
- `UiPrefs.attach()` 加一次性对齐（`KEY_DEFAULTS_SYNCED` / `DEFAULTS_SYNC = 1`），只覆写
  `workbench_auto_hide=false`、`preview_histogram=true`、`home_view_mode=preview` 三项，之后不再覆盖用户选择。
- 关于页、设置页、参数说明里与这三项默认值矛盾的文案同步改正。

### 4. 直方图刻度

- `HistogramView` 由线性 `bins[i]/maxCount` 改为开根号刻度，并补「数组不足 256 桶直接不画」的兜底
  （线性刻度下主峰吃掉全部高度，其余 255 桶压成贴底一条线，看上去像没画；那条 0 高度分支还可能越界崩）。

### 5. 首页重排与编辑页菜单（改版区间）

- 底栏三格（图库 / Speklab / 观片）、首页齿轮菜单、悬浮簇（智能配方显示当前配方名 + 导入 + 拍摄）。
- 首页两形态可切换：预览框 · 当前照片（默认，新增 `EditorViewModel.sessionPreview()` 读工作台存档，
  效果图优先、否则解原片，都在后台线程；读不到才退回相机引导）/ 宫格 · 图库照片（两列错落，长按勾选批量放入）。
- 编辑页三层菜单、控制条一排到底、工具菜单图标改自绘线框工具箱 `ic_spk_toolbox`；
  照片条与「放入」条并入计数行、多选态整条可点、恢复入口并入计数行。

### 6. 权限闸门与界面收纳

- `TheiaRoot.withMediaLocation()`：`SDK_INT < 29` 直接放行。`ACCESS_MEDIA_LOCATION` 是 Android 10 才有的权限，
  低版本问了只会被拒，还白弹一次窗。
- 低使用率入口（数字中间片、DI TIFF、软打样、高清预览、效果细节检查）从界面撤下，实现代码全部保留。

### 7. 体检清理

- Android Lint 全量（2 errors / 106 warnings / 30 hints，逐条复核）+ Kotlin 编译告警 + 孤儿资源/孤儿函数扫描。
- 删除 10 项无引用资源（4 条 strings、2 条 colors、4 个 drawable 文件；启动图标实际引用的
  `ic_launcher_foreground` 未动）与 9 个零调用函数（`ChoiceStrip`、`SectionLabel`、`StockDropdown`、
  `WideMenuBoard`、`dismissMessage`、`toggleCollapsed`、`previousPhoto`、`saveResult`、`resizeRaw`）；
  测试与工具入口（`capabilities`、`getParams`、`warmUp`、`renderForExport` 等）全部保留。
- 删掉一条惰性偏好链路（`autoCollapseSections` / `setAutoCollapse` / `KEY_AUTO_COLLAPSE`）：有存储、有读取，
  但设置页没有开关、composition local 也从未被提供，用户永远改不动。
- 修掉 lint 里唯一的 Error 级代码问题（`PhotoLibraryOrganizationUi` 计数行的 SuspiciousIndentation）；
  `thumbnailDispatcher` 补 `@OptIn(ExperimentalCoroutinesApi::class)`。
- 复核为误报、未动：`StaticFieldLeak` ×2（构造里已用 `applicationContext`）、`UsableSpace` ×7
  （全部用的就是 `usableSpace` / `availableBytes`）；`UseKtx` 77 条属风格性，未扫。

### 8. 正式版身份（本版从改版测试线收回）

- 界面改版在独立测试线（包名 `com.spektralabcam.revamp`、显示名 SpeklabCam）上完成真机验证后，
  发布时逐项收回正式版口径：`applicationId` 回 `com.spektralabcam.android`（`EditVersionProvider`
  的 authority 随 `${applicationId}` 一起回到 `com.spektralabcam.android.editversions`）、
  `app_name` 回「SpekLab相机」、`export_host_title` 回「SpekLab相机 正在冲印」、关于页那句测试线说明按 1.0.2 原文恢复。
- 签名仍用同一把 `speklabcam-release.jks`（证书 SHA-256 `a11fc44c…3cc34b`），
  已在装有 1.0.2 的真机上以 `pm install -r` 实测覆盖成功，编辑数据、侧车、配方、设置全部保留。
