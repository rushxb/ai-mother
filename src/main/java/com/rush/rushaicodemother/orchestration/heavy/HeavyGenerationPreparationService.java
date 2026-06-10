package com.rush.rushaicodemother.orchestration.heavy;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.constant.AppConstant;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.exception.ThrowUtils;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationRequest;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrationResult;
import com.rush.rushaicodemother.orchestration.GenerationOrchestrator;
import com.rush.rushaicodemother.orchestration.GenerationPreparation;
import com.rush.rushaicodemother.orchestration.artifact.ChangePlan;
import com.rush.rushaicodemother.orchestration.artifact.GenerationArtifact;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentAssembler;
import com.rush.rushaicodemother.orchestration.routing.HeavyGenerationIntentDecision;
import com.rush.rushaicodemother.orchestration.tool.GenerationToolExecutionContextService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspaceService;
import com.rush.rushaicodemother.service.GenerationMemoryContextService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

@Slf4j
@Service
@RequiredArgsConstructor
public class HeavyGenerationPreparationService {

    private static final int MAX_PROJECT_INDEX_FILES = 80;
    private static final int MAX_MODEL_CONTEXT_FILE_CHARS = 12000;

    private final HeavyGenerationIntentAssembler heavyGenerationIntentAssembler;
    private final GenerationMemoryContextService generationMemoryContextService;
    private final GenerationOrchestrator generationOrchestrator;
    private final GenerationToolExecutionContextService generationToolExecutionContextService;

    public GenerationPreparation prepare(App app, String userMessage) {
        GenerationIntent intent = recognizeGenerationIntent(app, userMessage);
        GenerationContextAssembly contextAssembly = assembleGenerationContext(intent);
        GenerationRoutingPlan routingPlan = routeGeneration(intent, contextAssembly);
        return buildGenerationPreparation(intent, routingPlan);
    }

    private GenerationIntent recognizeGenerationIntent(App app, String userMessage) {
        HeavyGenerationIntentDecision decision = heavyGenerationIntentAssembler.assemble(app, userMessage);
        return new GenerationIntent(
                app,
                decision.currentType(),
                decision.targetType(),
                decision.generationMessage(),
                decision.generatingStage(),
                decision.hasGeneratedCode(),
                decision.requiresBuild()
        );
    }

    private GenerationContextAssembly assembleGenerationContext(GenerationIntent intent) {
        return new GenerationContextAssembly(createProjectContextSupplier(intent.app()));
    }

    private GenerationRoutingPlan routeGeneration(GenerationIntent intent, GenerationContextAssembly contextAssembly) {
        return new GenerationRoutingPlan(
                routingPrompt -> CodeGenTypeEnum.max(intent.currentType(), intent.targetType()),
                contextAssembly
        );
    }

    private GenerationPreparation buildGenerationPreparation(GenerationIntent intent,
                                                             GenerationRoutingPlan routingPlan) {
        CodeGenTypeEnum targetType = intent.targetType() == null
                ? routingPlan.routingFunction().apply(intent.generationMessage())
                : intent.targetType();
        String memoryContext = generationMemoryContextService.buildGenerationMemoryContext(
                intent.app(),
                intent.generationMessage(),
                targetType
        );
        GenerationOrchestrationResult orchestrationResult = generationOrchestrator.prepare(
                new GenerationOrchestrationRequest(
                        intent.app(),
                        intent.generationMessage(),
                        intent.currentType(),
                        intent.generatingStage(),
                        intent.hasGeneratedCode(),
                        routingPlan.contextAssembly().projectContextSupplier(),
                        routingPlan.routingFunction(),
                        memoryContext
                )
        );
        GenerationPreparation preparation = new GenerationPreparation(
                orchestrationResult.originalType(),
                orchestrationResult.targetType(),
                orchestrationResult.upgradeRequired(),
                orchestrationResult.generatingStage(),
                orchestrationResult.enhancedMessage(),
                orchestrationResult.events(),
                orchestrationResult.artifacts(),
                orchestrationResult.qualityGateResult(),
                orchestrationResult.timings(),
                orchestrationResult.taskId()
        );
        bindToolExecutionContext(intent.app(), preparation);
        return preparation;
    }

    private void bindToolExecutionContext(App app, GenerationPreparation preparation) {
        if (app == null || app.getId() == null || preparation == null) {
            return;
        }
        GenerationArtifact changePlanArtifact = preparation.artifact("change_plan");
        ChangePlan changePlan = changePlanArtifact == null ? null : ChangePlan.fromPayload(changePlanArtifact.payload());
        boolean allowUnplannedWrite = changePlan != null && "project_bootstrap".equals(changePlan.changeScope());
        String generationMode = allowUnplannedWrite ? "full_generation" : "patch_first";
        generationToolExecutionContextService.bindChangePlan(
                app.getId(),
                preparation.taskId(),
                generationMode,
                preparation.targetType(),
                changePlan,
                allowUnplannedWrite,
                "orchestration_context"
        );
    }

    private Supplier<String> createProjectContextSupplier(App app) {
        return () -> buildProjectContext(app);
    }

    private File getCodeRootDir(App app) {
        String sourceDirName = app.getCodeGenType() + "_" + app.getId();
        File rootDir = new File(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName);
        ThrowUtils.throwIf(!rootDir.exists() || !rootDir.isDirectory(),
                ErrorCode.NOT_FOUND_ERROR, "应用代码不存在，请先生成代码");
        return rootDir;
    }

