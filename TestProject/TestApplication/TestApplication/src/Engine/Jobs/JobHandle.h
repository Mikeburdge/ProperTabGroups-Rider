#pragma once
#include <atomic>
struct JobHandle {
  std::atomic<int>* counter = nullptr;
  void Wait() const;
  bool IsDone() const;
};
