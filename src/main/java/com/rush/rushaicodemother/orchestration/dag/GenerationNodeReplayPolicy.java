package com.rush.rushaicodemother.orchestration.dag;

/** 定义节点在进程中断后是否允许从上一个持久化边界重新执行。 */
public enum GenerationNodeReplayPolicy {

    /** 节点可能产生非幂等副作用，执行前必须先持久化运行中状态。 */
    REQUIRES_START_CHECKPOINT,

    /** 节点无副作用或副作用具备幂等边界，可以从上一个完成检查点安全重放。 */
    REPLAY_SAFE
}
