SpekLab相机（SpeklabCam）v1.0.2 · 2026-11-26

**This is a modified version (fork) of Theia.** 现作者 / maintainer：**无限好运**
GitHub：<https://github.com/lzfon/SpeklabCam> ｜ 抖音（更新与安装教程）：<https://v.douyin.com/r0yaZLMMTU8/>

上游与素材：Theia by deepspyder <https://github.com/deepspyder/theia>（基线 v0.1.8，GPL-3.0-or-later）；
色彩引擎与胶片档案 spektrafilm by Andrea Volpato <https://github.com/andreavolpato/spektrafilm>（CC BY-SA 4.0）。
本 fork 与两位原作者无隶属或背书关系。完整借用清单见 `NOTICE.md`，逐条义务对照见 `COMPLIANCE.md`，
改动内容与日期见 `CHANGELOG-custom.md`。

这是一个 **PATCH 版**：只修两个 bug，不改任何影像算法，不动配方文件，也不动已有的编辑数据与侧车格式。

---

### 版本与源码对应

- versionName：`1.0.2`（干净短号，应用信息页只显示它；上游基座另行标注 Theia v0.1.8）
- versionCode：`1002`（1000 → 1001 → 1002，只增不减、永不复用）
- 源码 tag：**`v1.0.2`**（与下面这个 APK 同一次构建，满足 GPL「随安装包提供对应源码」）
- 包名：`com.spektralabcam.android`，签名证书与 1.0.0 / 1.0.1 **完全一致**，
  所以老用户**直接装这个包就是覆盖升级**，编辑历史、侧车、我的配方、相册分组、设置全部保留，不用卸载。

### 安装包与校验

- 文件：`speklabcam-1.0.2-arm64-release-signed.apk`（arm64-v8a，52,864,286 B）
- APK SHA-256：`0EAB97E1E0D0CAE1A4098C94FC0C1011E5BAB09B69BA509CE6484ACC31107694`
  - 核对：Windows `certutil -hashfile 文件名 SHA256`；macOS / Linux `shasum -a 256 文件名`
- 签名证书 SHA-256：`a11fc44c9763819df767060ad9a415da537fb1dff9f3bbfa3beeb393713cc34b`（与前两版同一把钥匙）
  - 核对：`apksigner verify --print-certs 文件名`（App 内「菜单 → 关于」也直接显示这串）
- VirusTotal 报告链接（上传本文件后可在此查看）：<https://www.virustotal.com/gui/file/0eab97e1e0d0caea1a4098c94fc0c101e5bab09b69ba509ce6484acc31107694>

要求：Android 8.0（API 26）及以上、支持 Vulkan 的 64 位 ARM 设备。

### 这一版改了什么

**1. RAW（`.dng`）导出只出一张内嵌预览小图 —— 修复**

从相册导入 Lightroom 转档的 DNG（尼康 NEF → DNG，真实 5600×3728 / 20.7MP），导出却只有 1066×744：
片边、机身、镜头、拍摄时间全对，只有画面是 DNG 里那张预览放大后的尺寸。

- **根因不在解码器，而在「怎么判定这是 RAW」**。旧版只看文件名扩展名，可系统相册选择器给的文件名会撒谎：
  ColorOS 把这颗 `.dng` 报成以 `.jpg` 结尾的名字（字节仍是完整的 21MB DNG），vivo 更常直接给一个相册行号
  （像 `1000125791.tiff`）。名字判成位图，就走 `ImageDecoder / BitmapFactory`，Android 只交 DNG 内嵌的 1024×683 预览，
  加完片边正好 1066×744。
- **现在改为文件头优先**：先读文件前 64KiB，认 TIFF 魔数与 DNG 版本标签，命中就按 RAW 走全尺寸解码；
  读不出 RAW 头才按扩展名兜底。**改扩展名、报假名字一律不再影响判定**。扩展名清单同时补上 `tif` / `tiff`。
- **两道兜底**：导出流水线拿到旧的「按位图」判定也会自己嗅探文件头改走 RAW；万一仍以位图路径解出 RAW 容器，
  **直接报错也不把内嵌预览当成品交给你**——宁可失败，不要静默缩水。
- **实测**（同一颗文件、同一台问题机 A/B）：旧包 `1066×744 / 282,396 B` → 本包
  **`5820×4062 / 11,775,496 B`**（= RAW 全幅 5600×3728 加当前配方片边），EXIF 机身 `NIKON Z 50`、
  拍摄时间 `2026:08:06 16:56:15` 原样保留。
- **好消息**：本版内置的 LibRaw 能解**无损 JPEG 压缩的 DNG**（`compression=7 / photometric=32803`），
  也就是 Lightroom 手机端默认那种压缩转档可以直接用，**不需要**再退回去导「未压缩 DNG」。
- 单张原始尺寸与批量导出都验过（50 颗同类型 DNG 一整批），无输入尺寸上限问题。

**2. 照片图库：相册名不回显 + 新增「未分组」筛选**

