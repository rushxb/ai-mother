package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制器持久化边界门禁。
 *
 * <p>Controller 只负责协议适配、认证上下文提取和调用用例服务，禁止直接调用通用 CRUD
 * 方法编排数据库写入。事务、影响行数检查和并发语义必须归属于服务层。</p>
 */
class ControllerPersistenceBoundaryArchitectureTest {

    private static final Path CONTROLLER_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother", "controller"
    );
    private static final Pattern GENERIC_PERSISTENCE_CALL = Pattern.compile(
            "\\.\\s*(save|saveBatch|updateById|updateBatchById|removeById|removeByIds)\\s*\\("
    );

    @Test
    void controllersMustNotInvokeGenericPersistenceMethods() throws IOException {
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(CONTROLLER_SOURCE_ROOT)) {
            for (Path sourceFile : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                collectViolations(sourceFile, violations);
            }
        }

        violations.sort(Comparator.naturalOrder());
        assertTrue(violations.isEmpty(), () ->
                "Controllers must delegate writes to transactional use-case services:\n - "
                        + String.join("\n - ", violations));
    }

    private void collectViolations(Path sourceFile, List<String> violations) throws IOException {
        List<String> lines = Files.readAllLines(sourceFile);
        for (int index = 0; index < lines.size(); index++) {
            Matcher matcher = GENERIC_PERSISTENCE_CALL.matcher(lines.get(index));
            if (matcher.find()) {
                violations.add(sourceFile + ":" + (index + 1) + " invokes " + matcher.group(1));
            }
        }
    }
}
