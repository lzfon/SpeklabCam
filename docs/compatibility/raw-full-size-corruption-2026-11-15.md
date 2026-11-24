# RAW 原尺寸黑块与偏色专项

后续反馈：小米 17 Pro 在本轮包中仍出现花屏，因此以下内容仅保留为第一阶段历史验证。进一步发现并处理整幅绑定与最大值归约缺口，并加入存储控制和生产成片保护；最新实现及验收见 [RAW 窗口化导出与存储缓存专项](raw-export-windowed-v2-2026-11-15.md)。

日期：2026-11-15。报告范围：用户在小米 17 Pro、最新本地包、CineStill 800T + Kodak Vision 2383 下反馈的原尺寸异常。原片 `DSC_5099.NEF` 为 6064×4040，SHA-256 为 `cafd8050d216fd08923eb972221c2011c2a4d39cda33efb6b0ed39e2a77ede3b`，与上一轮样本字节相同。

本轮确认并修复了安全调度变体覆盖不完整的问题。**本机只有 API 37 x86_64 模拟器，尚未在这台小米上完成修复后复测**；不能把模拟器通过写成真机问题已经最终关闭。

## 图像证据与已确认缺陷

用户提供的损坏 JPEG 尺寸仍是 6064×4040，但有 2538 行接近纯黑，有内容的行范围为 1273–2766。该位置与大图调度边界严格对应：

- 总像素：24,498,560。
- `65535 × 256 = 16,776,960`，对应第 2766.649 行。
- 超出这一批的 7,721,600 像素，对应第 1273.351 行。

这与安卓 GPU 大图调度丢块高度吻合，说明不能把它归因于照片本身或正常胶片色彩。原片在独立 PC 参考、当前模拟器和较小尺寸链路均能正常解码。此边界分析仍不是这台手机的 Vulkan 调用追踪，不能排除其他驱动问题。

代码审计发现 `tools/bounded_shaders.py` 原来只扫描 `.comp` 本身中的两个命名索引函数：

1. 共享 `.glsl` 中的 `main` 和索引没有展开，所以 CAM16、显示调色、片边等漏掉。
2. Android 专用的 `spk_highlight_headroom.comp` 和场景宽容度等使用内联索引，也没有被识别。
3. `get_pipeline` 找不到 `.bounded.spv` 时静默选回原版本，导出在这些节点仍折叠为二维大 dispatch。

实际前包只有 35 份安全变体，修复后为 46 份。新增的 11 份是：

`spk_cam16ucs_compress`、`spk_highlight_headroom`、`spk_scene_latitude`、`spk_view_cam16`、`spk_view_grade`、`spk_view_grade_di`、`spk_view_pair`、`spk_overscan_add_mask`、`spk_overscan_canvas`、`spk_overscan_film_present`、`spk_overscan_light`。

上一轮 8193×281 对照验证了部分分批与 IIR 线执行，但不是 24MP 真机完整节点的调度覆盖验收。两条路径在同一模拟器上得到相同摘要，也不足以排除另一个驱动上的共同错误。上一轮通过记录保留，但对“所有设备质量未受影响”的结论必须收窄。

## 修复范围

- 编译安全变体前递归展开引用的 GLSL，拒绝缺失与循环 include；识别经过审查的完整索引表达式，包括内联形式和条件编译的共享实现。
- 只替换全局索引来源。数学、材料、取样与随机公式逐字保持，`#version` / `#extension` 顺序保留。
- 编译工具检查所有帧内核的安全变体覆盖；归约和 split 数学探针保留各自不能通用切分的合同。
- 导出找不到必需安全变体时明确失败，禁止静默回退到旧大图路径。
- 增加 `tools/test_bounded_shaders.py`，逐个覆盖共享和 Android 专用内核，并还原索引替换核对其他表达式没有变化。
- 增加 `FullSizeCineStillAudit`，使用真实编辑器、独立渲染进程及系统编码器，对原尺寸实际成片做外观核对。

本轮没有降低原尺寸、RAW 显影精度、输出位深或 JPEG 质量值；未关闭正常导出的颗粒和光晕。它也没有完成真正的全管线缓冲区窗口化与显存预算，原有 F03/F16 剩余项保留。

## 参考仓库核对

