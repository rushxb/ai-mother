package com.rush.rushaicodemother.orchestration.create;

import com.rush.rushaicodemother.ai.model.CreateSpec;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.rush.rushaicodemother.orchestration.create.CreatePromptKeywordMatcher.containsAny;

/**
 * Go 后端 recipe 已内建能力的唯一标识。
 *
 * <p>能力 slot 描述的是基础 CRUD 文件应包含的行为，不等同于一个额外文件补丁。
 * Planner 与 renderer 必须复用同一映射，避免模板已有能力却被误判为未覆盖。</p>
 */
public enum BackendRecipeCapability {

    SEARCH(
            "module_search",
            "backend-search",
            "后端搜索扩展",
            "后端搜索需要模糊查询和分页能力",
            List.of("搜索", "筛选", "filter", "search", "查询"),
            CreateSpec.Backend::search
    ),
    IMPORT_EXPORT(
            "module_import_export",
            "backend-export",
            "后端导入导出",
            "后端导入导出需要 CSV/JSON 批量操作",
            List.of("导入", "导出", "批量", "csv", "excel", "import", "export"),
            CreateSpec.Backend::importExport
    ),
    PAGINATION(
            "module_pagination",
            "backend-pagination",
            "后端分页扩展",
            "后端分页需要统一分页查询辅助",
            List.of("分页", "pagination", "列表", "page"),
            CreateSpec.Backend::pagination
    );

    private static final Map<String, BackendRecipeCapability> CAPABILITIES_BY_SLOT =
            Stream.of(values())
                    .collect(Collectors.toUnmodifiableMap(BackendRecipeCapability::slotId, capability -> capability));

    private final String slotId;
    private final String moduleId;
    private final String displayName;
    private final String reason;
    private final List<String> keywords;
    private final Predicate<CreateSpec.Backend> enabledPredicate;

    BackendRecipeCapability(String slotId,
                            String moduleId,
                            String displayName,
                            String reason,
                            List<String> keywords,
                            Predicate<CreateSpec.Backend> enabledPredicate) {
        this.slotId = slotId;
        this.moduleId = moduleId;
        this.displayName = displayName;
        this.reason = reason;
        this.keywords = List.copyOf(keywords);
        this.enabledPredicate = enabledPredicate;
    }

    public String slotId() {
        return slotId;
    }

    /** 判断规范是否已启用当前能力。 */
    public boolean isEnabled(CreateSpec.Backend backend) {
        return backend != null && enabledPredicate.test(backend);
    }

    /** 判断用户请求是否明确要求当前能力。 */
    boolean matches(String userMessage) {
        return containsAny(userMessage, keywords.toArray(String[]::new));
    }

    /** 将能力定义转换为 Planner 使用的功能模块。 */
    FeatureModuleManifest plannedModule(String templateId) {
        return new FeatureModuleManifest(
                moduleId,
                displayName,
                templateId,
                List.of(slotId),
                reason
        );
    }

    /** 按稳定 slot 标识解析能力；普通文件 slot 不属于本枚举。 */
    public static Optional<BackendRecipeCapability> fromSlotId(String slotId) {
        if (slotId == null || slotId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(CAPABILITIES_BY_SLOT.get(slotId));
    }

    /**
     * 将冻结计划中的能力下限合并进模型规格。
     *
     * <p>模型可以补充能力，但不能关闭 Planner 已经确定的必需能力，否则同一请求会因
     * 规格模型的布尔漂移而错误转交 Heavy。</p>
     */
    public static CreateSpec.Backend enforcePlannedCapabilities(CreateSpec.Backend backend,
                                                                SlotGroup group) {
        Objects.requireNonNull(backend, "后端规格不能为空");
        Set<BackendRecipeCapability> required = requiredBy(group);
        if (required.isEmpty()) {
            return backend;
        }
        return new CreateSpec.Backend(
                backend.apiStyle(),
                backend.authRequired(),
                backend.pagination() || required.contains(PAGINATION),
                backend.search() || required.contains(SEARCH),
                backend.sort(),
                backend.softDelete(),
                backend.auditFields(),
                backend.importExport() || required.contains(IMPORT_EXPORT),
                backend.batchActions(),
                backend.validationRules(),
                backend.errorStyle(),
                backend.moduleName()
        );
    }

    private static Set<BackendRecipeCapability> requiredBy(SlotGroup group) {
        EnumSet<BackendRecipeCapability> required = EnumSet.noneOf(BackendRecipeCapability.class);
        if (group == null) {
            return required;
        }
        for (String slotId : group.slotIds()) {
            fromSlotId(slotId).ifPresent(required::add);
        }
        return required;
    }
}
