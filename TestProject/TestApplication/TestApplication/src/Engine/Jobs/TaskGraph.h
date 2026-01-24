#pragma once
#include <vector>
#include <functional>
#include "JobHandle.h"

struct TaskNode {
  std::function<void()> fn;
  std::vector<int> deps;
};

class TaskGraph {
public:
  int AddTask(std::function<void()> fn, std::vector<int> deps = {});
  JobHandle Execute();
private:
  std::vector<TaskNode> nodes;
};
