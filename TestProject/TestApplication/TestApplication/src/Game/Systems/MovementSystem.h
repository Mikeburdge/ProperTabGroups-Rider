#pragma once
#include "..\..\Engine\ECS\Systems\ISystem.h"
class MovementSystem : public ISystem {
public:
  void Run(class World& world, float dt) override;
};
