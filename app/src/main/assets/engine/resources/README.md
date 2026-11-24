# 引擎资源（生成目录）

本目录由 [tools/sync_resources.py](../../../../tools/sync_resources.py) 从
`SpektraLab-Windows/engine` 同步生成，**不要手动编辑**（改动会被覆盖），
也**不提交进 git**（源在引擎 checkout 里）。

运行：`python tools/sync_resources.py`（或 `./gradlew syncEngineResources`）。

内容（`spk_engine_create` 的 `resources_dir` 直接指向这里）：

- `profiles/*.json` — 28 款胶片/相纸 + 5 款 B&W（覆盖自 resources_product）
- `spektrafilm_constants.bin` — 预烘焙常量（光谱表、打印 LUT 等）
- `print_luts.json` / `paper_grades.json` / `neutral_print_filters.json` / `product-neutral-print-filters.json`
- `io/sRGB.icc`
- `DI/*.cube` — 数字中间片查色 LUT
- `vulkan/*.spv` — SPIR-V（由 [tools/compile_shaders.py](../../../../tools/compile_shaders.py) 生成，需 glslang）
