SpekLab相机（SpeklabCam）v1.0.0 · 2026-11-24

**This is a modified version (fork) of Theia.** 现作者 / maintainer：**无限好运**
GitHub：<https://github.com/lzfon/SpeklabCam> ｜ 抖音（更新与安装教程）：<https://v.douyin.com/r0yaZLMMTU8/>

上游与素材：Theia by deepspyder <https://github.com/deepspyder/theia>（基线 v0.1.8，GPL-3.0-or-later）；
色彩引擎与胶片档案 spektrafilm by Andrea Volpato <https://github.com/andreavolpato/spektrafilm>（CC BY-SA 4.0）。
本 fork 与两位原作者无隶属或背书关系。完整借用清单见 `NOTICE.md`，逐条义务对照见 `COMPLIANCE.md`，
改动内容与日期见 `CHANGELOG-custom.md`。

---

### 版本与源码对应

- versionName：`1.0.0`（干净短号，应用信息页只显示它；上游基座在关于页与文档里另行标注 Theia v0.1.8）
- versionCode：`1000`（以后每发一个包 +1，只增不减、永不复用）
- 源码 tag：**`v1.0.0`**（与下面这个 APK 同一次构建，满足 GPL「随安装包提供对应源码」）
- 包名：`com.spektralabcam.android`（与原版 Theia 的 `com.spektralab.android` 不同，**两个应用可在同一台手机并存安装**）

### 安装包与校验

- 文件：`SpeklabCam-1.0.0-arm64-release-signed.apk`（arm64-v8a）
- APK SHA-256：`AFB7596FD5A69292A161156C212761B7A3B09EBFD1B45368555825EFE18F8B43`
  - 核对：Windows `certutil -hashfile 文件名 SHA256`；macOS / Linux `shasum -a 256 文件名`
- 签名证书 SHA-256：`a11fc44c9763819df767060ad9a415da537fb1dff9f3bbfa3beeb393713cc34b`
  - 核对：`apksigner verify --print-certs 文件名`（App 内「菜单 → 关于」也直接显示这串）
- VirusTotal 报告链接（上传本文件后补在这里）：____________________

要求：Android 8.0（API 26）及以上、支持 Vulkan 的 64 位 ARM 设备。

### 这个版本能做什么

- 拍完直接冲印；RAW（DNG）自动导入，不再报「不支持的 RAW」
- 默认胶片 / 默认相纸可设（含 auto），与智能显影分工明确
- 我的配方：保存、重命名、删除、**导入 / 导出 JSON**
- 胶片模拟总开关、胶片前曲线（EV 坐标）、垂直/水平透视校正、成片裁切（只裁成片不重算胶片物理量）
- 导出：JPEG / PNG / TIFF（含 Deflate 无损）/ HEIF；色域四选（sRGB、Display P3、Adobe RGB 1998、ProPhoto RGB）并写入对应 ICC；
  自定义长边/短边/像素总数；文件名模板；批量导出；导出页四栏折叠
- 成片保留原文件名、GPS 位置，**并修正相册时间被按 UTC 记账导致的 8 小时偏差**（补齐 OffsetTime 三标签 + 显式写 DATE_TAKEN）
- 全屏预览双指缩放（1–6 倍）、原尺寸细节检查、跨会话撤销重做、所有滑轨带复位、失败给中文明确原因

### 官方配方（不内置，按需导入）

这一版随仓库发布 **8 支官方配方**，都在 `presets/speklabcam-recipes-v1.json` 里（本页附件也有一份 `SpeklabCam-recipes-v1.json`），
逐支的影调依据、每个数值为什么这么给、适合什么题材、容易踩的坑，写在 `docs/recipes.md`（附件 `SpeklabCam-recipes-guide-zh.md`）。

- 花样年华·暗红浓绿 ｜ 重庆森林·酸绿夜色 ｜ 英雄本色·钢蓝烟幕 ｜ 堕落天使·青紫霓虹
- 阿郎的故事·褪色尘土 ｜ 天若有情·冷暖夜雨 ｜ 山河故人·冰河烟花 ｜ 山河故人·1999县城冬日

配方里已经带好胶片与相纸（Vision3 500T/250D + 2383/2393，堕落天使那支用 CineStill 800T），套用后不用手选材料；
曲线、几何、导出设置属于"人"的设置，不随配方传递。
导入：首页 →「智能配方」→「增加我的配方」→ 弹窗底部**「导入配方」**（编辑页扳手菜单里的「编辑配方」是同一个入口）；
同名更新、新名新增、坏记录跳过，不会动你已有的其它配方。
这 8 支的参数全部按影片调色逻辑推导，未从任何剧照或海报取色，包内不含剧照、海报等受版权保护素材。

### 直接下载

- APK：<https://github.com/lzfon/SpeklabCam/releases/download/v1.0.0/SpeklabCam-1.0.0-arm64-release-signed.apk>
- Release 页（含配方文件与说明）：<https://github.com/lzfon/SpeklabCam/releases/tag/v1.0.0>

### 隐私

不联网、不上传照片、不含广告与统计 SDK；安装包内没有 `INTERNET` 权限。
只有在系统相册里选择照片时才读取本机媒体；导出成片要带 GPS 需你单独授权位置权限。
自行核对：`aapt2 dump badging 文件名 | findstr uses-permission`

### 安装提示说明

从商店以外安装，各品牌都会弹风险提示（小米「未验证 / 纯净模式」、OPPO·vivo「未知来源 / 安装守护」、
华为「风险应用管控」、Google Play Protect）。这是系统对**所有**侧载 APK 的通用提醒，不代表本包被判定有害。
分品牌继续安装步骤、逐厂商误报申诉渠道、以及彻底消除提示的两条正规路线，都在仓库
[`docs/install-guidance.md`](docs/install-guidance.md)。

### 已知限制

- 只发布 arm64-v8a（x86_64 仅用于模拟器调试）。
- 已导出的旧成片不会被自动纠正时间，需要重导一次。
- 关闭胶片模拟的自定义配方仍会套所选胶片的颗粒，属引擎既有行为。
- 本 fork 未修改引擎 C++/Vulkan 源码；从源码自编译引擎需自行配置 `spektraEngineDir`。

### 许可

应用代码：**GPL-3.0-or-later**（见 `LICENSE`）。胶片档案 / LUT 等素材：**CC BY-SA 4.0**（见 `licenses/SPEKTRAFILM_LICENSE.txt`）。
LibRaw：LGPL-2.1 / CDDL-1.0；AndroidX·Compose：Apache-2.0；字体与图标：OFL-1.1 / ISC —— 明细见 `NOTICE.md`。
再分发时请保留上游版权声明与许可证全文，并以同一许可证共享源码；作者的个人渠道链接可以替换为你自己的，不构成附加条款。
