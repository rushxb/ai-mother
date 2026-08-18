package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止工程类型显式分支扩散到新的生产文件。 */
class CodeGenerationTypeBranchBudgetArchitectureTest {

    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Pattern SWITCH_PATTERN = Pattern.compile("\\bswitch\\s*\\(");
    private static final Pattern TYPE_VARIABLE_PATTERN = Pattern.compile(
            "\\bCodeGenTypeEnum\\s+([A-Za-z_$][A-Za-z0-9_$]*)"
    );
    private static final Pattern TYPE_ACCESSOR_PATTERN = Pattern.compile(
            "\\b(?:codeGenType|targetType|currentType|getCodeGenType|getTargetType)\\s*\\("
    );
    private static final int MAX_FILES_WITH_TYPE_SWITCH = 4;

    /**
     * 当前集合是待继续压缩的债务上限，不是推荐结构。
     * 每迁移一个注册式 adapter 都应删除对应文件，任何新文件不得加入该集合。
     */
    private static final Set<String> LEGACY_TYPE_SWITCH_FILES = Set.of(
            "com/rush/rushaicodemother/ai/model/GenerationPerformanceProfile.java",
            "com/rush/rushaicodemother/orchestration/runtime/agent/GenerationAgentTurnPolicy.java",
            "com/rush/rushaicodemother/orchestration/workspace/GenerationWorkspaceService.java",
            "com/rush/rushaicodemother/service/credit/GenerationCreditReservationPolicy.java"
    );

    @Test
    void codeGenerationTypeSwitchesMustNotSpreadToNewProductionFiles() throws IOException {
        Set<String> actualFiles = findFilesWithTypeSwitch();
        Set<String> unexpectedFiles = new TreeSet<>(actualFiles);
        unexpectedFiles.removeAll(LEGACY_TYPE_SWITCH_FILES);

        assertTrue(
                unexpectedFiles.isEmpty(),
                () -> "工程类型显式 switch 已扩散到新文件，应改为注册式 adapter: " + unexpectedFiles
        );
        assertTrue(
                actualFiles.size() <= MAX_FILES_WITH_TYPE_SWITCH,
                () -> "工程类型显式 switch 文件数不得超过 " + MAX_FILES_WITH_TYPE_SWITCH
                        + "，实际为 " + actualFiles.size()
        );
    }

    @Test
    void detectorMustIgnoreUnrelatedSwitchInsideRegisteredAdapter() {
        String source = """
                import com.example.CodeGenTypeEnum;
                class RegisteredAdapter {
                    private final Set<CodeGenTypeEnum> supportedTypes = Set.of();
                    String handle(Event event) {
                        return switch (event.type()) {
                            default -> "ok";
                        };
                    }
                }
                """;

        assertFalse(containsCodeGenerationTypeSwitch(source));
    }

    @Test
    void detectorMustRecognizeTypedVariablesAndWorkspaceAccessors() {
        String typedVariableSwitch = """
                CodeGenTypeEnum targetType = request.targetType();
                return switch (targetType) { default -> true; };
                """;
        String accessorSwitch = """
                return switch (workspace.codeGenType()) { default -> true; };
                """;
        String explicitCastSwitch = """
                return switch ((CodeGenTypeEnum) rawType) { default -> true; };
                """;

        assertTrue(containsCodeGenerationTypeSwitch(typedVariableSwitch));
        assertTrue(containsCodeGenerationTypeSwitch(accessorSwitch));
        assertTrue(containsCodeGenerationTypeSwitch(explicitCastSwitch));
    }

    private Set<String> findFilesWithTypeSwitch() throws IOException {
        Set<String> matches = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path);
                if (containsCodeGenerationTypeSwitch(source)) {
                    matches.add(PRODUCTION_SOURCE_ROOT.relativize(path)
                            .toString()
                            .replace('\\', '/'));
                }
            }
        }
        return matches;
    }

    /** 仅识别真正引用工程类型变量或访问器的 switch，避免把同文件的其他状态机误报为债务。 */
    private static boolean containsCodeGenerationTypeSwitch(String source) {
        if (source == null) {
            return false;
        }
        Set<String> typeVariables = new HashSet<>();
        var variableMatcher = TYPE_VARIABLE_PATTERN.matcher(source);
        while (variableMatcher.find()) {
            typeVariables.add(variableMatcher.group(1));
        }
        for (String expression : extractSwitchExpressions(source)) {
            if (expression.contains("CodeGenTypeEnum")
                    || TYPE_ACCESSOR_PATTERN.matcher(expression).find()) {
                return true;
            }
            for (String typeVariable : typeVariables) {
                if (Pattern.compile("\\b" + Pattern.quote(typeVariable) + "\\b")
                        .matcher(expression)
                        .find()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 提取 switch 圆括号内的表达式，并正确跳过字符串中的括号。 */
    private static List<String> extractSwitchExpressions(String source) {
        List<String> expressions = new ArrayList<>();
        var switchMatcher = SWITCH_PATTERN.matcher(source);
        int searchFrom = 0;
        while (switchMatcher.find(searchFrom)) {
            int expressionStart = switchMatcher.end();
            int depth = 1;
            boolean inSingleQuote = false;
            boolean inDoubleQuote = false;
            boolean escaped = false;
            int index = expressionStart;
            for (; index < source.length() && depth > 0; index++) {
                char current = source.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if ((inSingleQuote || inDoubleQuote) && current == '\\') {
                    escaped = true;
                    continue;
                }
                if (!inDoubleQuote && current == '\'') {
                    inSingleQuote = !inSingleQuote;
                    continue;
                }
                if (!inSingleQuote && current == '"') {
                    inDoubleQuote = !inDoubleQuote;
                    continue;
                }
                if (inSingleQuote || inDoubleQuote) {
                    continue;
                }
                if (current == '(') {
                    depth++;
                } else if (current == ')') {
                    depth--;
                }
            }
            if (depth == 0) {
                expressions.add(source.substring(expressionStart, index - 1));
            }
            searchFrom = Math.max(index, switchMatcher.end());
        }
        return expressions;
    }
}
