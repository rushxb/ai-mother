package com.rush.rushaicodemother.orchestration.edit.fallback;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.edit.EditFileCandidate;
import com.rush.rushaicodemother.orchestration.edit.EditWorkspaceFile;
import com.rush.rushaicodemother.orchestration.edit.EditWorkspaceFileService;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 合并工程生态 adapter 提供的编辑回退候选。
 *
 * <p>所有候选必须先转换为 canonical workspace 相对路径，再经过统一文件安全
 * 实现校验；因此新增生态 adapter 无法绕过隐藏文件、符号链接与越界限制。</p>
 */
@Component
public class EditFallbackCandidateResolver {

    private static final String MATCH_TYPE = "fallback_entry";
    private static final int MATCH_SCORE = 50;
    private static final String MATCH_REASON = "Fallback project entry file";

    private final Map<CodeGenTypeEnum, List<EditFallbackCandidateAdapter>> adaptersByType;
    private final EditWorkspaceFileService workspaceFileService;

    /** 构建不可变的多 adapter 注册表；Full Stack 等类型可组合多个生态 adapter。 */
    public EditFallbackCandidateResolver(List<EditFallbackCandidateAdapter> adapters,
                                         EditWorkspaceFileService workspaceFileService) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("编辑回退候选 adapter 列表不能为空");
        }
        this.workspaceFileService = Objects.requireNonNull(
                workspaceFileService, "编辑工作区文件服务不能为空");
        this.adaptersByType = indexAdapters(adapters);
    }

    /** 返回指定工程类型中存在且通过统一路径安全校验的回退候选。 */
    public List<EditFileCandidate> resolve(GenerationWorkspace workspace,
                                           CodeGenTypeEnum codeGenType) {
        if (workspace == null || !workspace.exists() || codeGenType == null
                || workspace.canonicalRootPath() == null) {
            return List.of();
        }
        List<EditFallbackCandidateAdapter> adapters = adaptersByType.get(codeGenType);
        if (adapters == null || adapters.isEmpty()) {
            return List.of();
        }
        Path canonicalRoot = workspace.canonicalRootPath().toAbsolutePath().normalize();
        List<EditFileCandidate> candidates = new ArrayList<>();
        Set<String> seenPaths = new LinkedHashSet<>();
        for (EditFallbackCandidateAdapter adapter : adapters) {
            List<Path> candidatePaths = adapter.candidatePaths(workspace);
            if (candidatePaths == null) {
                continue;
            }
            for (Path candidatePath : candidatePaths) {
                resolveCandidate(workspace, canonicalRoot, candidatePath).ifPresent(file -> {
                    if (seenPaths.add(file.relativePath())) {
                        candidates.add(toCandidate(file));
                    }
                });
            }
        }
        return List.copyOf(candidates);
    }

    private Optional<EditWorkspaceFile> resolveCandidate(
            GenerationWorkspace workspace,
            Path canonicalRoot,
            Path candidatePath) {
        if (candidatePath == null) {
            return Optional.empty();
        }
        Path normalizedPath = candidatePath.toAbsolutePath().normalize();
        if (!normalizedPath.startsWith(canonicalRoot)) {
            return Optional.empty();
        }
        String relativePath = canonicalRoot.relativize(normalizedPath)
                .toString()
                .replace('\\', '/');
        return workspaceFileService.resolveEditableFile(workspace, relativePath);
    }

    private EditFileCandidate toCandidate(EditWorkspaceFile file) {
        return new EditFileCandidate(
                file.relativePath(),
                file.fileName(),
                MATCH_TYPE,
                MATCH_SCORE,
                MATCH_REASON,
                List.of()
        );
    }

    private static Map<CodeGenTypeEnum, List<EditFallbackCandidateAdapter>> indexAdapters(
            List<EditFallbackCandidateAdapter> adapters) {
        EnumMap<CodeGenTypeEnum, List<EditFallbackCandidateAdapter>> indexed =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (EditFallbackCandidateAdapter adapter : adapters) {
            if (adapter == null) {
                throw new IllegalStateException("编辑回退候选 adapter 列表不能包含 null");
            }
            Set<CodeGenTypeEnum> supportedTypes = adapter.supportedCodeGenTypes();
            if (supportedTypes == null || supportedTypes.isEmpty()) {
                throw new IllegalStateException(
                        "编辑回退候选 adapter 必须声明至少一个工程类型: "
                                + adapter.getClass().getName());
            }
            for (CodeGenTypeEnum supportedType : supportedTypes) {
                if (supportedType == null) {
                    throw new IllegalStateException(
                            "编辑回退候选 adapter 不能声明 null 工程类型: "
                                    + adapter.getClass().getName());
                }
                indexed.computeIfAbsent(supportedType, ignored -> new ArrayList<>())
                        .add(adapter);
            }
        }
        EnumMap<CodeGenTypeEnum, List<EditFallbackCandidateAdapter>> immutable =
                new EnumMap<>(CodeGenTypeEnum.class);
        indexed.forEach((type, registeredAdapters) ->
                immutable.put(type, List.copyOf(registeredAdapters)));
        return Map.copyOf(immutable);
    }
}
