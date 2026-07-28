package com.rush.rushaicodemother.ai.intent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

/**
 * 后端意图检测器
 * 两层检测机制：关键词门控 + AI 路由
 * 确保后端生成只在用户明确要求时触发
 *
 * @author rush
 */
@Slf4j
@Service
public class BackendIntentDetector {

    /**
     * 明确的后端关键词 - 用户必须明确提到这些词才会触发后端生成
     */
    private static final Set<String> EXPLICIT_BACKEND_KEYWORDS = Set.of(
            "后端", "服务端", "后端项目", "服务端项目",
            "go后端", "go服务端", "go backend",
            "api服务", "api接口", "restful",
            "数据库", "sqlite", "mysql", "postgresql",
            "登录注册接口", "crud接口", "管理系统服务端",
            "后端工程", "服务端工程", "后端开发", "服务端开发"
    );

    /**
     * 前端优先关键词 - 即使包含后端相关内容，也优先选择前端
     */
    private static final Set<String> FRONTEND_PRIORITY_KEYWORDS = Set.of(
            "前端", "页面", "界面",
            "vue", "react", "angular", "svelte",
            "组件", "路由", "表单", "列表", "搜索",
            "dashboard", "后台管理", "管理系统页面",
            "落地页", "官网", "产品展示", "活动页",
            "样式", "css", "动画", "动效", "交互"
    );

    /**
     * 全栈关键词 - 用户明确要求全栈
     */
    private static final Set<String> FULLSTACK_KEYWORDS = Set.of(
            "全栈", "前后端", "前端和后端", "前端加后端",
            "前端调用后端", "接口联调",
            "前后端联调", "全栈开发", "fullstack", "full-stack"
    );

