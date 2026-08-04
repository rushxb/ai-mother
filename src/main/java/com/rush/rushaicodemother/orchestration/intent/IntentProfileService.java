package com.rush.rushaicodemother.orchestration.intent;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 将用户提示词与工作区状态解析为可复用的结构化意图画像。
 *
 * <p>解析过程完全本地、确定且无副作用；为限制异常超长输入的分析成本，
 * 只分析提示词首尾各一段内容，画像中不会保存原始提示词。</p>
 */
@Service
public class IntentProfileService {

    private static final int MAX_ANALYZED_CHARACTERS = 20_000;
    private static final int ANALYZED_EDGE_CHARACTERS = MAX_ANALYZED_CHARACTERS / 2;

    private static final List<String> REPAIR_KEYWORDS = List.of(
            "修复", "修 bug", "修bug", "bug", "报错", "异常", "错误", "失败", "无法运行",
            "崩溃", "故障", "fix", "broken", "error", "exception", "failed", "failure", "crash"
    );
    private static final List<String> EXPLAIN_KEYWORDS = List.of(
            "解释", "说明", "分析原因", "为什么", "怎么实现", "如何实现", "原理", "讲解",
            "explain", "why", "how does", "how to", "analyze"
    );
    private static final List<String> EDIT_ACTION_KEYWORDS = List.of(
            "修改", "调整", "更改", "替换", "改成", "换成", "新增", "增加", "实现", "重构",
            "删除", "升级", "迁移", "modify", "change", "replace", "add", "implement", "refactor",
            "delete", "remove", "upgrade", "migrate"
    );
    private static final List<String> LIGHT_EDIT_KEYWORDS = List.of(
            "文案", "标题", "文字", "颜色", "字号", "字体", "间距", "边距", "圆角", "按钮",
            "图标", "样式", "布局微调", "copy", "title", "text", "color", "font", "spacing",
            "margin", "padding", "border radius", "button", "icon", "style"
    );
    private static final List<String> FRONTEND_KEYWORDS = List.of(
            "前端", "前后端", "页面", "首页", "组件", "界面", "布局", "样式", "按钮", "表单", "弹窗",
            "vue", "react", "css", "html", "frontend", "page", "component", "ui", "ux"
    );
    private static final List<String> BACKEND_KEYWORDS = List.of(
            "后端", "前后端", "服务端", "controller", "service", "repository", "spring", "java", "backend", "server"
    );
    private static final List<String> API_KEYWORDS = List.of(
            "接口", "api", "endpoint", "rest", "graphql", "websocket", "契约"
    );
    private static final List<String> DATABASE_KEYWORDS = List.of(
            "数据库", "数据表", "表结构", "字段", "sql", "mysql", "postgres", "redis", "mongodb",
            "database", "schema", "migration", "orm", "mybatis", "jpa"
    );
    private static final List<String> AUTHENTICATION_KEYWORDS = List.of(
            "登录", "注册", "鉴权", "认证", "授权", "权限", "角色", "jwt", "oauth", "sso",
            "login", "register", "authentication", "authorization", "permission", "role"
    );
    private static final List<String> BUILD_KEYWORDS = List.of(
            "构建", "编译", "依赖", "打包", "部署配置", "package.json", "pom.xml", "gradle", "maven",
            "npm", "pnpm", "yarn", "vite", "webpack", "build", "compile", "dependency"
    );
    private static final List<String> INFRASTRUCTURE_KEYWORDS = List.of(
            "微服务", "分布式", "kubernetes", "k8s", "docker", "容器", "网关", "消息队列", "高并发",
            "多租户", "限流", "熔断", "链路追踪", "microservice", "distributed", "infrastructure"
    );
    private static final List<String> TESTING_KEYWORDS = List.of(
            "测试", "单测", "集成测试", "回归", "验收", "test", "testing", "junit", "playwright", "cypress"
    );
    private static final List<String> DOCUMENTATION_KEYWORDS = List.of(
            "文档", "说明书", "readme", "注释", "documentation", "docs", "comment"
    );
    private static final List<String> MULTI_FILE_KEYWORDS = List.of(
            "跨文件", "多个文件", "多文件", "前后端", "全栈", "整个项目", "全项目", "所有文件",
            "cross-file", "multiple files", "full stack", "entire project", "whole project"
    );
    private static final List<String> SINGLE_FILE_KEYWORDS = List.of(
            "单文件", "一个文件", "当前文件", "这个文件", "single file", "one file", "this file"
    );
    private static final List<String> HIGH_COMPLEXITY_KEYWORDS = List.of(
            "完整重构", "彻底重构", "全部重写", "从头重写", "推倒重来", "更换技术栈", "换框架",
            "支付系统", "复杂工作流", "架构改造", "领域驱动", "高并发", "多租户", "微服务", "分布式",
            "rewrite everything", "from scratch", "change framework", "payment system", "complex workflow",
            "architecture", "multi-tenant", "microservice", "distributed"
    );
    private static final List<String> HIGH_DESTRUCTIVE_KEYWORDS = List.of(
            "全部删除", "删除全部", "清空", "全部重写", "从头重写", "推倒重来", "替换整个", "更换技术栈",
            "换框架", "drop table", "drop database", "delete all", "remove all", "rewrite everything",
            "replace entire", "change framework"
    );
    private static final List<String> MEDIUM_DESTRUCTIVE_KEYWORDS = List.of(
            "重构", "迁移", "升级", "删除", "移除", "表结构", "字段变更", "数据库迁移",
            "refactor", "migrate", "upgrade", "delete", "remove", "schema change"
    );

