# SpeklabCam（SpekLab相机）

**作者：无限好运**（抖音：[无限好运](https://v.douyin.com/r0yaZLMMTU8/)）。本仓库是 [Theia](https://github.com/deepspyder/theia) 的**修改版（fork）**，
上游作者为 deepspyder，Theia 本身基于 **Andrea Volpato 的 [spektrafilm](https://github.com/andreavolpato/spektrafilm)**。
上游原版说明原样保留在 [docs/README.upstream.zh.md](docs/README.upstream.zh.md) 与 [docs/README.upstream.en.md](docs/README.upstream.en.md)。

一句话说明白我做了什么：把一间装进口袋的数字暗房，改成了更贴合我自己日常拍摄与出片习惯的工作流工具。

- 应用显示名：**SpekLab相机**
- 包名（applicationId）：`com.spektralabcam.android`（与上游 Theia 的 `com.spektralab.android` 区分开，**两个应用可以在同一台手机上并存安装**）
- 版本口径：见下面[版本规则](#版本规则)。当前发布 **1.0.0（versionCode 1000）**，基于上游引擎 **Theia v0.1.8**
- 源码仓库：https://github.com/lzfon/SpeklabCam ｜ 下载：本仓库 **Releases** 页

---

## 版本规则

对外一律用**语义化版本 SemVer 2.0**：`MAJOR.MINOR.PATCH`。

| 位置 | 规则 | 例子 |
| --- | --- | --- |
| `versionName`（App 内显示） | `MAJOR.MINOR.PATCH`，`+` 号后面跟上游基座，一眼看清从哪版 Theia 改的 | `1.0.0+theia-v0.1.8` |
| `versionCode`（系统识别用） | 从 **1000** 起，每发一个 APK **加 1**，只增不减、永不复用 | 1000 → 1001 → 1002 |
| git tag | 干净短号，不带 `+` 和上游信息 | `v1.0.0` |
| Release 标题 | `SpekLab相机 <versionName>（SpeklabCam）`，上游基座写在正文 | |
| 内测 / 公测 | 用 SemVer 预发布后缀，正式号留空 | `1.1.0-beta.1`、`1.1.0-rc.2` |
| 内部调试号 | v87、v88 这类编号**只在协作记录里用**，不进 tag、不进 Release、不写进 App | |
| 配方文件 | 有自己独立的规范版本（JSON 顶层 `version: 1`），与 App 版本解耦：App 升级不影响老配方 | |

进位规则：

- **MAJOR**：不兼容改动。配方结构大改导致旧配方导不进来、工程结构或包名变更。`1.x.x → 2.0.0`
- **MINOR**：新增功能且向前兼容。新增配方、新增导出格式、新增界面能力。`1.0.x → 1.1.0`
- **PATCH**：修 bug 与小调整，行为兼容。修时间戳、修文案、修某条配方的偏移。`1.0.0 → 1.0.1`

---

## 这个版本改了什么

逐条清单见 [CHANGELOG-custom.md](CHANGELOG-custom.md)，重点如下。

- **拍摄与导入**：拍完回应用直接冲印；RAW（DNG）自动导入做了三重防呆（等媒体库写完、校验文件头、延迟补扫同一次拍摄），修掉原版偶发的 `RAW open` 报错。
- **默认材料**：设置里可选默认胶片 / 默认相纸（含 `auto`），与「智能显影」分工明确——具体型号＝锁定材料，`auto`＝交给智能选片；优先级为「已存编辑 > 我的配方 > 默认材料 > 自动选片」。
- **暗房能力**：胶片模拟总开关、胶片前曲线（EV 坐标，作用于胶片模拟之前）、垂直/水平透视校正（±30°）、成片裁切（只裁成片，不重算胶片物理量）。
- **配方系统**：我的配方支持重命名、删除、**导入 / 导出 JSON**，换机或备份一个文件搞定；智能配方里选中某条我的配方后，新照片会先套它再按画面微调。
- **导出**：HEIF、TIFF（含 Deflate 无损）、色域四选（sRGB / Display P3 / Adobe RGB (1998) / ProPhoto RGB，写入对应 ICC）、自定义长边/短边/像素总数、文件名模板、导出配方、批量导出、四栏折叠界面。
- **导出元数据三项修复**（最容易被忽略、也最影响存档）：
  1. 导出成片丢失原文件名——Android 照片选择器返回的是脱敏 URI，现在映射回真实媒体后读取未脱敏 EXIF。
  2. 导出成片丢失 GPS 位置——在打开选择器**之前**申请媒体位置权限，导入时落库，导出时三路合并写回。
  3. 导出成片时间被算早/晚 8 小时——EXIF 时间字符串不带时区标记会被媒体库按 UTC 记账，现在四条导出路径补齐 `OffsetTime` / `OffsetTimeOriginal` / `OffsetTimeDigitized`，并向媒体库显式写入 `DATE_TAKEN`。
- **查看与交互**：全屏预览双指缩放（1–6 倍、可一键回正）、原尺寸细节检查、跨会话撤销重做、所有滑轨补齐复位按钮、失败一律给中文明确原因。
- **界面收纳**：使用率低的入口（数字中间片、软打样、高清预览、效果细节放大）从界面撤下，实现代码全部保留。
- **关于页**：现作者与渠道、与上游的区别、全部借用的开源项目及许可、义务清单、签名指纹与自证方法，都在 App 内「菜单 → 关于」。

## 安装

到本仓库 **Releases** 下载 `arm64-v8a` 的 APK 安装即可。

- 发布版用作者自建密钥签名（不是 debug 包）。正式签名证书 SHA-256 指纹：
  `a11fc44c9763819df767060ad9a415da537fb1dff9f3bbfa3beeb393713cc34b`
- 系统要求：Android 8.0（API 26）及以上、支持 Vulkan 的 64 位 ARM 设备。
- **各品牌手机安装时会弹风险提示**（小米「未验证 / 纯净模式」、OPPO / vivo / 华为「未知来源 / 安装守护」、Google Play Protect）。
  这是系统对**所有**非商店安装包的通用提醒，不代表本应用被判定有问题。逐品牌怎么装、怎么核对安装包、
  以及怎么向各厂商申诉误报，全部写在 [docs/install-guidance.md](docs/install-guidance.md)。

## 从源码构建

1. 准备 JDK 17/21、Android SDK（compileSdk 36）、NDK `27.2.12479018`、CMake `3.22.1`。
2. 项目根目录新建 `local.properties`：

   ```properties
   sdk.dir=C\:\\your\\android-sdk
   # 仅从源码自编译 C++ 引擎时需要：
   # spektraEngineDir=D\:\\your\\engine\\checkout
   ```

   `local.properties`、`keystore.properties`、`*.jks` 已被 `.gitignore` 排除，请不要提交。
3. 调试版：`./gradlew :app:assembleDebug`
4. 发布版：新建 `keystore.properties`（四个键：`storeFile` / `storePassword` / `keyAlias` / `keyPassword`，
   读取逻辑见 `app/build.gradle.kts` 的 `releaseSigningProperties`），再执行 `./gradlew :app:assembleRelease`。
   签名密钥属于发布者本人，**绝对不要提交到仓库，也不要分享给他人**。

默认构建直接使用随仓库附带的上游官方预编译引擎 `app/src/main/jniLibs/arm64-v8a/libspektralab.so`（本 fork 未修改引擎 C++/Vulkan 源码）。
若要自行编译引擎，恢复 `app/build.gradle.kts` 里被注释的 `externalNativeBuild.cmake.path` 一行并配置 `spektraEngineDir`。

测试夹具说明：`app/src/androidTest/assets/` 里的 `raw.dng`、`audit.dng`、`import-switch.dng` 与上游 0.1.8 字节一致（哈希相同），
其中 `raw.dng` 是上游提供的真机 RAW 样本，内含拍摄设备与时间信息。你重新分发这个仓库时可以保留，也可以按自己的合规要求替换：
合成夹具用 `python tools/make_audit_dng.py --output app/src/androidTest/assets/audit.dng` 生成，功能审计测试本身不依赖具体设备样本。

自编译得到的是你自己签名的包，与官方发布的包签名不同，两者不能互相覆盖安装，这属正常现象。

## 隐私

应用不联网、不上传照片，图像处理全部在本机完成；安装包内**不含任何网络权限**，也没有广告与统计 SDK。
只有在系统相册里选择照片时才读取本机媒体；若要在导出的成片里保留 GPS 位置，需要你单独授予「图片和视频」位置权限，
不授权也能正常导出，只是不带位置信息。配方导入导出、诊断报告都是本地 JSON/文本文件，由你自己决定放哪儿。

## 许可证与署名义务

| 内容 | 许可 | 位置 |
| --- | --- | --- |
| 应用代码（含本 fork 全部改动） | GPL-3.0-or-later | [LICENSE](LICENSE)、[CHANGELOG-custom.md](CHANGELOG-custom.md) |
| spektrafilm 胶片档案 / LUT / 引擎素材 | CC BY-SA 4.0 | [licenses/SPEKTRAFILM_LICENSE.txt](licenses/SPEKTRAFILM_LICENSE.txt) |
| LibRaw / AndroidX·Compose / 字体 / 图标 | LGPL-2.1·CDDL-1.0 / Apache-2.0 / OFL-1.1 / ISC | [NOTICE.md](NOTICE.md) |
| 上游 Theia / spektrafilm 版权声明 | 原样保留 | [NOTICE.md](NOTICE.md) |

逐条义务对照见 [COMPLIANCE.md](COMPLIANCE.md)：随每个分发的 APK 提供**完整对应源码**、显著声明**本版本为修改版**并列出改动内容与日期、
保留上游与素材作者的署名及许可证原文、以同一许可证共享而不附加限制。

关于个人渠道：抖音链接与 GitHub 地址只是作者的联系方式，**不构成许可的附加条款**；你再分发时可以把它们换成自己的联系方式，
但上游版权声明、许可证全文与 CC BY-SA 素材的署名要求必须原样保留。

## 免责

本 fork 与 deepspyder、Andrea Volpato 无隶属或背书关系。软件按 GPL 原样提供，不含任何明示或暗示的担保。
胶片与相纸模拟的结果只用于创作表达，不保证与真实胶片或真实冲印一致。

## 致谢

- [deepspyder](https://github.com/deepspyder/theia) —— Theia 应用本体与全部 Android 工程。
- [Andrea Volpato](https://github.com/andreavolpato/spektrafilm) —— spektrafilm 胶片模型与色彩档案。
- LibRaw 团队、Jetpack Compose / AndroidX、Resource Han Rounded、Lucide、LLVM、volk、stb 的作者们 —— 见 [NOTICE.md](NOTICE.md)。