- **相册名回填**：照片行右侧的「相册」格以前在冷启动时一律显示「选择相册」，非得点开一次相册菜单才刷出真名。
  现在整页照片连同归属关系一次取回并预热，**没归任何相册的照片也返回明确的空集**，
  所以取消分组后旧相册名不会赖在格子里不走。
- **「未分组」筛选**：相册下拉菜单变成「全部照片 / 未分组 / 各相册」三档，每项带当前张数、选中项有圆点标记；
  选「未分组」时按钮文案直接显示「未分组」，列表只剩没进任何相册的照片。用于「我到底还有多少张没归类」这件事。
- **实测**（293 张 / 4 个相册的真机）：菜单计数自洽（未分组 1 + 127 + 38 + 86 + 41 覆盖 293 张）；
  选「未分组」显示「1 张匹配，共 293 张」，只剩那张确实没归类的 `.dng`，且它的相册格正确显示「选择相册」；
  冷启动一次都没点开菜单，首屏与翻页后每行的相册格都已经是真实相册名。

### 直接下载

- APK：<https://github.com/lzfon/SpeklabCam/releases/download/v1.0.2/speklabcam-1.0.2-arm64-release-signed.apk>
- Release 页（含配方文件与说明）：<https://github.com/lzfon/SpeklabCam/releases/tag/v1.0.2>
- 上一版：<https://github.com/lzfon/SpeklabCam/releases/tag/v1.0.1>

### 官方配方（不内置，按需导入）

配方包**不做版本号**，仓库与本页附件始终是同一份 `speklabcam-recipes.json`（`kind = spektralab-edit-presets`），
与 1.0.1 那一份内容完全相同；这一版没有任何配方改动。逐支的影调依据与调参说明在附件
`speklabcam-recipes-guide-zh.md`（仓库里是 `docs/recipes.md`）。

- 花样年华·暗红浓绿 ｜ 重庆森林·酸绿夜色 ｜ 英雄本色·钢蓝烟幕 ｜ 堕落天使·青紫霓虹
- 阿郎的故事·褪色尘土 ｜ 天若有情·冷暖夜雨 ｜ 山河故人·冰河烟花 ｜ 山河故人·1999县城冬日

导入：首页 →「智能配方」→「增加我的配方」→ 弹窗底部**「导入配方」**；同名更新、新名新增、坏记录跳过。
这 8 支的参数全部按影片调色逻辑推导，未从任何剧照或海报取色，包内不含剧照、海报等受版权保护素材。

### 隐私

不联网、不上传照片、不含广告与统计 SDK；安装包内没有 `INTERNET` 权限。
只有在系统相册里选择照片时才读取本机媒体；导出成片要带 GPS 需你单独授权位置权限。
自行核对：`aapt2 dump badging 文件名 | findstr uses-permission`

### 安装提示说明

从商店以外安装，各品牌都会弹风险提示（小米「未验证 / 纯净模式」、OPPO·vivo「未知来源 / 安装守护」、
华为「风险应用管控」、Google Play Protect）。这是系统对**所有**侧载 APK 的通用提醒，不代表本包被判定有害。
分品牌继续安装步骤、逐厂商误报申诉渠道、以及彻底消除提示的正规路线，都在仓库
[`docs/install-guidance.md`](docs/install-guidance.md)。

### 已知限制

- 只发布 arm64-v8a（x86_64 仅用于模拟器调试）。
- **图库列表与照片信息里显示的 DNG 尺寸仍是内嵌预览的尺寸**（读尺寸走的是位图解码），
  不影响导出成品；导出的成片是 RAW 全幅。显示层这一处留待下版一并收口。
- **已经导出过的旧成片不会自动追改**（RAW 判定的修复只对新导出生效）：之前那批缩水的
  1066×744 需要在 App 里重新导出一次；旧成片上的日期与帧号同理，是导出那一刻就印进画面的。
- RAW（`.dng`）的日期取数走 EXIF→`DATE_TAKEN` 两级兜底；个别机型相册没记 `DATE_TAKEN` 时仍可能落到系统当天，
  此时日期框下方会提示「照片未记录拍摄日期，请手动输入」。
- 关闭胶片模拟的自定义配方仍会套所选胶片的颗粒，属引擎既有行为。
- 本 fork 未修改引擎 C++/Vulkan 源码；从源码自编译引擎需自行配置 `spektraEngineDir`。

### 许可

应用代码：**GPL-3.0-or-later**（见 `LICENSE`）。胶片档案 / LUT 等素材：**CC BY-SA 4.0**（见 `licenses/SPEKTRAFILM_LICENSE.txt`）。
LibRaw：LGPL-2.1 / CDDL-1.0；AndroidX·Compose：Apache-2.0；字体与图标：OFL-1.1 / ISC —— 明细见 `NOTICE.md`。
再分发时请保留上游版权声明与许可证全文，并以同一许可证共享源码；作者的个人渠道链接可以替换为你自己的，不构成附加条款。
