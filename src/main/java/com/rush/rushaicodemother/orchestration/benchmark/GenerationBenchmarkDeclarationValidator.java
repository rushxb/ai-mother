package com.rush.rushaicodemother.orchestration.benchmark;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** 对声明式夹具和断言设置固定资源上限及工作区路径边界。 */
public final class GenerationBenchmarkDeclarationValidator {

    public static final int MAX_FIXTURE_FILES = 12;
    public static final int MAX_FIXTURE_FILE_CHARS = 50_000;
    public static final int MAX_FIXTURE_TOTAL_CHARS = 200_000;
    public static final int MAX_ASSERTIONS = 16;
    public static final int MAX_RESPONSE_ASSERTIONS = 8;
    public static final int MAX_ASSERTION_PATHS = 8;
    public static final int MAX_ASSERTION_TOKENS = 16;
    public static final int MAX_ASSERTION_TOKEN_CHARS = 256;
    public static final int MAX_SOURCE_FILES = 24;
    public static final int MAX_SOURCE_FILE_CHARS = 100_000;
    public static final int MAX_SOURCE_TOTAL_CHARS = 500_000;

    private static final int MAX_PATH_CHARS = 256;
    private static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "vue", "ts", "tsx", "js", "jsx", "css", "scss", "json", "go", "html", "md", "sql", "env"
    );
    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of(
            ".git", ".idea", ".vscode", "node_modules", "dist", "target", "vendor"
    );

    private GenerationBenchmarkDeclarationValidator() {
    }

    public static void validate(GenerationBenchmarkTask task) {
        if (task == null) {
            throw new IllegalArgumentException("评测任务不能为空");
        }
        validateFixtures(task.fixtureFiles());
        validateAssertions(task.sourceAssertions());
        validateResponseAssertions(task.responseAssertions());
        if (!task.fixtureFiles().isEmpty() && task.sourceAssertions().isEmpty()) {
            throw new IllegalArgumentException("声明式源码夹具必须配置对应断言");
        }
    }

    private static void validateFixtures(List<GenerationBenchmarkFixtureFile> fixtures) {
        if (fixtures.size() > MAX_FIXTURE_FILES) {
            throw new IllegalArgumentException("单个评测任务的源码夹具文件过多");
        }
        int totalChars = 0;
        Set<String> identities = new HashSet<>();
        for (GenerationBenchmarkFixtureFile fixture : fixtures) {
            if (fixture == null || fixture.root() == null) {
                throw new IllegalArgumentException("源码夹具根目录不能为空");
            }
            validatePath(fixture.path());
            String identity = fixture.root() + ":" + fixture.path();
            if (!identities.add(identity)) {
                throw new IllegalArgumentException("源码夹具路径不能重复");
            }
            int chars = fixture.content() == null ? 0 : fixture.content().length();
            if (chars > MAX_FIXTURE_FILE_CHARS) {
                throw new IllegalArgumentException("单个源码夹具超过字符上限");
            }
            totalChars = Math.addExact(totalChars, chars);
            if (totalChars > MAX_FIXTURE_TOTAL_CHARS) {
                throw new IllegalArgumentException("源码夹具总字符数超过上限");
            }
        }
    }

    private static void validateAssertions(List<GenerationBenchmarkSourceAssertion> assertions) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (assertions.size() > MAX_ASSERTIONS) {
            throw new IllegalArgumentException("单个评测任务的源码断言过多");
        }
        Set<String> ids = new HashSet<>();
        Set<String> sourceFiles = new HashSet<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (GenerationBenchmarkSourceAssertion assertion : assertions) {
            if (assertion == null || assertion.root() == null
                    || assertion.id() == null || !ID_PATTERN.matcher(assertion.id()).matches()) {
                throw new IllegalArgumentException("源码断言标识或根目录无效");
            }
            if (!ids.add(assertion.id())) {
                throw new IllegalArgumentException("源码断言标识不能重复");
            }
            if (assertion.paths().isEmpty() || assertion.paths().size() > MAX_ASSERTION_PATHS) {
                throw new IllegalArgumentException("源码断言路径数量无效");
            }
            for (String path : assertion.paths()) {
                validatePath(path);
                sourceFiles.add(assertion.root() + ":" + path);
            }
            if (sourceFiles.size() > MAX_SOURCE_FILES) {
                throw new IllegalArgumentException("源码断言读取的文件数量超过上限");
            }
            if (assertion.allOf().isEmpty() && assertion.anyOf().isEmpty() && assertion.noneOf().isEmpty()) {
                throw new IllegalArgumentException("源码断言至少需要一个匹配条件");
            }
            validateTokens(assertion.allOf());
            validateTokens(assertion.anyOf());
            validateTokens(assertion.noneOf());
        }
    }

    private static void validateResponseAssertions(
            List<GenerationBenchmarkResponseAssertion> assertions
    ) {
        if (assertions.size() > MAX_RESPONSE_ASSERTIONS) {
            throw new IllegalArgumentException("单个评测任务的响应断言过多");
        }
        Set<String> ids = new HashSet<>();
        for (GenerationBenchmarkResponseAssertion assertion : assertions) {
            if (assertion == null || assertion.id() == null
                    || !ID_PATTERN.matcher(assertion.id()).matches()) {
                throw new IllegalArgumentException("响应断言标识无效");
            }
            if (!ids.add(assertion.id())) {
                throw new IllegalArgumentException("响应断言标识不能重复");
            }
            if (assertion.allOf().isEmpty()
                    && assertion.anyOf().isEmpty()
                    && assertion.noneOf().isEmpty()) {
                throw new IllegalArgumentException("响应断言至少需要一个匹配条件");
            }
            validateTokens(assertion.allOf());
            validateTokens(assertion.anyOf());
            validateTokens(assertion.noneOf());
        }
    }

    private static void validateTokens(List<String> tokens) {
        if (tokens.size() > MAX_ASSERTION_TOKENS) {
            throw new IllegalArgumentException("评测断言匹配项过多");
        }
        for (String token : tokens) {
            if (token == null || token.isBlank() || token.length() > MAX_ASSERTION_TOKEN_CHARS
                    || token.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("评测断言匹配项无效");
            }
        }
    }

    private static void validatePath(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_PATH_CHARS
                || value.indexOf('\0') >= 0 || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("评测源码路径无效");
        }
        Path path;
        try {
            path = Path.of(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("评测源码路径无效", invalid);
        }
        if (path.isAbsolute() || path.normalize().startsWith("..") || !path.equals(path.normalize())) {
            throw new IllegalArgumentException("评测源码路径必须位于工作区内");
        }
        for (Path segment : path) {
            if (FORBIDDEN_SEGMENTS.contains(segment.toString().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("评测源码路径包含受保护目录");
            }
        }
        String fileName = path.getFileName().toString();
        int separator = fileName.lastIndexOf('.');
        String extension = separator < 0
                ? ""
                : fileName.substring(separator + 1).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("评测源码文件类型不受支持");
        }
    }
}
