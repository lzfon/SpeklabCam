# 为什么手机会提示风险，以及怎么让大家放心安装

> 面向使用者与转发者的说明。可以整段复制到群里、放进 Release 正文，或做成抖音置顶图文。

## 一、提示的成因（2026 年的真实规则）

从商店以外的地方（GitHub Release、网盘、微信传的文件）安装 APK 属于**侧载（sideload）**，所有品牌都会给一句风险提示，原因有四类：

| 原因 | 说明 |
| --- | --- |
| **没有商店审核记录** | 系统只能确认"这个包没经过应用商店安全审核"，无法确认它安全，于是统一提示。 |
| **开发者身份验证（ADV）** | Google 的 Android developer verification：2026-08 上线账号体系与高级流程；2026-09-30 起在巴西、印尼、新加坡、泰国的参与商店强制；**2027 年起扩展到全球所有认证 Android 设备上的所有应用**。小米在中国大陆已于 2026-10-21 上线 ADV 验证入口，**2026-10-31 起在侧载安装环节正式校验**：未完成验证的应用会显示"未验证"提示并要求二次确认，识别依据是**包名 + 签名 + 认证结果**。 |
| **纯净模式 / 安装守护** | 小米"纯净模式"、OPPO/vivo 安装守护、华为/荣耀"风险应用管控"，会拦未审核包，需要"授权单次安装"或关闭该模式。 |
| **应用备案与信誉分** | 部分安装器会核对 APP 备案信息，查不到就提示"无法识别应用备案"。小众包、体积大、含原生 `.so`，安全引擎给不了信誉分，容易被判"未知/可疑"。 |

**结论**：只要走商店外分发，这句话一定出现，与包有没有毒无关。要让它彻底消失，只有完成开发者验证或上架商店（见第六节）。

## 二、本项目可以当场验证的硬事实

1. **没有网络权限**，也没有广告 SDK 与统计 SDK：
   ```
   aapt2 dump badging SpeklabCam-1.0.1-arm64-release-signed.apk | findstr uses-permission
   ```
   只会看到 `READ_MEDIA_IMAGES`、`READ_EXTERNAL_STORAGE(≤32)`、`ACCESS_MEDIA_LOCATION`、
   `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_DATA_SYNC` / `FOREGROUND_SERVICE_MEDIA_PROCESSING`、`WAKE_LOCK`。
   前两条用于读相册，第三条只在"导出成片带 GPS"时使用，后面几条只是让批量导出在后台不被系统杀掉。
   另外构建工具会自动补一条应用自有的 `com.spektralabcam.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`
   （只在本应用内部收发广播用，不是系统能力，也不增加任何权限面）。
2. **源码可复现**：仓库 tag 与该 APK 是同一次构建，按 README「从源码构建」自己编译即可得到等价产物。
3. **签名固定**：所有正式发布版用同一把密钥。
   ```
   apksigner verify --print-certs SpeklabCam-1.0.1-arm64-release-signed.apk
   ```
   证书 SHA-256 应为
   `a11fc44c9763819df767060ad9a415da537fb1dff9f3bbfa3beeb393713cc34b`
   （App 内「菜单 → 关于」也直接显示这串，可对照。）
4. **哈希可比对**：每个 Release 都会写该 APK 的 SHA-256。
   - Windows：`certutil -hashfile 文件名.apk SHA256`
   - macOS / Linux：`shasum -a 256 文件名.apk`
5. **不联网就无从上传**：照片不会离开手机，只有你主动从相册选择时才读取本机媒体。

## 三、发布方（作者）固定要做的六件事

1. **包名与签名永远不换**：`com.spektralabcam.android` + 同一把密钥。换签名等于信誉重新攒，而且老用户无法覆盖升级。
2. **只用 GitHub Releases 直链**：短链跳转、网盘、第三方"APK 分发站"是最强的风险触发因素，还常被二次打包注入广告 SDK。
3. **不要加固 / 加壳**：加固壳几乎必然被安全引擎报毒。
4. **每个 Release 附三样东西**：APK 的 SHA-256、签名证书指纹、VirusTotal 检测报告链接（<https://www.virustotal.com/gui/home/upload>）。
5. **每次发新版都复查一次 VirusTotal**：包体一变，引擎重新评估，历史白名单不延续。
6. **把本文放在 README 与关于页指向的位置**，让用户先看到"为什么弹提示"，再看"怎么继续安装"。

## 四、分品牌怎么继续装（发给群友的步骤）

**通用前提**：把 APK 下载到手机自带「文件管理」里（不要在微信内置浏览器直接打开），再从文件管理器点击安装。

- **小米 / Redmi（澎湃 OS）**：安装页右上角「设置」→ 选「**授权单次安装**」（或先关闭纯净模式，需输入锁屏密码）；
  出现「未验证 / 无法识别应用备案」时点「继续安装 / 无视风险安装」。
- **OPPO / 一加（ColorOS）**：安装页 →「我了解风险」→「继续安装」；被拦时到
  设置 → 隐私与安全 → 特殊应用权限 →「安装未知应用」给「文件管理」授权；安全守护里对该应用选择信任。
