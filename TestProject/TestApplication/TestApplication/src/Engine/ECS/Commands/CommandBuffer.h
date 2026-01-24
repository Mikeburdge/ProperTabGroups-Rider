#pragma once
#include <vector>
#include <functional>

class CommandBuffer {
public:
  void Enqueue(std::function<void()> fn);
  void Playback();
private:
  std::vector<std::function<void()>> ops;
};
