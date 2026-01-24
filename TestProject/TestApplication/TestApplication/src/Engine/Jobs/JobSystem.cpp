#include "JobSystem.h"
#include <thread>
#include <vector>

static JobSystem* gInstance = nullptr;

void JobHandle::Wait() const { while(counter && counter->load() > 0) { std::this_thread::yield(); } }
bool JobHandle::IsDone() const { return !counter || counter->load() == 0; }

JobSystem& JobSystem::Get() { if(!gInstance) gInstance = new JobSystem(); return *gInstance; }
void JobSystem::Start(uint32_t) {}
void JobSystem::Stop() {}

JobHandle JobSystem::Schedule(JobFn fn) { fn(); return JobHandle{}; }
JobHandle JobSystem::ScheduleAfter(const JobHandle&, JobFn fn) { fn(); return JobHandle{}; }
void JobSystem::Wait(const JobHandle& h) { h.Wait(); }
