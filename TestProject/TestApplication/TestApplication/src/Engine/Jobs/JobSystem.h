#pragma once
#include <functional>
#include <cstdint>
#include "JobHandle.h"

class JobSystem {
public:
  using JobFn = std::function<void()>;
  void Start(uint32_t workerCount);
  void Stop();

  JobHandle Schedule(JobFn fn);
  JobHandle ScheduleAfter(const JobHandle& dependency, JobFn fn);
  void Wait(const JobHandle& handle);

  static JobSystem& Get(); // singleton for sample
};
