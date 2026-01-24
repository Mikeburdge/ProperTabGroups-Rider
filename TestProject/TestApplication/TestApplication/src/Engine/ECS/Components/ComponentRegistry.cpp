#include "ComponentRegistry.h"
template<typename T> ComponentTypeId ComponentRegistry::Get() {
  auto key = std::type_index(typeid(T));
  auto it = map.find(key);
  if(it!=map.end()) return it->second;
  auto id = next++;
  map[key] = id;
  return id;
}
// NOTE: in a real project you'd put this in header or explicitly instantiate common components.
