export type GenerationPhase = 'idle' | 'codegen' | 'build' | 'repair' | 'done' | 'failed'

const allowedTransitions: Record<GenerationPhase, ReadonlySet<GenerationPhase>> = {
  idle: new Set(['idle', 'codegen', 'build', 'repair', 'failed']),
  codegen: new Set(['codegen', 'build', 'repair', 'done', 'failed', 'idle']),
  build: new Set(['build', 'repair', 'done', 'failed', 'idle']),
  repair: new Set(['repair', 'build', 'done', 'failed', 'idle']),
  done: new Set(['done', 'idle', 'codegen']),
  failed: new Set(['failed', 'idle', 'codegen', 'repair']),
}

/** 生成阶段转换规则集中在领域模块，避免各事件分支形成互相矛盾的隐式状态。 */
export const canTransitionGenerationPhase = (from: GenerationPhase, to: GenerationPhase): boolean => {
  return allowedTransitions[from].has(to)
}
