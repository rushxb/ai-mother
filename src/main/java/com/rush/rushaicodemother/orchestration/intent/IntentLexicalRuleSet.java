package com.rush.rushaicodemother.orchestration.intent;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 版本化的本地意图词法规则集。
 *
 * <p>该模块在初始化时预编译规则，解析路径不会反复构造正则。英文关键词使用
 * ASCII 词边界，中文可与英文标识紧邻；命中前还会排除同一分句内的
 * 否定表达。规则语义变更时必须同步提升 {@link #VERSION}，以便决策回放。</p>
 */
final class IntentLexicalRuleSet {

    static final String VERSION = "intent-lexical/1.2.0";

    private static final int NEGATION_LOOKBACK_CHARACTERS = 24;
    private static final Pattern ENGLISH_NEGATION = Pattern.compile(
            "(?i)(?:^|[^a-z0-9_])(?:do\\s+not|don't|dont|never|without)"
                    + "(?:\\s+[a-z0-9_-]+){0,4}\\s*$"
    );
    private static final Pattern DIRECT_ENGLISH_NEGATION = Pattern.compile("(?i)(?:^|\\s)(?:not|no)\\s*$");
    private static final Pattern CHINESE_NEGATION = Pattern.compile(
            "(?:不需要|不要|不用|不必|不能|不涉及|不包含|不使用|无需|无须|禁止|拒绝|别)"
                    + "(?:再|要|需要|进行|涉及|包含|使用|帮我|对|任何|相关|现有)?"
                    + "[^，。；;！!？?\\r\\n]{0,8}$"
    );
    private static final Pattern DIRECT_CHINESE_NEGATION = Pattern.compile("(?:不|别)(?:再|要|应|应该|可|可以)?$");
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile("[，。；;！!？?\\r\\n]");

    private static final IntentLexicalRuleSet DEFAULT = new IntentLexicalRuleSet(defaultVocabulary());

    private final Map<IntentLexicalFeature, Pattern> rules;

    private IntentLexicalRuleSet(Map<IntentLexicalFeature, List<String>> vocabulary) {
        EnumMap<IntentLexicalFeature, Pattern> compiledRules =
                new EnumMap<>(IntentLexicalFeature.class);
        for (IntentLexicalFeature feature : IntentLexicalFeature.values()) {
            List<String> keywords = Objects.requireNonNull(
                    vocabulary.get(feature), "Missing lexical rules for " + feature);
            compiledRules.put(feature, compileFeaturePattern(keywords));
        }
        this.rules = Map.copyOf(compiledRules);
    }

    static IntentLexicalRuleSet defaultRules() {
        return DEFAULT;
    }

    String version() {
        return VERSION;
    }

    boolean matches(String normalizedMessage, IntentLexicalFeature feature) {
        if (normalizedMessage == null || normalizedMessage.isBlank() || feature == null) {
            return false;
        }
        Matcher matcher = rules.get(feature).matcher(normalizedMessage);
        while (matcher.find()) {
            if (!isNegated(normalizedMessage, matcher.start())) {
                return true;
            }
        }
        return false;
    }

    private static Pattern compileFeaturePattern(List<String> keywords) {
        String expression = keywords.stream()
                // 长词优先可避免短词抢先命中后改变否定窗口的判定位置。
                .sorted(Comparator.comparingInt(String::length).reversed())
                .map(IntentLexicalRuleSet::compileKeywordExpression)
                .collect(Collectors.joining("|", "(?:", ")"));
        return Pattern.compile(expression, Pattern.CASE_INSENSITIVE);
    }

    private static String compileKeywordExpression(String keyword) {
        String[] parts = keyword.trim().split("\\s+");
        StringBuilder expression = new StringBuilder();
        if (startsWithAsciiWordCharacter(parts[0])) {
            expression.append("(?<![A-Za-z0-9_])");
        }
        for (int index = 0; index < parts.length; index++) {
            if (index > 0) {
                expression.append("\\s+");
            }
            expression.append(Pattern.quote(parts[index]));
        }
        if (endsWithAsciiWordCharacter(parts[parts.length - 1])) {
            expression.append("(?![A-Za-z0-9_])");
        }
        return expression.toString();
    }

    private static boolean startsWithAsciiWordCharacter(String value) {
        return !value.isEmpty() && isAsciiWordCharacter(value.charAt(0));
    }

    private static boolean endsWithAsciiWordCharacter(String value) {
        return !value.isEmpty() && isAsciiWordCharacter(value.charAt(value.length() - 1));
    }

    private static boolean isAsciiWordCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '_';
    }

    private static boolean isNegated(String message, int matchStart) {
        int clauseStart = findClauseStart(message, matchStart);
        int lookbackStart = Math.max(clauseStart, matchStart - NEGATION_LOOKBACK_CHARACTERS);
        String prefix = message.substring(lookbackStart, matchStart).stripTrailing();
        return CHINESE_NEGATION.matcher(prefix).find()
                || DIRECT_CHINESE_NEGATION.matcher(prefix).find()
                || ENGLISH_NEGATION.matcher(prefix).find()
                || DIRECT_ENGLISH_NEGATION.matcher(prefix).find();
    }

    private static int findClauseStart(String message, int matchStart) {
        int start = 0;
        Matcher boundaryMatcher = CLAUSE_BOUNDARY.matcher(message);
        boundaryMatcher.region(0, matchStart);
        while (boundaryMatcher.find()) {
            start = boundaryMatcher.end();
        }
        return start;
    }

    private static Map<IntentLexicalFeature, List<String>> defaultVocabulary() {
        EnumMap<IntentLexicalFeature, List<String>> vocabulary =
                new EnumMap<>(IntentLexicalFeature.class);
        vocabulary.put(IntentLexicalFeature.REPAIR_ACTION, List.of(
                "修复", "修 bug", "修bug", "fix"
        ));
        vocabulary.put(IntentLexicalFeature.REPAIR_SYMPTOM, List.of(
                "bug", "报错", "异常", "错误", "失败", "无法运行", "崩溃", "故障",
                "broken", "error", "exception", "failed", "failure", "crash"
        ));
        vocabulary.put(IntentLexicalFeature.EXPLANATION_ACTION, List.of(
                "解释", "说明", "分析原因", "为什么", "怎么实现", "如何实现", "原理", "讲解",
                "explain", "why", "how does", "how to", "analyze"
        ));
        vocabulary.put(IntentLexicalFeature.AUDIT_ACTION, List.of(
                "审计", "代码审查", "安全审查", "风险评估", "检查一下", "检视",
                "audit", "code review", "security review", "risk assessment"
        ));
        vocabulary.put(IntentLexicalFeature.PLAN_ACTION, List.of(
                "先给方案", "给出方案", "实施方案", "迁移方案", "优化方案", "实施步骤", "先规划",
                "plan first", "proposal", "roadmap", "implementation plan", "migration plan"
        ));
        vocabulary.put(IntentLexicalFeature.READ_ONLY_CONSTRAINT, List.of(
                "不要修改", "不要改", "不要实现", "无需修改", "无需实现", "不改代码", "只分析", "只解释",
                "只审计", "只给方案", "仅分析", "仅解释", "仅审计", "仅给方案",
                "do not modify", "do not change", "do not implement", "without changes", "read only", "read-only"
        ));
        vocabulary.put(IntentLexicalFeature.EDIT_ACTION, List.of(
                "修改", "调整", "更改", "替换", "改成", "换成", "新增", "增加", "实现", "重构",
                "删除", "升级", "迁移", "modify", "change", "replace", "add", "implement", "refactor",
                "delete", "remove", "upgrade", "migrate"
        ));
        vocabulary.put(IntentLexicalFeature.LIGHT_EDIT, List.of(
                "文案", "标题", "文字", "颜色", "字号", "字体", "间距", "边距", "圆角", "按钮",
                "图标", "样式", "布局微调", "copy", "title", "text", "color", "font", "spacing",
                "margin", "padding", "border radius", "button", "icon", "style"
        ));
        vocabulary.put(IntentLexicalFeature.FRONTEND, List.of(
                "前端", "前后端", "页面", "首页", "组件", "界面", "布局", "样式", "按钮", "表单", "弹窗",
                "vue", "react", "css", "html", "frontend", "page", "component", "ui", "ux"
        ));
        vocabulary.put(IntentLexicalFeature.BACKEND, List.of(
                "后端", "前后端", "服务端", "controller", "service", "repository", "spring", "java", "backend", "server"
        ));
        vocabulary.put(IntentLexicalFeature.API, List.of(
                "接口", "api", "endpoint", "rest", "graphql", "websocket", "契约"
        ));
        vocabulary.put(IntentLexicalFeature.DATABASE, List.of(
                "数据库", "数据表", "表结构", "字段", "sql", "mysql", "postgres", "redis", "mongodb",
                "database", "schema", "migration", "orm", "mybatis", "jpa"
        ));
        vocabulary.put(IntentLexicalFeature.AUTHENTICATION, List.of(
                "登录", "注册", "鉴权", "认证", "授权", "权限", "角色", "jwt", "oauth", "sso",
                "login", "register", "authentication", "authorization", "permission", "role"
        ));
        vocabulary.put(IntentLexicalFeature.BUILD_CONFIGURATION, List.of(
                "构建", "编译", "依赖", "打包", "部署配置", "package.json", "pom.xml", "gradle", "maven",
                "npm", "pnpm", "yarn", "vite", "webpack", "build", "compile", "dependency"
        ));
        vocabulary.put(IntentLexicalFeature.INFRASTRUCTURE, List.of(
                "微服务", "分布式", "kubernetes", "k8s", "docker", "容器", "网关", "消息队列", "高并发",
                "多租户", "限流", "熔断", "链路追踪", "microservice", "distributed", "infrastructure"
        ));
        vocabulary.put(IntentLexicalFeature.TESTING, List.of(
                "测试", "单测", "集成测试", "回归", "验收", "test", "testing", "junit", "playwright", "cypress"
        ));
        vocabulary.put(IntentLexicalFeature.DOCUMENTATION, List.of(
                "文档", "说明书", "readme", "注释", "documentation", "docs", "comment"
        ));
        vocabulary.put(IntentLexicalFeature.FULL_STACK_PROJECT, List.of(
                "全栈", "前后端", "前端和后端", "前端加后端", "前端调用后端", "接口联调",
                "前后端联调", "全栈开发", "fullstack", "full stack", "full-stack"
        ));
        vocabulary.put(IntentLexicalFeature.ENGINEERED_FRONTEND_PROJECT, List.of(
                "vue", "vue3", "vue 3", "react", "angular", "svelte", "前端工程", "工程化前端",
                "单页应用", "single page application"
        ));
        vocabulary.put(IntentLexicalFeature.MULTI_FILE_PROJECT, List.of(
                "html/css/js", "html/css/javascript", "html、css、js", "html、css、javascript",
                "html css js", "html css javascript", "三件套", "多文件静态", "静态站点三文件",
                "css和js分离", "css与js分离"
        ));
        vocabulary.put(IntentLexicalFeature.SINGLE_HTML_PROJECT, List.of(
                "单个html", "单文件html", "一个html文件", "html单文件", "纯html",
                "内联css", "内联javascript", "内联js"
        ));
        vocabulary.put(IntentLexicalFeature.MULTI_FILE, List.of(
                "跨文件", "多个文件", "多文件", "前后端", "全栈", "整个项目", "全项目", "所有文件",
                "cross-file", "multiple files", "full stack", "entire project", "whole project"
        ));
        vocabulary.put(IntentLexicalFeature.SINGLE_FILE, List.of(
                "单文件", "一个文件", "当前文件", "这个文件", "single file", "one file", "this file"
        ));
        vocabulary.put(IntentLexicalFeature.HIGH_COMPLEXITY, List.of(
                "完整重构", "彻底重构", "全部重写", "从头重写", "推倒重来", "更换技术栈", "换框架",
                "支付系统", "复杂工作流", "架构改造", "领域驱动", "高并发", "多租户", "微服务", "分布式",
                "rewrite everything", "from scratch", "change framework", "payment system", "complex workflow",
                "architecture", "multi-tenant", "microservice", "distributed"
        ));
        vocabulary.put(IntentLexicalFeature.HIGH_DESTRUCTIVE_RISK, List.of(
                "全部删除", "删除全部", "删除所有", "所有删除", "清空", "全部重写", "从头重写", "推倒重来",
                "替换整个", "更换技术栈", "换框架", "drop table", "drop database", "delete all", "remove all",
                "rewrite everything", "replace entire", "change framework"
        ));
        vocabulary.put(IntentLexicalFeature.MEDIUM_DESTRUCTIVE_RISK, List.of(
                "重构", "迁移", "升级", "删除", "移除", "表结构", "字段变更", "数据库迁移",
                "refactor", "migrate", "upgrade", "delete", "remove", "schema change"
        ));
        return vocabulary;
    }
}