    /**
     * 后端关键词正则模式
     */
    private static final Pattern BACKEND_PATTERN = Pattern.compile(
            "(后端|服务端|(?<![a-z0-9])api(?![a-z0-9])|接口|数据库|sqlite|mysql|postgresql|go\\s*backend|crud\\s*接口)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 前端关键词正则模式
     */
    private static final Pattern FRONTEND_PATTERN = Pattern.compile(
            "(前端|页面|界面|(?<![a-z0-9])(?:ui|ux|vue|react|angular|svelte)(?![a-z0-9])|组件|路由|表单|dashboard|管理系统页面)",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * 检测用户意图
     *
     * @param userMessage 用户消息
     * @return 检测结果
     */
    public BackendIntentResult detectIntent(String userMessage) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (userMessage == null || userMessage.isBlank()) {
            return BackendIntentResult.none();
        }

        String normalized = userMessage.toLowerCase().trim();

        // 1. 检查是否明确要求全栈
        if (containsFullstackKeyword(normalized)) {
            log.info("检测到全栈关键词");
            return BackendIntentResult.fullstack();
        }

        // 2. 检查是否包含明确的后端关键词
        boolean hasExplicitBackend = containsExplicitBackendKeyword(normalized);

        // 3. 检查是否包含前端优先关键词
        boolean hasFrontendPriority = containsFrontendPriorityKeyword(normalized);

        // 4. 决策逻辑
        if (hasExplicitBackend && !hasFrontendPriority) {
            // 明确要求后端，且没有前端优先关键词
            log.info("检测到明确后端关键词");
            return BackendIntentResult.explicitBackend();
        }

        if (hasExplicitBackend && hasFrontendPriority) {
            // 同时包含后端和前端关键词，需要 AI 进一步判断
            log.info("检测到混合关键词（前端+后端），需要 AI 路由决策");
            return BackendIntentResult.ambiguous();
        }

        // 默认：前端优先
        log.info("未检测到明确后端关键词，默认前端优先");
        return BackendIntentResult.none();
    }

    /**
     * 根据检测结果约束代码生成类型
     *
     * @param intent      意图检测结果
     * @param aiRoutedType AI 路由的类型
     * @return 约束后的代码生成类型
     */
    public CodeGenTypeEnum constrainCodeGenType(BackendIntentResult intent, CodeGenTypeEnum aiRoutedType) {
        return switch (intent.level()) {
            case NONE -> {
                // 无后端意图，强制前端
                if (aiRoutedType == CodeGenTypeEnum.BACKEND_PROJECT || aiRoutedType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
                    log.info("用户未明确要求后端，AI 路由类型 {} 被约束为 VUE_PROJECT", aiRoutedType);
                    yield CodeGenTypeEnum.VUE_PROJECT;
                }
                yield aiRoutedType;
            }

            case EXPLICIT_BACKEND -> {
                // 明确后端意图时不允许模型把任务扩大成全栈或降级成前端
                if (aiRoutedType != CodeGenTypeEnum.BACKEND_PROJECT) {
                    log.info("用户明确要求后端，AI 路由类型 {} 被升级为 BACKEND_PROJECT", aiRoutedType);
                }
                yield CodeGenTypeEnum.BACKEND_PROJECT;
            }

            case FULLSTACK -> {
                if (aiRoutedType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
                    log.info("用户明确要求全栈，AI 路由类型 {} 被升级为 FULL_STACK_PROJECT", aiRoutedType);
                }
                yield CodeGenTypeEnum.FULL_STACK_PROJECT;
            }

            case AMBIGUOUS -> {
                // 混合意图只允许全栈或工程化前端，避免落入不完整的后端或静态页面
                if (aiRoutedType != CodeGenTypeEnum.FULL_STACK_PROJECT
                        && aiRoutedType != CodeGenTypeEnum.VUE_PROJECT) {
                    log.info("意图模糊，AI 路由类型 {} 被降级为 VUE_PROJECT", aiRoutedType);
                }
                yield aiRoutedType == CodeGenTypeEnum.FULL_STACK_PROJECT
                        ? CodeGenTypeEnum.FULL_STACK_PROJECT
                        : CodeGenTypeEnum.VUE_PROJECT;
            }
        };
    }

    private boolean containsExplicitBackendKeyword(String text) {
        return EXPLICIT_BACKEND_KEYWORDS.stream()
                .anyMatch(keyword -> text.contains(keyword.toLowerCase()))
                || BACKEND_PATTERN.matcher(text).find();
    }

    private boolean containsFrontendPriorityKeyword(String text) {
        return FRONTEND_PRIORITY_KEYWORDS.stream()
                .anyMatch(keyword -> text.contains(keyword.toLowerCase()))
                || FRONTEND_PATTERN.matcher(text).find();
    }

    private boolean containsFullstackKeyword(String text) {
        boolean hasExplicitFullstackKeyword = FULLSTACK_KEYWORDS.stream()
                .anyMatch(keyword -> text.contains(keyword.toLowerCase()));
        if (hasExplicitFullstackKeyword) {
            return true;
        }
        return text.contains("完整应用")
                && BACKEND_PATTERN.matcher(text).find()
                && FRONTEND_PATTERN.matcher(text).find();
    }

    /**
     * 后端意图检测结果
     */
    public record BackendIntentResult(IntentLevel level, String reason) {

        public static BackendIntentResult none() {
            return new BackendIntentResult(IntentLevel.NONE, "未检测到后端意图");
        }

        public static BackendIntentResult explicitBackend() {
            return new BackendIntentResult(IntentLevel.EXPLICIT_BACKEND, "用户明确要求后端");
        }

        public static BackendIntentResult fullstack() {
            return new BackendIntentResult(IntentLevel.FULLSTACK, "用户明确要求全栈");
        }

        public static BackendIntentResult ambiguous() {
            return new BackendIntentResult(IntentLevel.AMBIGUOUS, "意图模糊，需要 AI 进一步判断");
        }

        public enum IntentLevel {
            NONE,           // 无后端意图，强制前端
            EXPLICIT_BACKEND, // 明确后端意图
            FULLSTACK,      // 全栈意图
            AMBIGUOUS       // 模糊意图
        }
    }
}