- **vivo / iQOO（OriginOS）**：安装页 →「继续安装」→ 输入锁屏密码；
  「i 管家 → 应用与权限 → 安装管理」里查看拦截原因并允许。
- **华为 / 荣耀**：设置 → 安全 →「更多安全设置」→ 允许未知来源；进入「风险应用管控中心」时，在该应用条目里选「仍然安装 / 解除管控」。
- **三星**：设置 → 安全和隐私 →「受限应用」→ 允许安装；Play Protect 弹窗选「仍要安装」。
- **Android 的高级流程（针对未验证开发者）**：系统设置里启用开发者模式 → 确认无人胁迫你关闭保护 → 重启并重新验证身份 →
  等过保护等待期 → 用生物识别或 PIN 确认 → 之后 7 天或无限期可安装未验证开发者的包（仍会显示警告，点「仍要安装」）。
  这是 Google 为高级用户保留的正常通道，不是"破解"。

## 五、被报毒 / 被拦时怎么申诉

1. **先定位是谁报的**：把 APK 上传 VirusTotal，记下标红的引擎名与报告链接。VirusTotal 只聚合结果，**不能替厂商删除检测**，必须逐家找引擎。
2. **逐家提交误报**（附 APK、报毒截图、包名 `com.spektralabcam.android`、版本 `1.0.1 / versionCode 1001`、SHA-256、源码地址，以及"无网络权限、无广告 SDK、离线处理"三条事实）：

   | 引擎 / 安全中心 | 渠道 |
   | --- | --- |
   | 360 | `kefu@360.cn`；手机管家误报可用 `shoujijiance@360.cn` |
   | 腾讯（手机管家 / 微信安装拦截） | 手机管家 App 内「反馈」入口 |
   | 百度 | `msc-avp-support@baidu.com` |
   | 安天 | `avlsdk@avlsec.com` |
   | 其他国外引擎 | 官方联系表：<https://docs.virustotal.com/docs/false-positive-contacts> |

3. **Google Play Protect**：Play Console → 应用状态 → 违规处置 → "File a Play Protect appeal"；或走 Play Protect 申诉表，填包名 + 该 APK 在 VirusTotal 上的 SHA-256。
4. **小米商店驳回"某安全厂商报毒"**：官方口径是先核实，若为误报就联系对应厂商删除检测，拿到反馈截图后在「特殊资质 / 代理证书」栏重新提交审核。
5. **小米侧载"未验证"提示**：小米开放平台 →「应用管理 → 应用验证」→ 上传含签名的 APK（可为空包）绑定包名，校验通过后提示解除。
6. **"无法识别应用备案"**：小米安装器自带申诉入口，或用开放平台的 APP 备案申诉服务（填备案主体名称、统一社会信用代码；个人备案时"公司名称"填备案归属人姓名、"营业执照"填其证件号）。

预期时间：引擎误报申诉通常几个工作日到两周，且每次发新版可能要复查；ADV / 厂商应用验证审核约 1–7 天。

## 六、想彻底不弹提示的两条正规路

| 路线 | 条件与成本 | 效果 |
| --- | --- | --- |
| **完成开发者验证（ADV）** | Google：在 Android 开发者管理中心用 Google 账号完成身份验证，注册包名、填签名证书 SHA-256、上传已签名 APK 证明所有权。爱好者可选「有限范围分发」（免身份证件、免费，但最多 20 台设备），正式发布用「全面分发」。小米：开发者账号实名认证 + 应用验证。 | 参与计划的商店与侧载环节的"未验证"提示消失。2027 年全球强制，早做早省事。 |
| **上架厂商商店 / Google Play** | 国内商店需开发者账号 + **计算机软件著作权登记证书**（个人可申请，周期通常 1–2 个月起）+ APP 备案 + 隐私政策与权限说明；Google Play 需一次性注册费与账号审核。 | 用户从商店安装完全没有风险提示，更新走商店通道。 |

现阶段（个人开源、不收费、面向抖音群友）的建议顺序：
**先配齐"三样自证材料"并把本文发出去 → 再注册 Google ADV（爱好者账号起步）→ 然后做小米/华为/OPPO/vivo 的应用验证 → 最后考虑软著与商店上架。**

## 七、参考来源

- Google Android developer verification（时间线、账号类型、高级流程）：<https://developer.android.com/developer-verification>
- 开发者验证 FAQ（侧载、未验证开发者、希望匿名的开发者）：<https://developer.android.com/developer-verification/guides/faq>
- Android 开发者管理中心注册与包名注册：<https://developer.android.google.cn/developer-verification/guides/android-developer-console>
- 小米开发者身份验证（ADV）申请规范与 2026-10-31 侧载校验生效：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2366>
- 小米纯净模式说明：<https://app.market.xiaomi.com/hd/apm-h5-cdn/pure-mode.html>
- 小米安装器"无法识别应用备案"申诉指南：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1781>
- 小米 APP 备案申诉服务操作指南：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1790>
- 小米应用审核常见问题（报毒驳回先联系安全厂商删误报）：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=1252>
- VirusTotal 误报处理与厂商联系表：<https://docs.virustotal.com/docs/false-positive> 、<https://docs.virustotal.com/docs/false-positive-contacts>