    public IntentProfile analyze(GenerationTaskRequest request,
                                 CodeGenTypeEnum codeGenType,
                                 GenerationWorkspace workspace) {
        String normalizedMessage = normalizeForAnalysis(request == null ? null : request.message());
        if (normalizedMessage.isBlank()) {
            return IntentProfile.unknown();
        }

        boolean firstGeneration = workspace != null && !workspace.exists();
        Set<IntentAffectedScope> scopes = detectScopes(normalizedMessage, codeGenType);
        boolean requiresDatabase = scopes.contains(IntentAffectedScope.DATABASE);
        boolean requiresBackend = requiresDatabase
                || scopes.contains(IntentAffectedScope.BACKEND)
                || scopes.contains(IntentAffectedScope.API)
                || scopes.contains(IntentAffectedScope.AUTHENTICATION);
        IntentOperationType operationType = detectOperationType(normalizedMessage, firstGeneration);
        IntentDestructiveRisk destructiveRisk = detectDestructiveRisk(normalizedMessage);
        int expectedFileCount = estimateFileCount(
                normalizedMessage, firstGeneration, scopes, requiresBackend, requiresDatabase);
        IntentSemanticComplexity complexity = detectComplexity(
                normalizedMessage, firstGeneration, scopes, requiresBackend, requiresDatabase,
                destructiveRisk, expectedFileCount);
        IntentValidationRisk validationRisk = detectValidationRisk(
                operationType, scopes, complexity, destructiveRisk, requiresBackend, requiresDatabase);
        double confidence = calculateConfidence(
                normalizedMessage, firstGeneration, scopes, operationType, complexity);

        return new IntentProfile(
                operationType,
                scopes,
                complexity,
                requiresBackend,
                requiresDatabase,
                destructiveRisk,
                expectedFileCount,
                validationRisk,
                confidence
        );
    }

    private IntentOperationType detectOperationType(String message, boolean firstGeneration) {
        if (firstGeneration) {
            return IntentOperationType.CREATE;
        }
        if (containsAny(message, REPAIR_KEYWORDS)) {
            return IntentOperationType.REPAIR;
        }
        if (containsAny(message, EXPLAIN_KEYWORDS) && !containsAny(message, EDIT_ACTION_KEYWORDS)) {
            return IntentOperationType.EXPLAIN;
        }
        return IntentOperationType.EDIT;
    }

    private Set<IntentAffectedScope> detectScopes(String message, CodeGenTypeEnum codeGenType) {
        EnumSet<IntentAffectedScope> scopes = EnumSet.noneOf(IntentAffectedScope.class);
        addScopeWhenMatched(scopes, IntentAffectedScope.FRONTEND, message, FRONTEND_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.BACKEND, message, BACKEND_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.API, message, API_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.DATABASE, message, DATABASE_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.AUTHENTICATION, message, AUTHENTICATION_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.BUILD_CONFIGURATION, message, BUILD_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.INFRASTRUCTURE, message, INFRASTRUCTURE_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.TESTING, message, TESTING_KEYWORDS);
        addScopeWhenMatched(scopes, IntentAffectedScope.DOCUMENTATION, message, DOCUMENTATION_KEYWORDS);

        if (scopes.isEmpty() && codeGenType != null && codeGenType != CodeGenTypeEnum.HTML) {
            scopes.add(IntentAffectedScope.FRONTEND);
        }
        if (scopes.isEmpty()) {
            scopes.add(IntentAffectedScope.UNKNOWN);
        }
        return Set.copyOf(scopes);
    }

    private void addScopeWhenMatched(Set<IntentAffectedScope> scopes,
                                     IntentAffectedScope scope,
                                     String message,
                                     List<String> keywords) {
        if (containsAny(message, keywords)) {
            scopes.add(scope);
        }
    }

