package com.rush.rushaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 生成路由与构建门禁判定的共享支持类。
 */
@Component
public class GenerationRoutingSupport {

    private static final List<String> BUILD_KEYWORDS = List.of(
            "build", "构建", "打包", "编译", "测试", "lint", "校验", "发布", "npm", "vite", "工程化", "vue工程"
    );

    private static final List<String> FULL_STACK_KEYWORDS = List.of(
            "全栈", "前后端", "前端和后端", "前端+后端", "前端 后端", "frontend backend",
            "前端调用后端", "接口联调", "联调", "完整应用", "完整项目"
    );

    private static final List<String> BACKEND_KEYWORDS = List.of(
            "后端", "服务端", "go后端", "go 后端", "golang", "api服务", "api 服务", "接口服务",
            "数据库", "sqlite", "sqllite", "sql lite", "登录注册接口", "crud接口", "crud 接口"
    );

    private static final List<String> VUE_KEYWORDS = List.of(
            "vue", "vue3", "vue 3", "vue项目", "vue 项目"
    );

    private final GenerationAgentSupport generationAgentSupport;

    public GenerationRoutingSupport(GenerationAgentSupport generationAgentSupport) {
        this.generationAgentSupport = generationAgentSupport;
    }

    /**
 * 为目标类型选择处理路由。
 *
 * @param request 请求参数
 * @return 目标类型
 */
    public CodeGenTypeEnum routeTargetType(GenerationOrchestrationRequest request) {
        return routeTargetType(request, isComplexRequest(request == null ? null : request.userMessage()));
    }

    /**
 * 为目标类型选择处理路由。
 *
 * @param request 请求参数
 * @param complex {@code complex} 对应的调用参数
 * @return 目标类型
 */
    public CodeGenTypeEnum routeTargetType(GenerationOrchestrationRequest request, boolean complex) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (request == null) {
            return CodeGenTypeEnum.HTML;
        }
        String normalized = StrUtil.blankToDefault(request.userMessage(), "").toLowerCase(Locale.ROOT);
        if (request.currentType() == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return CodeGenTypeEnum.FULL_STACK_PROJECT;
        }
        if (containsAny(normalized, FULL_STACK_KEYWORDS)) {
            return CodeGenTypeEnum.FULL_STACK_PROJECT;
        }
        if (containsAny(normalized, BACKEND_KEYWORDS)) {
            return CodeGenTypeEnum.BACKEND_PROJECT;
        }
        if (containsAny(normalized, VUE_KEYWORDS)) {
            return CodeGenTypeEnum.VUE_PROJECT;
        }
        if (!complex && request.currentType() == CodeGenTypeEnum.HTML) {
            return CodeGenTypeEnum.HTML;
        }
        if (request.currentType() == CodeGenTypeEnum.BACKEND_PROJECT) {
            return CodeGenTypeEnum.BACKEND_PROJECT;
        }
        if (request.currentType() == CodeGenTypeEnum.VUE_PROJECT) {
            return CodeGenTypeEnum.VUE_PROJECT;
        }
        if (request.routingFunction() == null) {
            return complex ? CodeGenTypeEnum.VUE_PROJECT : request.currentType();
        }
        // 将可能失败的操作收敛在统一异常边界内，便于清理资源和转换错误。
        try {
            String routingPrompt = "请根据以下需求判断最适合的生成模式：\n" + request.userMessage();
            CodeGenTypeEnum routedType = request.routingFunction().apply(routingPrompt);
            if (routedType != null) {
                return routedType;
            }
        } catch (Exception ignored) {
        }
        return complex ? CodeGenTypeEnum.VUE_PROJECT : request.currentType();
    }

    /**
 * 校验并返回有效的{@code s}构建校验。
 *
 * @param request 请求参数
 * @param targetType 目标类型
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean requiresBuildValidation(GenerationOrchestrationRequest request, CodeGenTypeEnum targetType) {
        if (request == null || targetType == null) {
            return false;
        }
        if (targetType == CodeGenTypeEnum.BACKEND_PROJECT) {
            return true;
        }
        String normalized = StrUtil.blankToDefault(request.userMessage(), "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, BUILD_KEYWORDS)) {
            return true;
        }
        if (targetType != CodeGenTypeEnum.VUE_PROJECT && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return false;
        }
        if (!request.hasGeneratedCode()) {
            return false;
        }
        return request.currentType() != null && request.currentType().canUpgradeTo(targetType);
    }

    /**
 * 判断是否应执行{@code Use}重型路径。
 *
 * @param request 请求参数
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    public boolean shouldUseHeavyPath(GenerationOrchestrationRequest request) {
        CodeGenTypeEnum targetType = routeTargetType(request);
        return requiresBuildValidation(request, targetType);
    }

    public boolean isComplexRequest(String userMessage) {
        return generationAgentSupport.isComplexRequest(userMessage);
    }

    /** 返回{@code contains}{@code Any}。 */
    private boolean containsAny(String value, List<String> keywords) {
        if (StrUtil.isBlank(value)) {
            return false;
        }
        for (String keyword : keywords) {
            if (value.contains(keyword)) {
                return true;
            }
        }
        return false;
    }
}
