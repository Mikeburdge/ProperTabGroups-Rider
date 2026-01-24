#pragma once
class World;
struct ISystem {
  virtual ~ISystem() = default;
  virtual void Run(World& world, float dt) = 0;
};
