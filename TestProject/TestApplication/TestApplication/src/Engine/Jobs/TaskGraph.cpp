#include "TaskGraph.h"
#include "JobSystem.h"

int TaskGraph::AddTask(std::function<void()> fn, std::vector<int> deps) {
  nodes.push_back({fn, deps});
  return (int)nodes.size() - 1;
}
JobHandle TaskGraph::Execute() {
  // Stub: real version would schedule respecting deps.
  for (auto& n : nodes) n.fn();
  return JobHandle{};
}
