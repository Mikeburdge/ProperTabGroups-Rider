#pragma once
#include <vector>
#include "Entity.h"

class EntityManager {
public:
  Entity Create();
  bool IsAlive(Entity e) const;
private:
  uint32_t nextId = 1;
  std::vector<uint32_t> gens;
};
