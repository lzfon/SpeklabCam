package com.spektralab.android.feature.about

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.spektralab.android.ui.theme.TheiaShapes
import kotlinx.coroutines.launch

/* ---------------------------------------------------------------------------
 * 关于页的固定信息。版本号不写在这里：它由 packageManager 从 APK 读取，
 * 保证界面显示的版本与实际安装包永远一致。
 * ------------------------------------------------------------------------- */

/** 本版（作者：无限好运）的源码发布地址。 */
internal const val MODDING_REPOSITORY_URL = "https://github.com/lzfon/SpeklabCam"
/** 本版所依据的上游内核基座，只在关于页与仓库文档里标注，不进 versionName。 */
internal const val THEIA_ENGINE_BASELINE = "v0.1.8"
/** 作者的抖音主页，更新记录与安装教程都在这里。 */
internal const val AUTHOR_DOUYIN_URL = "https://v.douyin.com/r0yaZLMMTU8/"
internal const val AUTHOR_DOUYIN_NAME = "无限好运"
/** 正式发布版的签名证书 SHA-256 指纹（与 debug 签名不同，用于核对安装包是否出自同一把密钥）。 */
internal const val RELEASE_SIGNATURE_SHA256 =
    "a11fc44c9763819df767060ad9a415da537fb1dff9f3bbfa3beeb393713cc34b"