    private IntentDestructiveRisk detectDestructiveRisk(String message) {
        if (containsAny(message, HIGH_DESTRUCTIVE_KEYWORDS)) {
            return IntentDestructiveRisk.HIGH;
        }
        if (containsAny(message, MEDIUM_DESTRUCTIVE_KEYWORDS)) {
            return IntentDestructiveRisk.MEDIUM;
        }
        return IntentDestructiveRisk.LOW;
    }

    private int estimateFileCount(String message,
                                  boolean firstGeneration,
                                  Set<IntentAffectedScope> scopes,
                                  boolean requiresBackend,
                                  boolean requiresDatabase) {
        if (containsAny(message, SINGLE_FILE_KEYWORDS)) {
            return 1;
        }
        if (firstGeneration) {
            return requiresBackend || requiresDatabase ? 12 : 6;
        }
        if (containsAny(message, MULTI_FILE_KEYWORDS)) {
            return Math.max(4, scopes.size() + 2);
        }
        if (requiresBackend && requiresDatabase) {
            return 6;
        }
        if (requiresBackend) {
            return 4;
        }
        if (containsAny(message, LIGHT_EDIT_KEYWORDS)) {
            return 1;
        }
        return Math.max(2, scopes.size());
    }

    private IntentSemanticComplexity detectComplexity(String message,
                                                       boolean firstGeneration,
                                                       Set<IntentAffectedScope> scopes,
                                                       boolean requiresBackend,
                                                       boolean requiresDatabase,
                                                       IntentDestructiveRisk destructiveRisk,
                                                       int expectedFileCount) {
        if (containsAny(message, HIGH_COMPLEXITY_KEYWORDS)
                || destructiveRisk == IntentDestructiveRisk.HIGH
                || scopes.contains(IntentAffectedScope.INFRASTRUCTURE)
                || (requiresBackend && requiresDatabase
                && scopes.contains(IntentAffectedScope.AUTHENTICATION))) {
            return IntentSemanticComplexity.HIGH;
        }
        if (!firstGeneration
                && expectedFileCount <= 2
                && !requiresBackend
                && !requiresDatabase
                && destructiveRisk == IntentDestructiveRisk.LOW
                && containsAny(message, LIGHT_EDIT_KEYWORDS)) {
            return IntentSemanticComplexity.LOW;
        }
        return IntentSemanticComplexity.MEDIUM;
    }

    private IntentValidationRisk detectValidationRisk(IntentOperationType operationType,
                                                       Set<IntentAffectedScope> scopes,
                                                       IntentSemanticComplexity complexity,
                                                       IntentDestructiveRisk destructiveRisk,
                                                       boolean requiresBackend,
                                                       boolean requiresDatabase) {
        if (complexity == IntentSemanticComplexity.HIGH
                || destructiveRisk == IntentDestructiveRisk.HIGH
                || (requiresBackend && requiresDatabase)
                || scopes.contains(IntentAffectedScope.AUTHENTICATION)
                || scopes.contains(IntentAffectedScope.INFRASTRUCTURE)) {
            return IntentValidationRisk.HIGH;
        }
        if (operationType == IntentOperationType.REPAIR
                || requiresBackend
                || requiresDatabase
                || scopes.contains(IntentAffectedScope.API)
                || scopes.contains(IntentAffectedScope.BUILD_CONFIGURATION)
                || scopes.contains(IntentAffectedScope.TESTING)) {
            return IntentValidationRisk.MEDIUM;
        }
        return IntentValidationRisk.LOW;
    }

    private double calculateConfidence(String message,
                                       boolean firstGeneration,
                                       Set<IntentAffectedScope> scopes,
                                       IntentOperationType operationType,
                                       IntentSemanticComplexity complexity) {
        double confidence = firstGeneration ? 0.92 : 0.72;
        if (!scopes.contains(IntentAffectedScope.UNKNOWN)) {
            confidence += 0.08;
        }
        if (operationType == IntentOperationType.REPAIR || operationType == IntentOperationType.EXPLAIN) {
            confidence += 0.07;
        }
        if (complexity == IntentSemanticComplexity.LOW || complexity == IntentSemanticComplexity.HIGH) {
            confidence += 0.05;
        }
        if (message.length() < 6) {
            confidence -= 0.25;
        }
        return Math.max(0.0, Math.min(0.99, confidence));
    }

    private String normalizeForAnalysis(String message) {
        String normalized = StrUtil.blankToDefault(message, "").trim().toLowerCase(Locale.ROOT);
        if (normalized.length() <= MAX_ANALYZED_CHARACTERS) {
            return normalized;
        }
        return normalized.substring(0, ANALYZED_EDGE_CHARACTERS)
                + " "
                + normalized.substring(normalized.length() - ANALYZED_EDGE_CHARACTERS);
    }

    private boolean containsAny(String message, List<String> keywords) {
        return keywords.stream().anyMatch(message::contains);
    }
}