核对对象：[SAINTTaiYi/SpektraLab-Android](https://github.com/SAINTTaiYi/SpektraLab-Android)，实际读取 commit `b17f6d2bdcd8024bb62abf212d10819115ca2d87`。只读取源码和文档，没有在 ARM 设备运行该项目安装包。

| 问题 | 代码核对结果 |
| --- | --- |
| 是否有原尺寸导出 | 有 JPEG、TIFF16、DNG 的 full 入口。`MainActivity.java` 最新 RAW 分支明确传入 `exportPixels = 0`、`exportEdge = 0`，重新按文件自身尺寸解码；导入预算与最终导出不能混为一谈。 |
| 如何处理手机 GPU | `engine-android.patch` 实现带 offset/range 的 descriptor 窗口、绝对/局部索引区分、分带与独立提交。它比本轮的“限制单次工作量”覆盖了更多绑定范围问题，值得继续按 F03 移植和验收。 |
| 如何发现静默花屏 | 成片与画布做灰度相关检查，不合格时不报告成功并做有界重导。这里借鉴了测试时核对成片外观的思路，没有把经验阈值直接用来拒绝所有用户照片。 |
| 效果和速度是否更好 | 从源码能确认它保留上游物理模型、重点改执行层；没有同图、同参数、同手机的独立实测，不能据 README 的作者测量认定比 Theia 更快或像素相等。 |

参考文件为仓库的 `README.md`、`docs/tiling.md`、`patches/engine-android.patch`、`app/src/main/java/app/spektralab/MainActivity.java`。文档保留多轮历史，早期“仍按预算缩小”的注释与新 RAW 分支并存，因此上述尺寸结论以最新可执行的 `0, 0` 参数为准。

## 最终验证

- 修复构建：11 组、18 条功能记录全部通过；受控缺失着色器后恢复完整宿主，再通过 3 组、3 条。合计 21 条执行、去重 20 用例。证据为 `.tmp/libraw-upgrade/full-size-fixed/summary.json` 与 `full-size-guard-restored/summary.json`。
- 真实 CineStill 800T/Vision 2383、背反射 4 EV、颗粒与光晕开启：2560×1705 JPEG、6064×4040 JPEG、6064×4040 PNG 均通过。与编辑预览的灰度相关分别约 0.99424、0.99801、0.99453；此指标检测大面积损坏，不作为精确色差结论。
- 将原尺寸 JPEG 与 2560 JPEG 缩到相同大小独立比较，灰度相关为 0.999838；用户损坏 JPEG 与正常 2560 输出只有 0.065911，新的外观用例能识别该类异常。
- 两种空间的独立 PC 原生整图对照：sRGB 与 ProPhoto RGB，各比较 73,495,680 个 16 位 RGB 通道，最大差异均 1；平均差异仍为 0.00217145 / 0.00131406。此组关闭随机颗粒/眩光以获得确定性参考，不代表任意后期或 UI 完全相同。
- CineStill 原尺寸 TIFF16、首次与缓存完整摘要保持 `13ecf2f7c248b4066b6dc37b1728aca5f2e73e894758bee2f345208759cf1a3c`。四组有界/原调度完整 U16、25 胶片/9 相纸的 32 组材料参考、编码、host、缓存、发布、设备失效、编辑中断与观景台外观通过。
- 故意从测试 APK 移除 `spk_highlight_headroom.bounded.spv`：明确出现 `Missing exact 1D export shader`，没有相册输出；恢复完整宿主后真实导出重新通过。负向测试不把预期失败计为功能通过用例。
- 四项 Python 测试通过；生成器全量覆盖共享和 Android 内核。97 份原引擎资源、共享引擎 170 项源码与独立 LibRaw 源保持，正式宿主的六份 SO 与 Release 相同；六份 ELF LOAD 对齐均至少 16 KiB。

本地详细证据位于 `.tmp/full-size-corruption/`，其中 `pc-parity-verification.json`、`appearance-verification.json`、`guard-test/verification.json` 分别记录独立 PC、图像外观和缺失变体保护的结论。相机原片和用户异常 JPEG 不加入 Git。

## 安装包与未完成项

新包仍为 0.1.6 / code 7，原证书 V1/V2/V3，58,172,903 字节，SHA-256 为 `9fee0d844ae08ee62c481bfd1b2d12f32a69d79f94ed79975113d6f2a349652f`。归档到 `app/release/v0.1.6-full-size-fix/Theia-v0.1.6-universal.apk`，用于区别上一轮验证包。未提交、推送或发布。

仍需小米 17 Pro 用本包同原片复测，并取得实际设备/驱动信息；不能声称已经完成该真机的最终验收。完整 descriptor/range 窗口、跨节点显存控制、IIR 状态分段及其他 ARM 驱动仍按原计划进行，不能通过降画质或把模拟器结果冒充真机来关闭。