/** 上游原版仓库，仅用于致谢与获取原始源码。 */
internal const val PROJECT_REPOSITORY_URL = "https://github.com/deepspyder/theia"
internal const val UPSTREAM_PROJECT_URL = "https://github.com/andreavolpato/spektrafilm"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val versionInfo = remember(context) {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName.orEmpty() to
                androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info).toString()
        }.getOrDefault("" to "")
    }
    // versionName 里就是对外版本号，不加任何后缀；上游基座单独用常量标注，避免手机“应用信息”里出现一串加号尾巴。
    val versionMain = versionInfo.first.trim()
    val versionCode = versionInfo.second
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun open(url: String) {
        try {
            uriHandler.openUri(url)
        } catch (_: ActivityNotFoundException) {
            scope.launch { snackbar.showSnackbar("未找到浏览器，请复制地址后打开") }
        } catch (_: IllegalArgumentException) {
            scope.launch { snackbar.showSnackbar("暂时无法打开链接，请复制地址") }
        }
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("关于", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回编辑器")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface),
                expandedHeight = 56.dp,
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { insets ->
        Box(Modifier.fillMaxSize().padding(insets), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 28.dp),
            ) {
                // ---- 头部：名称、口号、版本 ----
                Text("SpeklabCam", style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(6.dp))
                Text("胶片与暗房模拟", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(if (versionInfo.first.isNotBlank()) "版本 $versionMain" else "版本",
                            style = MaterialTheme.typography.titleSmall)
                        Text("上游内核 Theia $THEIA_ENGINE_BASELINE",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (versionCode.isNotBlank()) {
                            Text("versionCode $versionCode（只增不减，覆盖安装与版本校验用）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))

                // ---- 本版作者与渠道 ----
                Text("本版作者", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("无限好运", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(12.dp))
                Text("更新记录与安装教程都发在抖音；源码与安装包以 GitHub 仓库为准。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                OutlinedButton(onClick = { open(AUTHOR_DOUYIN_URL) },
                    shape = TheiaShapes.control, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开抖音 · $AUTHOR_DOUYIN_NAME")
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(AUTHOR_DOUYIN_URL))
                    scope.launch { snackbar.showSnackbar("抖音链接已复制") }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制抖音链接")
                }

                Spacer(Modifier.height(14.dp))
                Text("本版源码仓库", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                SelectionContainer {
                    Text(MODDING_REPOSITORY_URL, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { open(MODDING_REPOSITORY_URL) },
                    shape = TheiaShapes.control,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Rounded.OpenInNew, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("打开源码仓库")
                }
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(MODDING_REPOSITORY_URL))
                    scope.launch { snackbar.showSnackbar("仓库地址已复制") }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制仓库地址")
                }
                Spacer(Modifier.height(8.dp))
                Text("官方配方不内置在安装包里：配方文件与逐支文字说明放在仓库 presets/ 与 docs/recipes.md。" +
                    "下载后在 App 里导入：首页点「智能配方」→「增加我的配方」→ 底部「导入配方」" +
                    "（编辑页扳手菜单里的「编辑配方」是同一个入口）。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))

                // ---- 项目介绍（从 README 移植） ----
                Text("这个项目是什么", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("一间装进口袋的数字暗房。导入照片后按真实胶片的成像过程走一遍——底片测光、" +
                    "显影、放大机曝光、相纸显影——再把喜欢的色彩带回相册。\n" +
                    "它与原版 Theia 的区别在于：底层色彩科学沿用同一套引擎（我们没有改动引擎源码），" +
                    "而把日常拍完就用的完整链路补齐了：拍完直接冲印、RAW 自动导入、" +
                    "自己的配方库、导出时的文件名/尺寸/色域/元数据控制，以及全套中文化与失败反馈。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(16.dp))
                Text("与上游的区别（特色功能）", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("· 拍摄闭环：拍完回应用直接冲印，RAW（DNG）自动导入做了三重防呆，不再报「不支持的 RAW」；\n" +
                    "· 选片口径：新照片用哪支胶片完全交给「智能显影」按画面挑（设置里的「材料」已撤下）；\n" +
                    "· 暗房能力：胶片模拟总开关、胶片前曲线（EV 坐标）、垂直/水平透视校正、成片裁切（只裁成片，不重算胶片物理量）；\n" +
                    "· 配方系统：我的配方支持重命名 / 删除 / 导入 / 导出 JSON，可跨机迁移，也可作为智能配方的起点；\n" +
                    "· 导出能力：HEIF、TIFF（含 Deflate 无损）、色域四选并写入对应 ICC、自定义长边/短边/像素总数、" +
                    "文件名模板与自定义前缀、导出方案、批量导出、四栏折叠界面；\n" +
                    "· 元数据完整性：成片保留原文件名、GPS 位置，并把拍摄时间的时区偏移写全（修掉相册时间被按 UTC 记账导致的 8 小时偏差）；\n" +
                    "· 查看与交互：预览画面双指缩放（1–6 倍）、右上角前后对比、跨会话撤销重做、所有滑轨带复位、失败给中文明确原因；\n" +
                    "· 界面收纳：低使用率入口（数字中间片、软打样、高清预览、效果细节放大）从界面撤下，实现代码全部保留；\n" +
                    "· 改版测试线：包名 com.spektralabcam.revamp，与正式版 SpekLab相机、原版 Theia 都能并存安装，互不覆盖数据。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))

                // ---- 上游与全部借用的开源项目 ----
                Text("上游与借用的开源项目", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("本项目是 GPL 意义上的修改版（modified version），下列来源的版权与许可声明全部保留、" +
                    "按原许可共享，未做任何替换：\n\n" +
                    "· Theia（前身 SpektraLab Android）— deepspyder — 应用本体与全部 Android 工程 — GPL-3.0-or-later\n" +
                    "· spektrafilm — Andrea Volpato — 胶片模型、档案与 LUT、色彩科学内核 — CC BY-SA 4.0\n" +
                    "· SpektraLab（macOS/iOS 原版）— 版印后色彩调整（vibrance 与八色带 HSL）的数学来源 — GPL-3.0-or-later\n" +
                    "· libspektralab.so — 上游官方预编译引擎，本项目未修改引擎 C++/Vulkan 源码 — 同上\n" +
                    "· Jetpack Compose / AndroidX（core、lifecycle、exifinterface、heifwriter、documentfile、splashscreen）— Apache-2.0\n" +
                    "· LibRaw — RAW 解码 — LGPL-2.1 / CDDL-1.0 双许可\n" +
                    "· Resource Han Rounded CN — 界面与字体后背 — SIL Open Font License 1.1\n" +
                    "· Lucide Icons — ISC\n" +
                    "· LLVM OpenMP runtime — Apache-2.0（含 LLVM 例外条款）\n" +
                    "· volk — MIT · stb — public domain / MIT · Liberation 印像字体 — OFL\n\n" +
                    "完整逐条声明见仓库 NOTICE.md、licenses/ 与 app/src/main/assets/licenses/；" +
                    "义务对照见 COMPLIANCE.md。本项目与 deepspyder、Andrea Volpato 无隶属或背书关系。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { open(PROJECT_REPOSITORY_URL) }) { Text("Theia 原仓库") }
                    TextButton(onClick = { open(UPSTREAM_PROJECT_URL) }) { Text("spektrafilm") }
                }

                Spacer(Modifier.height(16.dp))
                Text("免费使用，源码公开", style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(12.dp))
                Text("本版本不收取下载、安装或使用费用。程序代码沿用上游的 GPL-3.0-or-later 授权发布；" +
                    "任何人可以使用、修改、再分发，但分发时必须同时满足下面几条。",
                    style = MaterialTheme.typography.bodyLarge)

                Spacer(Modifier.height(16.dp))
                Text("分发或再发布时必须做到", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("· 随包提供完整对应的源代码，或给出稳定可取的获取地址（GPL 第 3 条）；\n" +
                    "· 保留上游版权声明、许可证全文与「无担保」声明，不得删改；\n" +
                    "· 你修改后的版本仍按 GPL-3.0 发布，不能改成闭源或附加限制；\n" +
                    "· 明确标注这是修改版及其改动内容，不以原作者名义为你的版本背书；\n" +
                    "· 胶片曲线、LUT 等素材来自 spektrafilm，按 CC BY-SA 4.0 授权：" +
                    "使用时须署名原作者 Andrea Volpato、注明是否修改，并以相同方式共享，不能转为私有；\n" +
                    "· 上面这些是本项目的全部要求。抖音与 GitHub 链接只是作者的联系方式，" +
                    "再分发时你可以替换成你自己的联系方式。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(20.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Spacer(Modifier.height(20.dp))

                // ---- 安装提示与自证方法 ----
                Text("手机提示风险时，怎么核对", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("从商店以外的地方安装应用，各品牌系统都会给一句风险提示（小米叫「未验证/纯净模式」，" +
                    "OPPO、vivo、华为叫「未知来源/安装守护」，Google Play Protect 也会拦一次）。" +
                    "这是系统对所有侧载 APK 的通用提醒，不是因为本应用被判定有问题。\n\n" +
                    "本应用可以当场验证的几点：\n" +
                    "· 不联网：安装包里没有申请任何网络权限，也不含广告与统计 SDK，照片不会离开手机；\n" +
                    "· 只申请必需权限：照片读取、媒体位置（导出带 GPS 时才用）、前台服务（批量导出计时）；\n" +
                    "· 开源可复查：仓库源码与安装包同版本对应，任何人可以自行编译并比对；\n" +
                    "· 无历史包袱：每个正式发布版都用同一把密钥签名，指纹如下。\n\n" +
                    "正式发布版签名证书 SHA-256：",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                SelectionContainer {
                    Text(RELEASE_SIGNATURE_SHA256, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(RELEASE_SIGNATURE_SHA256))
                    scope.launch { snackbar.showSnackbar("签名指纹已复制") }
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.ContentCopy, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("复制签名指纹")
                }
                Spacer(Modifier.height(8.dp))
                Text("每次 Release 页面都会附上该 APK 的 SHA-256 校验值；用命令 certutil -hashfile 文件名 SHA256（Windows）" +
                    "或 shasum -a 256 文件名（Mac/Linux）算一次，对得上就是同一个包。" +
                    "详细的分品牌安装步骤与各厂商误报申诉渠道，见仓库 docs/install-guidance.md。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(16.dp))
                Text("交互方式说明", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("· 预览画面按住＝查看原图，松手恢复；\n" +
                    "· 预览右上角＝前后对比开关（只剩图标，画面中间出分割线）；双指直接在画面上缩放（1–6 倍）看细节，独立的放大页已取消；\n" +
                    "· 扳手菜单＝重置 / 裁剪 / 成片裁切 / 编辑配方 / 复制配方；\n" +
                    "· 每条滑轨右侧小圆箭头＝单条复位；曲线点位滑轨同样带复位；\n" +
                    "· 控制条左端＝弹出 / 收起工作台（照片条 + 胶片 / 相纸栏），闲置几秒也会自动收起；\n" +
                    "· 编辑页分三级：底栏一级页签 → 药丸二级 → 就地展开的三级参数，曲线也在参数里就地展开；\n" +
                    "· 所有失败都会用中文明确告诉你原因，不会静默。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(16.dp))
                Text("商标与隶属关系", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("Kodak、Fujifilm、Ilford、CineStill 等名称与商标归各自权利人所有，" +
                    "在本应用中仅用于说明所模拟的胶片或相纸。本应用与上述公司无任何隶属、授权或背书关系，" +
                    "也未使用其官方标识、包装图案或字体。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(16.dp))
                Text("免责", style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(8.dp))
                Text("本程序按「现状」提供，不附带任何明示或默示担保；使用本程序产生的风险由使用者自行承担。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(Modifier.height(20.dp))
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = TheiaShapes.panel) {
                    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("如果你为它付过钱", style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer)
                        Text("本版本与上游原版项目均不收费。若你是付费获得的，请向收费方申请退款；" +
                            "下载与更新请以公开仓库为准。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }

                Spacer(Modifier.height(24.dp))
                Text("感谢原作者与每一位使用者、开源贡献者。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
