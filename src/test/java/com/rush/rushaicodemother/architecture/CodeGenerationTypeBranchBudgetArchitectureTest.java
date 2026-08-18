package com.rush.rushaicodemother.architecture;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 防止工程类型显式分支扩散到新的生产文件。 */
class CodeGenerationTypeBranchBudgetArchitectureTest {

    private static final Path PRODUCTION_SOURCE_ROOT = Path.of("src", "main", "java");
    private static final Pattern SWITCH_PATTERN = Pattern.compile("\\bswitch\\s*\\(");
    private static final int MAX_FILES_WITH_TYPE_SWITCH = 18;

    /**
     * 当前集合是待继续压缩的债务上限，不是推荐结构。
     * 每迁移一个注册式 adapter 都应删除对应文件，任何新文件不得加入该集合。
     */
    private static final Set<String> LEGACY_TYPE_SWITCH_FILES = Set.of(
            "com/rush/rushaicodemother/ai/AiCodeGeneratorServiceFactory.java",
            "com/rush/rushaicodemother/ai/intent/BackendIntentDetector.java",
            "com/rush/rushaicodemother/ai/intent/DeterministicCodeGenTypeRouter.java",
            "com/rush/rushaicodemother/ai/model/GenerationPerformanceProfile.java",
            "com/rush/rushaicodemother/core/AiCodeGeneratorFacade.java",
            "com/rush/rushaicodemother/core/handler/StreamHandlerExecutor.java",
            "com/rush/rushaicodemother/orchestration/AgentGenerationOrchestrator.java",
            "com/rush/rushaicodemother/orchestration/edit/AgentEditPlanningService.java",
            "com/rush/rushaicodemother/orchestration/edit/BackgroundValidationService.java",
            "com/rush/rushaicodemother/orchestration/edit/EditFileLocatorService.java",
            "com/rush/rushaicodemother/orchestration/pipeline/SlotFillGenerationPipeline.java",
            "com/rush/rushaicodemother/orchestration/runtime/agent/GenerationAgentTurnPolicy.java",
            "com/rush/rushaicodemother/orchestration/runtime/execution/GenerationStageAdmissionProperties.java",
            "com/rush/rushaicodemother/orchestration/workspace/GenerationWorkspaceService.java",
            "com/rush/rushaicodemother/service/artifact/LocalAppArtifactLifecycleService.java",
            "com/rush/rushaicodemother/service/credit/GenerationCreditReservationPolicy.java",
            "com/rush/rushaicodemother/service/devserver/DevServerManager.java",
            "com/rush/rushaicodemother/service/workspace/LocalAppCodeWorkspaceService.java"
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

    private Set<String> findFilesWithTypeSwitch() throws IOException {
        Set<String> matches = new TreeSet<>();
        try (Stream<Path> paths = Files.walk(PRODUCTION_SOURCE_ROOT)) {
            for (Path path : paths.filter(Files::isRegularFile)
                    .filter(candidate -> candidate.toString().endsWith(".java"))
                    .toList()) {
                String source = Files.readString(path);
                if (source.contains("CodeGenTypeEnum")
                        && SWITCH_PATTERN.matcher(source).find()) {
                    matches.add(PRODUCTION_SOURCE_ROOT.relativize(path)
                            .toString()
                            .replace('\\', '/'));
                }
            }
        }
        return matches;
    }
}
