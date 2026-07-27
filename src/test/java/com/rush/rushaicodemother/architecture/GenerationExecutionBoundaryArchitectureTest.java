package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止 HTTP 接口或普通业务服务绕过持久化任务运行时直接执行生成管线。 */
class GenerationExecutionBoundaryArchitectureTest {

    private static final Path MAIN_SOURCE_ROOT = Path.of(
            "src", "main", "java", "com", "rush", "rushaicodemother"
    );

    private static final List<String> CONTROLLER_FORBIDDEN_DEPENDENCIES = List.of(
            "AiCodeGeneratorFacade",
            "AiCodeGeneratorService",
            "AiCodeGeneratorServiceFactory",
            "AiCodeEditServiceFactory",
            "StreamingModelFactory",
            "GenerationPipelineExecutor",
            "GenerationTaskCommandExecutionService"
    );

    private static final Set<Path> LEGACY_MODEL_INVOCATION_OWNERS = Set.of(
            MAIN_SOURCE_ROOT.resolve(Path.of(
                    "orchestration", "edit", "GenerationEditModelInvoker.java")),
            MAIN_SOURCE_ROOT.resolve(Path.of(
                    "orchestration", "edit", "LightweightEditAiService.java")),
            MAIN_SOURCE_ROOT.resolve(Path.of(
                    "orchestration", "edit", "AgentEditGenerationService.java"))
    );

    @Test
    void controllerMustOnlySubmitManagedGenerationTasks() throws IOException {
        List<String> violations = new ArrayList<>();
        Path controllerRoot = MAIN_SOURCE_ROOT.resolve("controller");
        try (var sources = Files.walk(controllerRoot)) {
            for (Path source : sources.filter(this::isJavaSource).toList()) {
                String content = Files.readString(source);
                for (String forbiddenDependency : CONTROLLER_FORBIDDEN_DEPENDENCIES) {
                    if (content.contains(forbiddenDependency)) {
                        violations.add(source + " 直接依赖 " + forbiddenDependency);
                    }
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Controller 必须通过 AppService/GenerationTaskOrchestrator 提交持久化任务，禁止直接执行模型或生成管线：\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void pipelineExecutorMustOnlyBeInvokedByDurableWorker() throws IOException {
        List<String> violations = new ArrayList<>();
        Path expectedOwner = MAIN_SOURCE_ROOT.resolve(Path.of(
                "orchestration", "runtime", "task", "GenerationTaskCommandExecutionService.java"
        ));
        try (var sources = Files.walk(MAIN_SOURCE_ROOT)) {
            for (Path source : sources.filter(this::isJavaSource).toList()) {
                String content = Files.readString(source);
                if ((content.contains("pipelineExecutor.execute(")
                        || content.contains("generationPipelineExecutor.execute("))
                        && !source.equals(expectedOwner)) {
                    violations.add(source.toString());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "GenerationPipelineExecutor 只能由持久化 Worker 调用，发现未受管入口：\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void synchronousFacadeMustNotHaveProductionCallers() throws IOException {
        List<String> violations = new ArrayList<>();
        Path facade = MAIN_SOURCE_ROOT.resolve(Path.of("core", "AiCodeGeneratorFacade.java"));
        try (var sources = Files.walk(MAIN_SOURCE_ROOT)) {
            for (Path source : sources.filter(this::isJavaSource).toList()) {
                if (source.equals(facade)) {
                    continue;
                }
                String content = Files.readString(source);
                if (content.contains(".generateAndSaveCode(")) {
                    violations.add(source.toString());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "同步生成门面不具备任务 SLA、持久化恢复和发布围栏，生产代码不得调用：\n - "
                        + String.join("\n - ", violations));
    }

    @Test
    void legacyEditModelInvocationMustStayInsideExplicitCompatibilityOwners() throws IOException {
        List<String> violations = new ArrayList<>();
        try (var sources = Files.walk(MAIN_SOURCE_ROOT)) {
            for (Path source : sources.filter(this::isJavaSource).toList()) {
                if (!LEGACY_MODEL_INVOCATION_OWNERS.contains(source)
                        && Files.readString(source).contains("invokeLegacy(")) {
                    violations.add(source.toString());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "受管生产链路不得新增 legacy 模型调用，发现越界入口：\n - "
                        + String.join("\n - ", violations));
    }

    private boolean isJavaSource(Path path) {
        return Files.isRegularFile(path) && path.getFileName().toString().endsWith(".java");
    }
}
