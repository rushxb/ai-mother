package com.rush.rushaicodemother.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionSourceHygieneTest {

    private static final List<String> FORBIDDEN_CONSOLE_PATTERNS = List.of(
            "System.out.",
            "System.err.",
            ".printStackTrace("
    );
    private static final Set<String> THROWABLE_ARGUMENT_NAMES = Set.of(
            "e", "ex", "exception", "error", "failure", "throwable",
            "lastError", "rebuildFailure", "cleanupFailure"
    );
    private static final Pattern DIRECT_THROWABLE_ARGUMENT = Pattern.compile(
            ",\\s*(" + String.join("|", THROWABLE_ARGUMENT_NAMES) + ")\\s*\\)\\s*$",
            Pattern.DOTALL
    );
    private static final List<Pattern> DAMAGED_JAVADOC_PATTERNS = List.of(
            Pattern.compile("\\{@code (?:ate|ed|are)}"),
            Pattern.compile("(?:返回|处理|校验)\\{@code"),
            Pattern.compile("创建.*实例并完成必要的依赖和初始状态设置"),
            Pattern.compile("方法执行结果"),
            Pattern.compile("获取并返回"),
            Pattern.compile("发布当前处理结果或领域事件")
    );
    private static final List<Pattern> LOW_VALUE_TEMPLATE_COMMENT_PATTERNS = List.of(
            Pattern.compile("先处理前置条件和快速返回分支"),
            Pattern.compile("将可能失败的操作收敛在统一异常边界内")
    );

    @Test
    void productionSourcesMustNotWriteDirectlyToProcessConsole() throws IOException {
        Path projectBaseDir = Path.of(System.getProperty("projectBaseDir")).toAbsolutePath().normalize();
        Path mainSourceRoot = projectBaseDir.resolve("src/main/java");
        List<String> violations = new ArrayList<>();

        try (var sourceFiles = Files.walk(mainSourceRoot)) {
            sourceFiles
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> inspectSourceFile(mainSourceRoot, path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "生产源码不得直接写入 stdout/stderr 或调用 printStackTrace；请使用结构化日志：\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void productionLogsMustSanitizeExceptionDiagnostics() throws IOException {
        Path projectBaseDir = Path.of(System.getProperty("projectBaseDir")).toAbsolutePath().normalize();
        Path mainSourceRoot = projectBaseDir.resolve("src/main/java");
        List<String> violations = new ArrayList<>();

        try (var sourceFiles = Files.walk(mainSourceRoot)) {
            sourceFiles
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> inspectLoggerCalls(mainSourceRoot, path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "生产日志不得直接记录 Throwable 或 Throwable#getMessage()；"
                        + "请使用 LogExceptionSanitizer：\n" + String.join("\n", violations)
        );
    }

    @Test
    void generationBenchmarkSourcesMustNotContainDamagedTemplateComments() throws IOException {
        Path projectBaseDir = Path.of(System.getProperty("projectBaseDir")).toAbsolutePath().normalize();
        Path benchmarkSourceRoot = projectBaseDir.resolve(
                "src/main/java/com/rush/rushaicodemother/orchestration/benchmark");
        List<String> violations = new ArrayList<>();

        try (var sourceFiles = Files.walk(benchmarkSourceRoot)) {
            sourceFiles
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .forEach(path -> inspectDamagedComments(benchmarkSourceRoot, path, violations));
        }

        assertTrue(
                violations.isEmpty(),
                () -> "生成评测源码不得包含损坏或仅复述方法名的模板注释：\n"
                        + String.join("\n", violations)
        );
    }

    @Test
    void generatedWorkspaceExecutionCoreMustNotContainLowValueTemplateComments()
            throws IOException {
        Path projectBaseDir = Path.of(System.getProperty("projectBaseDir"))
                .toAbsolutePath()
                .normalize();
        Path mainSourceRoot = projectBaseDir.resolve("src/main/java");
        List<Path> protectedRoots = List.of(
                mainSourceRoot.resolve("com/rush/rushaicodemother/security/workspace"),
                mainSourceRoot.resolve("com/rush/rushaicodemother/infrastructure/sandbox"),
                mainSourceRoot.resolve("com/rush/rushaicodemother/service/dependency")
        );
        List<Pattern> forbiddenPatterns = new ArrayList<>(DAMAGED_JAVADOC_PATTERNS);
        forbiddenPatterns.addAll(LOW_VALUE_TEMPLATE_COMMENT_PATTERNS);
        List<String> violations = new ArrayList<>();

        for (Path protectedRoot : protectedRoots) {
            try (var sourceFiles = Files.walk(protectedRoot)) {
                sourceFiles
                        .filter(path -> path.getFileName().toString().endsWith(".java"))
                        .forEach(path -> inspectDamagedComments(
                                mainSourceRoot,
                                path,
                                forbiddenPatterns,
                                violations));
            }
        }

        assertTrue(
                violations.isEmpty(),
                () -> "生成工作区执行核心的注释必须说明约束、失败语义或设计原因：\n"
                        + String.join("\n", violations)
        );
    }

    private void inspectSourceFile(Path sourceRoot, Path sourceFile, List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (FORBIDDEN_CONSOLE_PATTERNS.stream().anyMatch(line::contains)) {
                    violations.add(sourceRoot.relativize(sourceFile) + ":" + (index + 1));
                }
            }
        } catch (IOException exception) {
            throw new SourceInspectionException("无法读取生产源码：" + sourceFile, exception);
        }
    }

    private void inspectLoggerCalls(Path sourceRoot, Path sourceFile, List<String> violations) {
        try {
            String source = Files.readString(sourceFile, StandardCharsets.UTF_8);
            int searchFrom = 0;
            while (searchFrom < source.length()) {
                int logCallStart = source.indexOf("log.", searchFrom);
                if (logCallStart < 0) {
                    return;
                }
                int openingParenthesis = findLoggerOpeningParenthesis(source, logCallStart);
                if (openingParenthesis < 0) {
                    searchFrom = logCallStart + 4;
                    continue;
                }
                int closingParenthesis = findMatchingParenthesis(source, openingParenthesis);
                if (closingParenthesis < 0) {
                    violations.add(location(sourceRoot, sourceFile, source, logCallStart)
                            + " malformed logger invocation");
                    return;
                }
                String loggerCall = source.substring(logCallStart, closingParenthesis + 1);
                if (isUnsafeLoggerCall(loggerCall)) {
                    violations.add(location(sourceRoot, sourceFile, source, logCallStart));
                }
                searchFrom = closingParenthesis + 1;
            }
        } catch (IOException exception) {
            throw new SourceInspectionException("无法读取生产源码：" + sourceFile, exception);
        }
    }

    private void inspectDamagedComments(Path sourceRoot, Path sourceFile, List<String> violations) {
        inspectDamagedComments(sourceRoot, sourceFile, DAMAGED_JAVADOC_PATTERNS, violations);
    }

    private void inspectDamagedComments(Path sourceRoot,
                                        Path sourceFile,
                                        List<Pattern> forbiddenPatterns,
                                        List<String> violations) {
        try {
            List<String> lines = Files.readAllLines(sourceFile, StandardCharsets.UTF_8);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index);
                if (forbiddenPatterns.stream().anyMatch(pattern -> pattern.matcher(line).find())) {
                    violations.add(sourceRoot.relativize(sourceFile) + ":" + (index + 1));
                }
            }
        } catch (IOException exception) {
            throw new SourceInspectionException("无法读取生成评测源码：" + sourceFile, exception);
        }
    }

    private int findLoggerOpeningParenthesis(String source, int logCallStart) {
        int methodEnd = source.indexOf('(', logCallStart + 4);
        if (methodEnd < 0) {
            return -1;
        }
        String methodName = source.substring(logCallStart + 4, methodEnd).trim();
        return Set.of("trace", "debug", "info", "warn", "error").contains(methodName)
                ? methodEnd
                : -1;
    }

    private int findMatchingParenthesis(String source, int openingParenthesis) {
        int depth = 1;
        ParseState state = ParseState.CODE;
        for (int index = openingParenthesis + 1; index < source.length(); index++) {
            char current = source.charAt(index);
            char next = index + 1 < source.length() ? source.charAt(index + 1) : '\0';
            switch (state) {
                case CODE -> {
                    if (current == '"') {
                        state = ParseState.STRING;
                    } else if (current == '\'') {
                        state = ParseState.CHARACTER;
                    } else if (current == '/' && next == '*') {
                        state = ParseState.BLOCK_COMMENT;
                        index++;
                    } else if (current == '/' && next == '/') {
                        state = ParseState.LINE_COMMENT;
                        index++;
                    } else if (current == '(') {
                        depth++;
                    } else if (current == ')' && --depth == 0) {
                        return index;
                    }
                }
                case STRING -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '"') {
                        state = ParseState.CODE;
                    }
                }
                case CHARACTER -> {
                    if (current == '\\') {
                        index++;
                    } else if (current == '\'') {
                        state = ParseState.CODE;
                    }
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = ParseState.CODE;
                        index++;
                    }
                }
                case LINE_COMMENT -> {
                    if (current == '\n') {
                        state = ParseState.CODE;
                    }
                }
            }
        }
        return -1;
    }

    private boolean isUnsafeLoggerCall(String loggerCall) {
        if (loggerCall.contains(".getMessage(") || loggerCall.contains(".getLocalizedMessage(")) {
            return true;
        }
        return !loggerCall.contains("LogExceptionSanitizer.")
                && DIRECT_THROWABLE_ARGUMENT.matcher(loggerCall).find();
    }

    private String location(Path sourceRoot, Path sourceFile, String source, int offset) {
        long line = source.substring(0, offset).chars().filter(character -> character == '\n').count() + 1;
        return sourceRoot.relativize(sourceFile) + ":" + line;
    }

    private enum ParseState {
        CODE, STRING, CHARACTER, BLOCK_COMMENT, LINE_COMMENT
    }

    private static final class SourceInspectionException extends RuntimeException {

        private SourceInspectionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
