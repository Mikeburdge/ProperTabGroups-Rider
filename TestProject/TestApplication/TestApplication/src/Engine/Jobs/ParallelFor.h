#pragma once
#include <cstdint>
#include <functional>
#include "JobSystem.h"

inline void ParallelFor(uint32_t count, const std::function<void(uint32_t)>& body) {
  // Stub: real version would split work into chunks and schedule jobs.
  for(uint32_t i=0;i<count;i++) body(i);
}
