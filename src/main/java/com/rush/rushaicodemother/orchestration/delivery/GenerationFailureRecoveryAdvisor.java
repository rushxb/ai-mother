package com.rush.rushaicodemother.orchestration.delivery;

import com.rush.rushaicodemother.core.error.GenerationErrorClassifier;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;

import java.util.Locale;

/** 将稳定失败分类映射为可行动恢复建议，避免控制器和前端重复维护字符串分支。 */
final class GenerationFailureRecoveryAdvisor {

    private GenerationFailureRecoveryAdvisor() {
    }

    static Advice advise(GenerationTaskStatus status, String observedCategory) {
        if (status == GenerationTaskStatus.SUCCESS) {
            return new Advice(null, false, "none", "可查看或继续编辑已交付项目");
        }
        if (status == GenerationTaskStatus.CANCELLED) {
            return new Advice("cancelled", true, "resubmit", "任务已取消；如仍需生成，请重新提交任务");
        }
        if (status == GenerationTaskStatus.DEADLINE_EXCEEDED) {
            return new Advice("deadline_exceeded", true, "retry",
                    "任务超过截止时间；可稍后重试或缩小本次生成范围");
        }

        String category = normalizeCategory(observedCategory);
        return switch (category) {
            case GenerationErrorClassifier.CATEGORY_MODEL_QUOTA ->
                    new Advice(category, false, "check_model_account", "请检查模型账户额度后再提交");
            case GenerationErrorClassifier.CATEGORY_MODEL_AUTH ->
                    new Advice(category, false, "contact_admin", "请联系管理员检查模型认证配置");
            case GenerationErrorClassifier.CATEGORY_MODEL_RATE_LIMIT ->
                    new Advice(category, true, "retry_later", "模型请求过于频繁，请稍后重新提交任务");
            case GenerationErrorClassifier.CATEGORY_MODEL_TIMEOUT ->
                    new Advice(category, true, "retry", "模型响应超时，可重试或缩小本次生成范围");
            case GenerationErrorClassifier.CATEGORY_MODEL_UNAVAILABLE ->
                    new Advice(category, true, "retry_later", "模型服务暂时不可用，请稍后重新提交任务");
            case GenerationErrorClassifier.CATEGORY_PERMISSION ->
                    new Advice(category, false, "check_permissions", "请检查项目目录权限后再提交");
            case GenerationErrorClassifier.CATEGORY_WORKSPACE_RESULT_UNKNOWN ->
                    new Advice(category, false, "reconcile_workspace",
                            "请刷新并核对当前文件与保留目录；确认实际结果前请勿重试或回滚");
            case GenerationErrorClassifier.CATEGORY_AGENT_LOOP ->
                    new Advice(category, false, "refine_request", "请明确或缩小需求后重新提交");
            case GenerationErrorClassifier.CATEGORY_BUILD ->
                    new Advice(category, true, "fix_build", "可修正构建问题后重试");
            case GenerationErrorClassifier.CATEGORY_DEPENDENCY ->
                    new Advice(category, true, "check_dependencies", "请检查依赖源与依赖声明后重试");
            case GenerationErrorClassifier.CATEGORY_ROUTING ->
                    new Advice(category, true, "check_routes", "请检查生成项目路由后重试");
            case GenerationErrorClassifier.CATEGORY_CODEGEN_EMPTY ->
                    new Advice(category, true, "refine_request", "请补充需求细节后重试");
            case GenerationErrorClassifier.CATEGORY_MODEL_CANCELLED ->
                    new Advice(category, true, "resubmit", "如仍需生成，可重新提交任务");
            default -> new Advice(category, true, "retry", "可稍后重试；重复失败时请联系管理员");
        };
    }

    private static String normalizeCategory(String value) {
        if (value == null || value.isBlank()) {
            return GenerationErrorClassifier.CATEGORY_UNKNOWN;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.matches("[a-z0-9_]{1,64}")
                ? normalized : GenerationErrorClassifier.CATEGORY_UNKNOWN;
    }

    record Advice(String failureCategory,
                  boolean retryable,
                  String recoveryAction,
                  String nextStep) {
    }
}
