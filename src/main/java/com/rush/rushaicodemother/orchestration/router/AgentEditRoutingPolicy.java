package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 智能体编辑路由决策策略。
 */
@Component
@Order(50)
public class AgentEditRoutingPolicy implements GenerationRoutingPolicy {

    private static final List<String> AGENT_EDIT_KEYWORDS = List.of(
            "新增功能", "增加功能", "实现功能", "跨文件", "多个文件", "接口", "api",
            "数据库", "db", "sql", "后端", "前后端", "全栈", "路由", "store", "pinia",
            "依赖", "package.json", "构建失败", "编译失败", "运行时报错", "修 bug", "修bug",
            "bug", "报错", "crud", "字段同步", "权限", "登录", "注册"
    );

    /**
 * 根据输入信号确定智能体编辑路由策略。
 *
 * @param signal 输入信号
 * @return 可选的智能体编辑路由策略；不存在时返回空值
 */
    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (!signal.existingWorkspace() || !signal.containsAny(AGENT_EDIT_KEYWORDS)) {
            return Optional.empty();
        }
        return Optional.of(GenerationModeDecision.of(
                GenerationMode.AGENT_EDIT,
                0.82,
                "请求涉及功能开发、跨文件修改、接口、数据库、依赖、构建错误或缺陷修复",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.BUILD,
                GenerationRoutingDecisionCode.AGENT_EDIT_COMPLEXITY
        ));
    }
}
