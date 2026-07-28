package com.rush.rushaicodemother.core.error;

import cn.hutool.core.util.StrUtil;

import java.util.Locale;
import java.util.concurrent.CancellationException;

/**
 * 生成链路错误分类器。
 * 用统一分类驱动自动修复策略和前端展示，避免各处散落字符串判断。
 */
public final class GenerationErrorClassifier {

    public static final String CATEGORY_UNKNOWN = "unknown";
    public static final String CATEGORY_MODEL_QUOTA = "model_quota";
    public static final String CATEGORY_MODEL_RATE_LIMIT = "model_rate_limit";
    public static final String CATEGORY_MODEL_AUTH = "model_auth";
    public static final String CATEGORY_MODEL_TIMEOUT = "model_timeout";
    public static final String CATEGORY_MODEL_UNAVAILABLE = "model_unavailable";
    public static final String CATEGORY_MODEL_CANCELLED = "model_cancelled";
    public static final String CATEGORY_CODEGEN_EMPTY = "codegen_empty";
    public static final String CATEGORY_DEPENDENCY = "dependency";
    public static final String CATEGORY_BUILD = "build";
    public static final String CATEGORY_ROUTING = "routing";
    public static final String CATEGORY_PERMISSION = "permission";
    public static final String CATEGORY_AGENT_LOOP = "agent_loop";
    public static final String CATEGORY_RUNTIME = "runtime";

    private static final String MODEL_QUOTA_MESSAGE =
            "AI 模型服务额度不足，请检查模型服务账户余额或更换可用模型后重试。";
    private static final String MODEL_RATE_LIMIT_MESSAGE = "AI 模型服务请求过于频繁，请稍后重试。";
    private static final String MODEL_AUTH_MESSAGE = "AI 模型服务认证失败，请联系管理员检查模型配置。";
    private static final String MODEL_TIMEOUT_MESSAGE = "上游模型超时，请稍后重试。";
    private static final String MODEL_UNAVAILABLE_MESSAGE = "AI 模型服务暂时不可用，请稍后重试。";
    private static final String MODEL_CANCELLED_MESSAGE = "AI 模型请求已取消。";
    private static final String CODEGEN_EMPTY_MESSAGE = "未生成有效项目代码，请重试。";
    private static final String DEPENDENCY_MESSAGE = "项目依赖处理失败，请稍后重试。";
    private static final String BUILD_MESSAGE = "项目构建失败，请检查生成代码后重试。";
    private static final String ROUTING_MESSAGE = "项目路由验证失败，请检查路由配置后重试。";
    private static final String PERMISSION_MESSAGE = "项目文件访问失败，请检查项目权限后重试。";
    private static final String AGENT_LOOP_MESSAGE = "AI 生成连续重复相同操作且未取得新进展，已提前停止。";
    private static final String GENERIC_MESSAGE = "代码生成失败，请稍后重试。";

    private GenerationErrorClassifier() {
    }

    /**
 * 对生成错误进行分类。
 *
 * @param throwable 待处理的异常
 * @return 生成错误
 */
    public static GenerationError classify(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof CancellationException
                    || current instanceof InterruptedException
                    || current instanceof GenerationCancellationSignal) {
                return new GenerationError(
                        CATEGORY_MODEL_CANCELLED, MODEL_CANCELLED_MESSAGE, false);
            }
            if (current instanceof java.util.concurrent.TimeoutException) {
                return new GenerationError(
                        CATEGORY_MODEL_TIMEOUT, MODEL_TIMEOUT_MESSAGE, true);
            }
            if (current instanceof GenerationAgentLoopException) {
                return new GenerationError(CATEGORY_AGENT_LOOP, AGENT_LOOP_MESSAGE, false);
            }
            current = current.getCause();
        }
        return classify(throwable == null ? null : throwable.getMessage());
    }

    /**
 * 对生成错误进行分类。
 *
 * @param errorMessage 错误消息
 * @return 生成错误
 */
    public static GenerationError classify(String errorMessage) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (StrUtil.isBlank(errorMessage)) {
            return new GenerationError(CATEGORY_UNKNOWN, GENERIC_MESSAGE, true);
        }
        String normalized = errorMessage.toLowerCase(Locale.ROOT);
        if (containsAny(normalized,
                "canceled",
                "cancelled",
                "request was canceled",
                "请求已取消",
                "模型请求取消")) {
            return new GenerationError(
                    CATEGORY_MODEL_CANCELLED, MODEL_CANCELLED_MESSAGE, false);
        }
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
        if (containsAny(normalized, "429", "too many requests", "rate limit")) {
            return new GenerationError(CATEGORY_MODEL_RATE_LIMIT, MODEL_RATE_LIMIT_MESSAGE, true);
        }
        if (containsAny(normalized, "401", "403", "unauthorized", "invalid api key", "authentication")) {
            return new GenerationError(CATEGORY_MODEL_AUTH, MODEL_AUTH_MESSAGE, false);
        }
        if (containsAny(normalized,
                "read timed out",
                "sockettimeoutexception",
                "resourceaccessexception",
                "upstream timeout",
                "模型调用超时",
                "上游模型超时")) {
            return new GenerationError(CATEGORY_MODEL_TIMEOUT, MODEL_TIMEOUT_MESSAGE, true);
        }
        if (containsAny(normalized, "503", "service unavailable", "connection refused")) {
            return new GenerationError(CATEGORY_MODEL_UNAVAILABLE, MODEL_UNAVAILABLE_MESSAGE, true);
        }
        if (containsAny(normalized,
                "未产出项目",
                "未产出有效项目文件",
                "missing generated project")) {
            return new GenerationError(CATEGORY_CODEGEN_EMPTY, CODEGEN_EMPTY_MESSAGE, true);
        }
        if (containsAny(normalized,
                "pnpm install",
                "npm install",
                "缺少模块",
                "dependency",
                "module not found",
                "failed to resolve import")) {
            return new GenerationError(CATEGORY_DEPENDENCY, DEPENDENCY_MESSAGE, true);
        }
        if (containsAny(normalized,
                "pnpm run build",
                "npm run build",
                "syntax",
                "编译",
                "vite",
                "vue")) {
            return new GenerationError(CATEGORY_BUILD, BUILD_MESSAGE, true);
        }
        if (containsAny(normalized, "router", "404", "history")) {
            return new GenerationError(CATEGORY_ROUTING, ROUTING_MESSAGE, true);
        }
        if (containsAny(normalized,
                "permission",
                "权限",
                "illegal",
                "非法路径")) {
            return new GenerationError(CATEGORY_PERMISSION, PERMISSION_MESSAGE, false);
        }
        return new GenerationError(CATEGORY_RUNTIME, GENERIC_MESSAGE, true);
    }

    /** 返回{@code contains}{@code Any}。 */
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
