#include <iostream>
#include "Engine\Jobs\JobSystem.h"
#include "Engine\ECS\World\World.h"
#include "Engine\ECS\Systems\SystemScheduler.h"
#include "Game\Systems\MovementSystem.h"

int main(){
  JobSystem::Get().Start(4);

  World world;
  SystemScheduler scheduler;
  MovementSystem move;
  scheduler.Add(&move);

  scheduler.Tick(world, 1.0f/60.0f);

  JobSystem::Get().Stop();
  std::cout << "EcsJobsSample running (stub)." << std::endl;
  return 0;
}
