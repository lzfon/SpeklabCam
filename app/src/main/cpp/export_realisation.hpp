#pragma once

#include <cstdint>

namespace theia::export_execution {
struct Realisation { bool resolved = false; std::uint32_t film = 0, print = 0; };
inline thread_local Realisation realisation;

class RealisationScope {
public:
    RealisationScope(std::uint32_t film, std::uint32_t print) : previous_(realisation) {
        realisation = {true, film, print};
    }
    ~RealisationScope() { realisation = previous_; }
    RealisationScope(const RealisationScope&) = delete;
    RealisationScope& operator=(const RealisationScope&) = delete;
private:
    Realisation previous_;
};
}  // namespace theia::export_execution
