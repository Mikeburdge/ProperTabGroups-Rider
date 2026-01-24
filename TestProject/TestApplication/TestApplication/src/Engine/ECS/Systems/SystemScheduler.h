#pragma once
#include <vector>
#include "ISystem.h"
#include "..\..\Jobs\JobSystem.h"
class World;

class SystemScheduler {
public:
  void Add(ISystem* s);
  void Tick(World& world, float dt);
private:
  std::vector<ISystem*> systems;
};
