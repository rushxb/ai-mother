package com.yupi.yuaicodemother.orchestration.agent;

import cn.hutool.core.util.StrUtil;
import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.GenerationOrchestrationRequest;
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

    private static final List<String> BACKEND_KEYWORDS = List.of(
            "后端", "服务端", "go后端", "go 后端", "golang", "api服务", "api 服务", "接口服务",
            "数据库", "sqlite", "sqllite", "sql lite", "登录注册接口", "crud接口", "crud 接口"
    );

    private final GenerationAgentSupport generationAgentSupport;

    public GenerationRoutingSupport(GenerationAgentSupport generationAgentSupport) {
        this.generationAgentSupport = generationAgentSupport;
    }

    public CodeGenTypeEnum routeTargetType(GenerationOrchestrationRequest request) {
        return routeTargetType(request, isComplexRequest(request == null ? null : request.userMessage()));
    }

    public CodeGenTypeEnum routeTargetType(GenerationOrchestrationRequest request, boolean complex) {
        if (request == null) {
            return CodeGenTypeEnum.HTML;
        }
        if (containsAny(StrUtil.blankToDefault(request.userMessage(), "").toLowerCase(Locale.ROOT), BACKEND_KEYWORDS)) {
            return CodeGenTypeEnum.BACKEND_PROJECT;
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

    public boolean requiresBuildValidation(GenerationOrchestrationRequest request, CodeGenTypeEnum targetType) {
        if (request == null || targetType == null) {
            return false;
        }
        String normalized = StrUtil.blankToDefault(request.userMessage(), "").toLowerCase(Locale.ROOT);
        if (containsAny(normalized, BUILD_KEYWORDS)) {
            return true;
        }
        if (targetType != CodeGenTypeEnum.VUE_PROJECT) {
            return false;
        }
        return !request.hasGeneratedCode()
                || (request.currentType() != null && request.currentType().canUpgradeTo(targetType));
    }

    public boolean shouldUseHeavyPath(GenerationOrchestrationRequest request) {
        CodeGenTypeEnum targetType = routeTargetType(request);
        return requiresBuildValidation(request, targetType);
    }

    public boolean isComplexRequest(String userMessage) {
        return generationAgentSupport.isComplexRequest(userMessage);
    }

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
