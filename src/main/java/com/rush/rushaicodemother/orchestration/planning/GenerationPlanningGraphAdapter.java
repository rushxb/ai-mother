package com.rush.rushaicodemother.orchestration.planning;

import com.rush.rushaicodemother.orchestration.GenerationPlanningVariant;
import com.rush.rushaicodemother.orchestration.dag.GenerationAgentNode;

import java.util.List;

/**
 * 一种规划消融方案对应的 DAG 图适配器。
 *
 * <p>规划方案只描述实验身份；节点组合、重型路径差异等实现细节留在适配器内部，
 * 调用方只需按方案取得不可变节点序列。</p>
 */
public interface GenerationPlanningGraphAdapter {

    /** 返回该适配器唯一支持的规划方案。 */
    GenerationPlanningVariant variant();

    /** 根据是否走重型路径返回本次运行的不可变节点序列。 */
    List<GenerationAgentNode> nodes(boolean heavyPath);
}
