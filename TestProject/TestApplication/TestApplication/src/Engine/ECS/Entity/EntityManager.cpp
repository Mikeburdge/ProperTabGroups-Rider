#include "EntityManager.h"
Entity EntityManager::Create(){ Entity e{nextId++, 0}; if(gens.size()<=e.id) gens.resize(e.id+1); return e; }
bool EntityManager::IsAlive(Entity e) const { return e.id < gens.size() && gens[e.id] == e.gen; }
