package com.rush.rushaicodemother.infrastructure.sandbox;

import com.rush.rushaicodemother.infrastructure.process.ManagedProcessRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 生成代码子进程的显式环境变量信任策略。
 *
 * <p>宿主环境清理只能阻止进程继承平台密钥，无法阻止调用方把密钥放入
 * {@link ManagedProcessRequest#environment()}。本策略位于容器授权边界，采用“命令族 + 变量名 +
 * 值约束”三层允许列表；未知变量一律拒绝，避免模型、数据库或租户凭据通过未来调用点进入生成代码。</p>
 *
 * <p>新增工具链变量时只需在本策略中增加一条可审计规则，容器编排逻辑无需感知具体变量。</p>
 */
@Component
public final class GeneratedCodeProcessEnvironmentPolicy {

    private static final int MAX_VALUE_LENGTH = 4_096;
    private static final Pattern VALID_ENVIRONMENT_NAME = Pattern.compile("[A-Z_][A-Z0-9_]*");
    private static final Pattern BENCHMARK_DATABASE_DSN = Pattern.compile(
            "file:benchmark-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
                    + "\\?mode=memory&cache=shared"
    );
    private static final Map<String, EnvironmentRule> RULES = createRules();

    /**
     * 校验并冻结即将注入容器的显式环境变量。
     *
     * @param request 受控进程请求
     * @param workingDirectory 已规范化的工作区根目录
     * @param runtimePort 运行时容器暴露端口，非运行时命令为 {@code null}
     * @return 按变量名稳定排序的可信环境变量
     */
    public Map<String, String> validate(
            ManagedProcessRequest request,
            Path workingDirectory,
            Integer runtimePort
    ) {
        if (request == null) {
            throw new IllegalArgumentException("受控进程请求不能为空");
        }
        if (workingDirectory == null) {
            throw new IllegalArgumentException("生成代码工作区不能为空");
        }
        if (request.environment().isEmpty()) {
            return Map.of();
        }

        EnvironmentContext context = new EnvironmentContext(
                commandFamily(request.command()),
                workingDirectory.toAbsolutePath().normalize(),
                request.networkPolicy(),
                runtimePort
        );
        Map<String, String> validated = new TreeMap<>();
        request.environment().forEach((name, value) -> {
            validateName(name);
            EnvironmentRule rule = RULES.get(name);
            if (rule == null || rule.commandFamily() != context.commandFamily()) {
                throw new IllegalArgumentException("生成代码子进程不允许注入环境变量: " + name);
            }
            validatePlainValue(name, value);
            rule.constraint().validate(name, value, context);
            validated.put(name, value);
        });
        return Collections.unmodifiableMap(new LinkedHashMap<>(validated));
    }

    private static Map<String, EnvironmentRule> createRules() {
        Map<String, EnvironmentRule> rules = new LinkedHashMap<>();

        registerExact(rules, CommandFamily.NODE, "NO_UPDATE_NOTIFIER", "1");
        registerExact(rules, CommandFamily.NODE, "NPM_CONFIG_AUDIT", "false");
        registerExact(rules, CommandFamily.NODE, "NPM_CONFIG_FUND", "false");
        registerExact(rules, CommandFamily.NODE, "CI", "true");
        register(rules, CommandFamily.NODE, "VITE_API_BASE_URL",
                GeneratedCodeProcessEnvironmentPolicy::validateLoopbackApiBase);

        registerExact(rules, CommandFamily.GO, "GOENV", "off");
        registerExact(rules, CommandFamily.GO, "GOFLAGS", "");
        registerExact(rules, CommandFamily.GO, "GOPROXY", "off");
        registerExact(rules, CommandFamily.GO, "GOSUMDB", "off");
        registerExact(rules, CommandFamily.GO, "GOTOOLCHAIN", "local");
        registerExact(rules, CommandFamily.GO, "GOTELEMETRY", "off");
        registerExact(rules, CommandFamily.GO, "GOWORK", "off");
        registerExact(rules, CommandFamily.GO, "CGO_ENABLED", "0");
        register(rules, CommandFamily.GO, "SERVER_ADDR",
                GeneratedCodeProcessEnvironmentPolicy::validateServerAddress);
        register(rules, CommandFamily.GO, "DATABASE_DSN",
                GeneratedCodeProcessEnvironmentPolicy::validateBenchmarkDatabaseDsn);
        registerExactRuntime(rules, CommandFamily.GO, "LOG_LEVEL", "warn");

        registerExact(rules, CommandFamily.GIT, "GIT_AUTHOR_NAME", "ai-code-mother");
        registerExact(rules, CommandFamily.GIT, "GIT_AUTHOR_EMAIL", "ai-code-mother@example.com");
        registerExact(rules, CommandFamily.GIT, "GIT_COMMITTER_NAME", "ai-code-mother");
        registerExact(rules, CommandFamily.GIT, "GIT_COMMITTER_EMAIL", "ai-code-mother@example.com");
        registerExact(rules, CommandFamily.GIT, "GIT_TERMINAL_PROMPT", "0");
        registerExact(rules, CommandFamily.GIT, "GCM_INTERACTIVE", "never");
        registerExact(rules, CommandFamily.GIT, "GIT_CONFIG_NOSYSTEM", "1");
        register(rules, CommandFamily.GIT, "GIT_CONFIG_GLOBAL",
                GeneratedCodeProcessEnvironmentPolicy::validateWorkspacePath);
        register(rules, CommandFamily.GIT, "XDG_CONFIG_HOME",
                GeneratedCodeProcessEnvironmentPolicy::validateWorkspacePath);
        register(rules, CommandFamily.GIT, "GIT_INDEX_FILE",
                GeneratedCodeProcessEnvironmentPolicy::validateWorkspacePath);

        return Map.copyOf(rules);
    }

    private static void registerExact(
            Map<String, EnvironmentRule> rules,
            CommandFamily commandFamily,
            String name,
            String expectedValue
    ) {
        register(rules, commandFamily, name, (actualName, value, context) -> {
            if (!expectedValue.equals(value)) {
                throw invalidValue(actualName);
            }
        });
    }

    private static void registerExactRuntime(
            Map<String, EnvironmentRule> rules,
            CommandFamily commandFamily,
            String name,
            String expectedValue
    ) {
        register(rules, commandFamily, name, (actualName, value, context) -> {
            requireRuntime(actualName, context);
            if (!expectedValue.equals(value)) {
                throw invalidValue(actualName);
            }
        });
    }

    private static void register(
            Map<String, EnvironmentRule> rules,
            CommandFamily commandFamily,
            String name,
            EnvironmentValueConstraint constraint
    ) {
        EnvironmentRule previous = rules.put(
                name,
                new EnvironmentRule(commandFamily, constraint)
        );
        if (previous != null) {
            throw new IllegalStateException("重复的沙箱环境变量规则: " + name);
        }
    }

    private static void validateName(String name) {
        if (name == null || !VALID_ENVIRONMENT_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("生成代码子进程环境变量名不合法");
        }
    }

    private static void validatePlainValue(String name, String value) {
        if (value == null
                || value.length() > MAX_VALUE_LENGTH
                || value.chars().anyMatch(Character::isISOControl)) {
            throw invalidValue(name);
        }
    }

    private static void validateLoopbackApiBase(
            String name,
            String value,
            EnvironmentContext context
    ) {
        requireRuntime(name, context);
        try {
            URI uri = URI.create(value);
            boolean valid = "http".equalsIgnoreCase(uri.getScheme())
                    && "127.0.0.1".equals(uri.getHost())
                    && uri.getPort() >= 1
                    && uri.getPort() <= 65_535
                    && "/api".equals(uri.getPath())
                    && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null;
            if (valid) {
                return;
            }
        } catch (RuntimeException exception) {
            // URI 解析失败统一转换为安全策略异常，避免向上泄漏实现细节。
        }
        throw invalidValue(name);
    }

    private static void validateServerAddress(
            String name,
            String value,
            EnvironmentContext context
    ) {
        requireRuntime(name, context);
        String expected = "127.0.0.1:" + context.runtimePort();
        if (!expected.equals(value)) {
            throw invalidValue(name);
        }
    }

    private static void validateBenchmarkDatabaseDsn(
            String name,
            String value,
            EnvironmentContext context
    ) {
        requireRuntime(name, context);
        if (!BENCHMARK_DATABASE_DSN.matcher(value).matches()) {
            throw invalidValue(name);
        }
    }

    private static void validateWorkspacePath(
            String name,
            String value,
            EnvironmentContext context
    ) {
        try {
            Path candidate = Path.of(value);
            if (candidate.isAbsolute()
                    && candidate.normalize().startsWith(context.workingDirectory())) {
                return;
            }
        } catch (RuntimeException exception) {
            // 路径解析失败统一转换为安全策略异常，避免向上泄漏宿主路径细节。
        }
        throw invalidValue(name);
    }

    private static void requireRuntime(String name, EnvironmentContext context) {
        if (context.networkPolicy() != SandboxNetworkPolicy.RUNTIME_INTERNAL
                || context.runtimePort() == null) {
            throw new IllegalArgumentException("环境变量仅允许用于受控运行时: " + name);
        }
    }

    private static IllegalArgumentException invalidValue(String name) {
        return new IllegalArgumentException("生成代码子进程环境变量值不受信任: " + name);
    }

    private static CommandFamily commandFamily(List<String> command) {
        if (command == null || command.isEmpty() || command.getFirst() == null) {
            return CommandFamily.UNKNOWN;
        }
        String executable = command.getFirst();
        int lastSeparator = Math.max(executable.lastIndexOf('/'), executable.lastIndexOf('\\'));
        String fileName = lastSeparator < 0 ? executable : executable.substring(lastSeparator + 1);
        String normalized = fileName.toLowerCase(Locale.ROOT);
        if (normalized.endsWith(".cmd") || normalized.endsWith(".exe")) {
            normalized = normalized.substring(0, normalized.length() - 4);
        }
        return switch (normalized) {
            case "node", "pnpm", "npm", "npx", "corepack" -> CommandFamily.NODE;
            case "go" -> CommandFamily.GO;
            case "git" -> CommandFamily.GIT;
            default -> CommandFamily.UNKNOWN;
        };
    }

    private enum CommandFamily {
        NODE,
        GO,
        GIT,
        UNKNOWN
    }

    private record EnvironmentContext(
            CommandFamily commandFamily,
            Path workingDirectory,
            SandboxNetworkPolicy networkPolicy,
            Integer runtimePort
    ) {
    }

    private record EnvironmentRule(
            CommandFamily commandFamily,
            EnvironmentValueConstraint constraint
    ) {
    }

    @FunctionalInterface
    private interface EnvironmentValueConstraint {

        void validate(String name, String value, EnvironmentContext context);
    }
}
