#pragma once
#include <unordered_map>
#include <typeindex>
#include "ComponentTypeId.h"

class ComponentRegistry {
public:
  template<typename T> ComponentTypeId Get();
private:
  ComponentTypeId next = 1;
  std::unordered_map<std::type_index, ComponentTypeId> map;
};
