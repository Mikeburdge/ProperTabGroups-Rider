#pragma once
#include <functional>
#include <queue>
#include <mutex>

class JobQueue {
public:
  using JobFn = std::function<void()>;
  void Push(JobFn fn);
  bool TryPop(JobFn& out);
private:
  std::mutex m;
  std::queue<JobFn> q;
};
