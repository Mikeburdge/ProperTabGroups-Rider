#pragma once
#include <vector>
#include <unordered_map>
#include "..\Entity\Entity.h"

template<typename T>
class SparseSetStorage {
public:
  bool Has(Entity e) const;
  T& Get(Entity e);
  void Set(Entity e, const T& v);
  void Remove(Entity e);
private:
  std::vector<Entity> denseEntities;
  std::vector<T> denseValues;
  std::unordered_map<uint32_t, uint32_t> sparse; // entityId -> denseIndex
};