    private String buildProjectContext(App app) {
        try {
            File rootDir = getCodeRootDir(app);
            CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(app.getCodeGenType());
            if (codeGenTypeEnum == null) {
                return "";
            }
            String projectIndex = buildProjectIndex(rootDir);
            String keyFiles = switch (codeGenTypeEnum) {
                case HTML -> readSingleFileContext(rootDir, "index.html");
                case MULTI_FILE -> readMultiFileContext(rootDir, List.of("index.html", "style.css", "script.js"));
                case VUE_PROJECT -> readMultiFileContext(rootDir, List.of("src/App.vue", "src/main.js", "src/main.ts", "index.html"));
                case BACKEND_PROJECT -> readMultiFileContext(rootDir, List.of("go.mod", "cmd/server/main.go", "internal/config/config.go", "internal/database/database.go", "sql/schema.sql"));
                case FULL_STACK_PROJECT -> readMultiFileContext(rootDir, List.of("frontend/package.json", "frontend/src/services/request.ts", "frontend/src/App.vue", "backend/go.mod", "backend/cmd/server/main.go", "backend/internal/config/config.go", "backend/sql/schema.sql", ".env.example"));
            };
            if (StrUtil.isBlank(projectIndex)) {
                return keyFiles;
            }
            if (StrUtil.isBlank(keyFiles)) {
                return projectIndex;
            }
            return projectIndex + "\n\n" + keyFiles;
        } catch (Exception e) {
            log.warn("构建项目上下文失败，appId: {}, error: {}", app.getId(), e.getMessage());
            return "";
        }
    }

    private String buildProjectIndex(File rootDir) {
        List<String> indexedFiles = new ArrayList<>();
        try {
            FileUtil.walkFiles(rootDir, file -> {
                if (indexedFiles.size() >= MAX_PROJECT_INDEX_FILES) {
                    return;
                }
                if (shouldHideFile(file)) {
                    return;
                }
                String relativePath = normalizeRelativePath(rootDir, file);
                if (file.isDirectory()) {
                    return;
                }
                String extension = FileUtil.extName(file).toLowerCase();
                if (isIndexableProjectFile(relativePath, extension)) {
                    indexedFiles.add(relativePath);
                }
            });
        } catch (Exception e) {
            log.warn("构建项目索引失败: {}", e.getMessage());
        }
        if (indexedFiles.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        builder.append("项目索引:\n");
        indexedFiles.stream()
                .sorted()
                .limit(MAX_PROJECT_INDEX_FILES)
                .forEach(path -> builder.append("- ").append(path).append('\n'));
        return builder.toString().trim();
    }

    private boolean isIndexableProjectFile(String relativePath, String extension) {
        if (StrUtil.isBlank(relativePath)) {
            return false;
        }
        if (relativePath.startsWith("src/") || relativePath.startsWith("public/") || relativePath.startsWith("cmd/")
                || relativePath.startsWith("internal/") || relativePath.startsWith("sql/")
                || relativePath.startsWith("frontend/") || relativePath.startsWith("backend/")) {
            return Set.of("vue", "js", "ts", "jsx", "tsx", "css", "scss", "less", "json", "svg", "md",
                    "go", "sql", "mod", "sum", "yml", "yaml").contains(extension);
        }
        return Set.of("package.json", "vite.config.js", "vite.config.ts", "index.html", "tsconfig.json",
                        "tsconfig.app.json", "go.mod", "go.sum", "README.md", "docker-compose.yml", ".env.example")
                .contains(relativePath);
    }

    private String readSingleFileContext(File rootDir, String relativePath) {
        File file = new File(rootDir, relativePath);
        if (!file.exists() || !file.isFile()) {
            return "";
        }
        String content = FileUtil.readString(file, StandardCharsets.UTF_8);
        return "当前文件: " + relativePath + "\n```html\n" + truncateForModel(content) + "\n```";
    }

    private String readMultiFileContext(File rootDir, List<String> relativePaths) {
        List<String> sections = new ArrayList<>();
        for (String relativePath : relativePaths) {
            File file = new File(rootDir, relativePath);
            if (!file.exists() || !file.isFile()) {
                continue;
            }
            String extension = FileUtil.extName(file);
            String content = FileUtil.readString(file, StandardCharsets.UTF_8);
            sections.add("当前文件: " + relativePath + "\n```" + extension + "\n" + truncateForModel(content) + "\n```");
        }
        return String.join("\n\n", sections);
    }

    private String truncateForModel(String content) {
        if (content == null) {
            return "";
        }
        if (content.length() <= MAX_MODEL_CONTEXT_FILE_CHARS) {
            return content;
        }
        return content.substring(0, MAX_MODEL_CONTEXT_FILE_CHARS)
                + "\n<!-- 文件内容过长，以上为截断后的前 "
                + MAX_MODEL_CONTEXT_FILE_CHARS
                + " 个字符 -->";
    }

    private boolean shouldHideFile(File file) {
        return GenerationWorkspaceService.HIDDEN_FILE_NAMES.contains(file.getName());
    }

    private String normalizeRelativePath(File rootDir, File file) {
        try {
            Path rootPath = rootDir.getCanonicalFile().toPath();
            Path filePath = file.getCanonicalFile().toPath();
            return rootPath.relativize(filePath).toString().replace(File.separator, "/");
        } catch (Exception e) {
            return file.getName();
        }
    }

    private record GenerationIntent(App app,
                                    CodeGenTypeEnum currentType,
                                    CodeGenTypeEnum targetType,
                                    String generationMessage,
                                    String generatingStage,
                                    boolean hasGeneratedCode,
                                    boolean requiresBuild) {
    }

    private record GenerationContextAssembly(Supplier<String> projectContextSupplier) {
    }

    private record GenerationRoutingPlan(Function<String, CodeGenTypeEnum> routingFunction,
                                         GenerationContextAssembly contextAssembly) {
    }
}
