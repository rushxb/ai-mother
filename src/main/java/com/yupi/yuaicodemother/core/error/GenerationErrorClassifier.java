package com.yupi.yuaicodemother.core.error;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;

/**
 * 生成链路错误分类器。
 * 用统一分类驱动自动修复策略和前端展示，避免各处散落字符串判断。
 */
public final class GenerationErrorClassifier {

    public static final String CATEGORY_UNKNOWN = "unknown";
    public static final String CATEGORY_MODEL_QUOTA = "model_quota";
    public static final String CATEGORY_CODEGEN_EMPTY = "codegen_empty";
    public static final String CATEGORY_DEPENDENCY = "dependency";
    public static final String CATEGORY_BUILD = "build";
    public static final String CATEGORY_ROUTING = "routing";
    public static final String CATEGORY_PERMISSION = "permission";
    public static final String CATEGORY_RUNTIME = "runtime";

    private static final String MODEL_QUOTA_MESSAGE =
            "AI 模型服务额度不足，请检查模型服务账户余额或更换可用模型后重试。";

    private GenerationErrorClassifier() {
    }

    public static GenerationError classify(Throwable throwable) {
        return classify(throwable == null ? null : throwable.getMessage());
    }

    public static GenerationError classify(String errorMessage) {
        if (StrUtil.isBlank(errorMessage)) {
            return new GenerationError(CATEGORY_UNKNOWN, "生成失败", true);
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "insufficient balance",
                "insufficient quota",
                "insufficient credit",
                "billing",
                "balance not enough",
                "余额不足",
                "额度不足",
                "账户余额",
                "欠费")) {
            return new GenerationError(CATEGORY_MODEL_QUOTA, MODEL_QUOTA_MESSAGE, false);
        }
        if (containsAny(normalized,
                "未产出项目",
                "未产出有效项目文件",
                "missing generated project")) {
            return new GenerationError(CATEGORY_CODEGEN_EMPTY, errorMessage, true);
        }
        if (containsAny(normalized,
                "npm install",
                "缺少模块",
                "dependency",
                "module not found",
                "failed to resolve import")) {
            return new GenerationError(CATEGORY_DEPENDENCY, errorMessage, true);
        }
        if (containsAny(normalized,
                "npm run build",
                "syntax",
                "编译",
                "vite",
                "vue")) {
            return new GenerationError(CATEGORY_BUILD, errorMessage, true);
        }
        if (containsAny(normalized, "router", "404", "history")) {
            return new GenerationError(CATEGORY_ROUTING, errorMessage, true);
        }
        if (containsAny(normalized,
                "permission",
                "权限",
                "illegal",
                "非法路径")) {
            return new GenerationError(CATEGORY_PERMISSION, errorMessage, false);
        }
        return new GenerationError(CATEGORY_RUNTIME, errorMessage, true);
    }

    private static boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    public record GenerationError(String category, String message, boolean recoverable) {
    }
}
