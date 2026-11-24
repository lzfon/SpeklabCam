# Exact dispatch partitioning through a Vulkan 1.0 push constant. It keeps each
# IIR line's complete two-way recurrence intact; no tile-local state reset.
function(theia_android_bounded_dispatch input output)
    set(source "${input}")
    set(before "struct ComputePipeline {")
    set(after "${before}\n    bool bounded = false;")
    theia_f14_replace(source "${before}" "${after}" "bounded pipeline flag")

    set(before "        auto found = pipelines_.find(spec.name);\n        if (found != pipelines_.end()) return &found->second;\n        const std::string path = shader_dir_ + \"/\" + spec.name + \".spv\";")
    set(after [=[        std::string key = spec.name;
        bool bounded = false;
        if (theia::export_execution::one_shot &&
            std::strcmp(spec.name, "spk_math_probe") != 0 &&
            std::strcmp(spec.name, "spk_split_probe") != 0 &&
            std::strcmp(spec.name, "spk_reduce_max") != 0) {
            const std::string candidate = key + ".windowed";
            auto cached = pipelines_.find(candidate);
            if (cached != pipelines_.end()) return &cached->second;
            std::ifstream variant(file_path(shader_dir_ + "/" + candidate + ".spv"), std::ios::binary);
            if (variant.good()) { key = candidate; bounded = true; }
            else if (std::strcmp(spec.name, "spk_reduce_max") != 0 &&
                     std::strcmp(spec.name, "spk_split_probe") != 0) {
                error = "Missing exact 1D export shader: " + candidate;
                return nullptr;
            }
        }
        auto found = pipelines_.find(key);
        if (found != pipelines_.end()) return &found->second;
        const std::string path = shader_dir_ + "/" + key + ".spv";]=])
    theia_f14_replace(source "${before}" "${after}" "bounded shader identity")
    set(before "        ComputePipeline p;")
    theia_f14_replace(source "${before}" "${before}\n        p.bounded = bounded;" "bounded pipeline mode")
    set(before "            layout_create.pSetLayouts = &p.descriptors;")
    set(after "${before}\n            const VkPushConstantRange dispatch_range{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(theia::export_windows::Push)};\n            if (bounded) { layout_create.pushConstantRangeCount = 1; layout_create.pPushConstantRanges = &dispatch_range; }")
    theia_f14_replace(source "${before}" "${after}" "bounded push range")
    set(before "return &pipelines_.emplace(spec.name, p).first->second;")
    theia_f14_replace(source "${before}" "return &pipelines_.emplace(key, p).first->second;" "bounded pipeline cache key")

    set(before [=[        const bool submitted = submit_sync([&](VkCommandBuffer command) {
                device_barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                               VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
                vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->layout,
                                        0, 1, &set, 0, nullptr);
                vkCmdDispatch(command, uint32_t(groups_x), uint32_t(groups_y), 1);
            }, "Vulkan compute dispatch", error);]=])
    set(after [=[        bool submitted = true;
        if (pipeline->bounded) ++theia::export_execution::dispatch_stats.kernels;
        const size_t limit = std::strcmp(kernel, "spk_iir_df_acc") == 0 ? 8u : 4096u;
        for (size_t first = 0; first < groups;) {
            if (theia::export_execution::cancellation &&
                theia::export_execution::cancellation->load(std::memory_order_relaxed)) {
                error = "render cancelled"; submitted = false; break;
            }
            const size_t count = pipeline->bounded ? std::min(groups - first, std::min(limit, max_x)) : groups;
            const uint32_t base = uint32_t(first * spec->workgroup);
            if (pipeline->bounded) {
                ++theia::export_execution::dispatch_stats.submissions;
                theia::export_execution::dispatch_stats.largest_invocations = std::max(
                    theia::export_execution::dispatch_stats.largest_invocations, count * spec->workgroup);
            }
            submitted = submit_sync([&](VkCommandBuffer command) {
                    device_barrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                                   VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
                    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->pipeline);
                    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline->layout,
                                            0, 1, &set, 0, nullptr);
                    if (pipeline->bounded) vkCmdPushConstants(command, pipeline->layout,
                        VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof base, &base);
                    vkCmdDispatch(command, uint32_t(pipeline->bounded ? count : groups_x),
                                  uint32_t(pipeline->bounded ? 1 : groups_y), 1);
                }, "Vulkan compute dispatch", error);
            if (!submitted || !pipeline->bounded) break;
            first += count;
        }]=])
    theia_f14_replace(source "${before}" "${after}" "bounded dispatch loop")
    set(${output} "${source}" PARENT_SCOPE)
endfunction()
