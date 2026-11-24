#pragma once

#include <cstddef>

namespace theia::export_execution {
struct DispatchStats {
    std::size_t kernels = 0;
    std::size_t submissions = 0;
    std::size_t largest_invocations = 0;
    std::size_t largest_binding_bytes = 0;
    std::size_t packed_column_batches = 0;
    std::size_t windowed_reductions = 0;
};
inline thread_local DispatchStats dispatch_stats;
}  // namespace theia::export_execution
