package com.rush.rushaicodemother.orchestration.router;

import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 轻量编辑路由决策策略。
 */
@Component
@Order(50)
public class LightweightEditRoutingPolicy implements GenerationRoutingPolicy {

    private static final List<String> LIGHT_EDIT_KEYWORDS = List.of(
            "文案", "标题", "文字", "文本", "颜色", "样式", "字体", "字号", "间距",
            "背景", "圆角", "阴影", "透明度", "按钮文字", "占位符", "图标", "链接",
            "对齐", "边距", "宽度", "高度"
    );

    /**
 * 根据输入信号确定轻量编辑路由策略。
 *
 * @param signal 输入信号
 * @return 可选的轻量编辑路由策略；不存在时返回空值
 */
    @Override
    public Optional<GenerationModeDecision> decide(GenerationRoutingSignal signal) {
        if (!signal.existingWorkspace()
                || (!signal.containsAny(LIGHT_EDIT_KEYWORDS) && !signal.looksLikeSmallSingleFileEdit())) {
            return Optional.empty();
        }
        return Optional.of(GenerationModeDecision.of(
                GenerationMode.LIGHT_EDIT,
                0.86,
                "Request is limited to copy, color, style or small single-file edits",
                FallbackPolicy.ESCALATE_TO_HEAVY_EXPERT,
                ExpectedValidationLevel.FAST,
                GenerationRoutingDecisionCode.LIGHT_EDIT_SCOPE
        ));
    }
}
