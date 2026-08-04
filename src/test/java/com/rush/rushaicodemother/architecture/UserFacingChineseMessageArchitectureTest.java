package com.rush.rushaicodemother.architecture;

import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.UserFacingMessageResolver;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserFacingChineseMessageArchitectureTest {

    private static final Pattern CONTROLLER_EXCEPTION_INVOCATION = Pattern.compile(
            "(?:new\\s+BusinessException|ThrowUtils\\.throwIf)\\s*\\((?s:.*?)\\)\\s*;");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");
    private static final Pattern SAFE_RESPONSE_BOUNDARY = Pattern.compile(
            "userFacingMessageResolver\\.resolve\\(\\s*errorMessage\\s*,\\s*"
                    + "defaultMessageFor\\(errorCode\\)\\s*\\)",
            Pattern.DOTALL);

    private final UserFacingMessageResolver messageResolver = new UserFacingMessageResolver();

    @Test
    void errorCodeMessagesMustUseChineseExceptSuccessResponse() {
        List<String> violations = Arrays.stream(ErrorCode.values())
                .filter(errorCode -> errorCode != ErrorCode.SUCCESS)
                .filter(errorCode -> !messageResolver.containsChinese(errorCode.getMessage()))
                .map(errorCode -> errorCode.name() + "=" + errorCode.getMessage())
                .toList();

        assertTrue(violations.isEmpty(), "错误码用户文案必须包含中文: " + violations);
    }

    @Test
    void controllerExceptionMessageLiteralsMustContainChinese() throws IOException {
        Path projectRoot = Path.of(System.getProperty("projectBaseDir", ".")).toAbsolutePath().normalize();
        Path controllerRoot = projectRoot.resolve("src/main/java/com/rush/rushaicodemother/controller");
        List<String> violations = new ArrayList<>();

        try (Stream<Path> sourceFiles = Files.walk(controllerRoot)) {
            sourceFiles.filter(path -> path.toString().endsWith(".java"))
                    .forEach(path -> inspectControllerSource(projectRoot, path, violations));
        }

        assertTrue(violations.isEmpty(), "Controller 用户异常存在纯英文硬编码: " + violations);
    }

    @Test
    void globalExceptionBoundaryMustAlwaysApplyChineseMessagePolicy() throws IOException {
        Path projectRoot = Path.of(System.getProperty("projectBaseDir", ".")).toAbsolutePath().normalize();
        Path handlerPath = projectRoot.resolve(
                "src/main/java/com/rush/rushaicodemother/exception/GlobalExceptionHandler.java");
        String source = Files.readString(handlerPath, StandardCharsets.UTF_8);

        assertTrue(SAFE_RESPONSE_BOUNDARY.matcher(source).find(),
                "GlobalExceptionHandler.respond 必须统一调用 UserFacingMessageResolver");
    }

    private void inspectControllerSource(Path projectRoot, Path sourcePath, List<String> violations) {
        try {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            Matcher invocationMatcher = CONTROLLER_EXCEPTION_INVOCATION.matcher(source);
            while (invocationMatcher.find()) {
                String invocation = invocationMatcher.group();
                Matcher literalMatcher = STRING_LITERAL.matcher(invocation);
                boolean containsLiteral = false;
                boolean containsChineseLiteral = false;
                while (literalMatcher.find()) {
                    containsLiteral = true;
                    if (messageResolver.containsChinese(literalMatcher.group())) {
                        containsChineseLiteral = true;
                        break;
                    }
                }
                if (containsLiteral && !containsChineseLiteral) {
                    String location = projectRoot.relativize(sourcePath).toString();
                    String excerpt = invocation.replaceAll("\\s+", " ").trim();
                    violations.add(location + " -> " + excerpt);
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("读取 Controller 源文件失败: " + sourcePath, exception);
        }
    }
}