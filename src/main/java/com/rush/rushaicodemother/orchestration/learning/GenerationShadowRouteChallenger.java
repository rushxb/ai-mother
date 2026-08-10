package com.rush.rushaicodemother.orchestration.learning;

import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

/**
 * 影子路由中的候选决策器。
 *
 * <p>入参刻意只收结构化的 {@link IntentProfile} 而不透传原始用户文本：影子评估在提交线程上
 * 与主路由同步执行，没有任务级预算与取消栅栏，一旦允许它接触原文就会诱导实现在此处调用模型，
 * 把观测链路变成第二条计费链路。需要模型参与的意图澄清已下沉到任务线程的
 * {@code IntentClarificationStage}，其结果会以精化后的画像回流，因此本 SPI 无需加宽。</p>
 */
@FunctionalInterface
public interface GenerationShadowRouteChallenger {

    GenerationModeDecision decide(IntentProfile intentProfile);
}
