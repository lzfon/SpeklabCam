#pragma once

#include <algorithm>
#include <thread>
#include <omp.h>

namespace theia::raw {

// Per-calling-thread OpenMP settings: never change another render's policy.
class ThreadScope final {
public:
    ThreadScope()
        : threads_(omp_get_max_threads()), dynamic_(omp_get_dynamic()),
          levels_(omp_get_max_active_levels()) {
        omp_set_dynamic(0);
        omp_set_max_active_levels(1);
        omp_set_num_threads(static_cast<int>(std::clamp(std::thread::hardware_concurrency(), 1u, 4u)));
    }
    ~ThreadScope() {
        omp_set_num_threads(threads_);
        omp_set_max_active_levels(levels_);
        omp_set_dynamic(dynamic_);
    }
    ThreadScope(const ThreadScope&) = delete;
    ThreadScope& operator=(const ThreadScope&) = delete;

private:
    int threads_, dynamic_, levels_;
};

}  // namespace theia::raw
