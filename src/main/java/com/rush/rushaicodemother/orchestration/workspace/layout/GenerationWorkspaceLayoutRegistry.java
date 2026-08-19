package com.rush.rushaicodemother.orchestration.workspace.layout;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 工作区布局的唯一注册表。
 *
 * <p>启动时验证每个工程类型恰好归属一个 adapter；解析后再次验证角色根没有逃逸
 * 规范工作区。这样布局扩展失败会在启动或提交前暴露，而不是在构建阶段才报错。</p>
 */
@Component
public class GenerationWorkspaceLayoutRegistry {

    private final Map<CodeGenTypeEnum, GenerationWorkspaceLayoutAdapter> adaptersByType;

    public GenerationWorkspaceLayoutRegistry(List<GenerationWorkspaceLayoutAdapter> adapters) {
        if (adapters == null || adapters.isEmpty()) {
            throw new IllegalStateException("工作区布局 adapter 不能为空");
        }
        EnumMap<CodeGenTypeEnum, GenerationWorkspaceLayoutAdapter> registered =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (GenerationWorkspaceLayoutAdapter adapter : adapters) {
            register(registered, Objects.requireNonNull(adapter, "工作区布局 adapter 不能为空"));
        }
        Set<CodeGenTypeEnum> missingTypes = EnumSet.allOf(CodeGenTypeEnum.class);
        missingTypes.removeAll(registered.keySet());
        if (!missingTypes.isEmpty()) {
            throw new IllegalStateException("工程类型缺少工作区布局 adapter: " + missingTypes);
        }
        this.adaptersByType = Map.copyOf(registered);
    }

    /** 为非 Spring 调用者提供与生产注册完全一致的默认布局集合。 */
    public static GenerationWorkspaceLayoutRegistry defaults() {
        return new GenerationWorkspaceLayoutRegistry(List.of(
                new FrontendWorkspaceLayoutAdapter(),
                new BackendWorkspaceLayoutAdapter(),
                new FullStackWorkspaceLayoutAdapter()
        ));
    }

    /** 解析并校验指定工程类型的角色根目录。 */
    public GenerationWorkspaceLayout resolve(CodeGenTypeEnum codeGenType, Path canonicalRootPath) {
        Objects.requireNonNull(codeGenType, "代码生成类型不能为空");
        Path canonicalRoot = Objects.requireNonNull(
                canonicalRootPath, "规范工作区根目录不能为空").toAbsolutePath().normalize();
        GenerationWorkspaceLayout layout = Objects.requireNonNull(
                adaptersByType.get(codeGenType).resolve(canonicalRoot),
                "工作区布局 adapter 不能返回 null");
        validateWithinCanonicalRoot(canonicalRoot, layout.frontendRootPath(), "前端");
        validateWithinCanonicalRoot(canonicalRoot, layout.backendRootPath(), "后端");
        return layout;
    }

    private void register(
            EnumMap<CodeGenTypeEnum, GenerationWorkspaceLayoutAdapter> registered,
            GenerationWorkspaceLayoutAdapter adapter) {
        Set<CodeGenTypeEnum> supportedTypes = adapter.supportedTypes();
        if (supportedTypes == null || supportedTypes.isEmpty()
                || supportedTypes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalStateException("工作区布局 adapter 必须声明至少一个有效工程类型");
        }
        for (CodeGenTypeEnum type : supportedTypes) {
            GenerationWorkspaceLayoutAdapter previous = registered.putIfAbsent(type, adapter);
            if (previous != null) {
                throw new IllegalStateException("工程类型存在重复工作区布局 adapter: " + type);
            }
        }
    }

    private void validateWithinCanonicalRoot(Path canonicalRoot, Path roleRoot, String role) {
        if (roleRoot != null && !roleRoot.startsWith(canonicalRoot)) {
            throw new IllegalStateException(role + "工作区根目录越出规范工作区");
        }
    }
}
