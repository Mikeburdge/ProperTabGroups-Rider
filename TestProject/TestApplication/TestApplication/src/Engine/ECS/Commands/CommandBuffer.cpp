#include "CommandBuffer.h"
void CommandBuffer::Enqueue(std::function<void()> fn){ ops.push_back(fn); }
void CommandBuffer::Playback(){ for(auto& fn: ops) fn(); ops.clear(); }
