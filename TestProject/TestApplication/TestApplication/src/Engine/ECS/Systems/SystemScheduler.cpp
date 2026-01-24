#include "SystemScheduler.h"
#include "..\World\World.h"

void SystemScheduler::Add(ISystem* s){ systems.push_back(s); }
void SystemScheduler::Tick(World& world, float dt){
  // Stub: real version would schedule systems as jobs with dependency tracking.
  for(auto* s : systems) s->Run(world, dt);
}
