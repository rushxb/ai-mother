package com.rush.rushaicodemother.orchestration.decision;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationResourceRequirements;
import com.rush.rushaicodemother.orchestration.intent.IntentAffectedScope;
import com.rush.rushaicodemother.orchestration.intent.IntentOperationType;
import com.rush.rushaicodemother.orchestration.intent.IntentProfile;
import com.rush.rushaicodemother.orchestration.router.ExpectedValidationLevel;
import com.rush.rushaicodemother.orchestration.router.GenerationMode;
import com.rush.rushaicodemother.orchestration.router.GenerationModeDecision;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 一次请求的不可变场景事实。
 *
 * <p>路由、资源、工具权限和验证下限都从这一对象读取；
 * 原始 Prompt 不进入本对象，避免后续模块再次解析自然语言。</p>
 */
public record GenerationScenarioDecision(
        IntentProfile intentProfile,
        GenerationGuidanceSelection guidanceSelection,
        CodeGenTypeEnum targetType,
        GenerationMutability mutability,
        GenerationResourceRequirements requiredResources,
        GenerationModeDecision routeDecision,
        GenerationToolPermissionProfile toolPermissionProfile,
        String ruleVersion,
        String releaseFingerprint
) {

    public GenerationScenarioDecision {
        Objects.requireNonNull(intentProfile, "场景意图画像不能为空");
        guidanceSelection = guidanceSelection == null
                ? GenerationGuidanceSelection.empty()
                : guidanceSelection;
        Objects.requireNonNull(targetType, "场景目标工程类型不能为空");
        Objects.requireNonNull(mutability, "场景可变性不能为空");
        Objects.requireNonNull(requiredResources, "场景资源需求不能为空");
        Objects.requireNonNull(routeDecision, "场景路由决策不能为空");
        Objects.requireNonNull(toolPermissionProfile, "场景工具权限不能为空");
        ruleVersion = requireText(ruleVersion, "场景规则版本不能为空");
        releaseFingerprint = requireText(releaseFingerprint, "场景发布指纹不能为空");

        boolean operationReadOnly = isReadOnlyOperation(intentProfile.operationType());
        boolean routeReadOnly = routeDecision.mode() == GenerationMode.READ_ONLY;
        if (operationReadOnly != (mutability == GenerationMutability.READ_ONLY)
                || routeReadOnly != (mutability == GenerationMutability.READ_ONLY)) {
            throw new IllegalArgumentException("场景操作、可变性与路由必须保持一致");
        }
        if (mutability == GenerationMutability.READ_ONLY) {
            if (requiredResources.databaseRequired()) {
                throw new IllegalArgumentException("只读场景不得预配数据库资源");
            }
            if (toolPermissionProfile != GenerationToolPermissionProfile.READ_ONLY) {
                throw new IllegalArgumentException("只读场景只能使用只读工具权限");
            }
        } else {
            if (requiredResources.databaseRequired() != intentProfile.requiresDatabase()) {
                throw new IllegalArgumentException("写场景的数据库资源需求必须来自意图画像");
            }
            if (!toolPermissionProfile.writeAllowed()) {
                throw new IllegalArgumentException("写场景必须具备受控写工具权限");
            }
            CodeGenTypeEnum explicitProjectType = intentProfile.explicitProjectType();
            if (explicitProjectType != null
                    && CodeGenTypeEnum.max(targetType, explicitProjectType) != targetType) {
                throw new IllegalArgumentException("场景目标工程类型必须承载显式迁移诉求");
            }
        }
    }

    /** 兼容尚未携带冻结工程指引的历史任务与既有调用方。 */
    public GenerationScenarioDecision(
            IntentProfile intentProfile,
            CodeGenTypeEnum targetType,
            GenerationMutability mutability,
            GenerationResourceRequirements requiredResources,
            GenerationModeDecision routeDecision,
            GenerationToolPermissionProfile toolPermissionProfile,
            String ruleVersion,
            String releaseFingerprint
    ) {
        this(intentProfile, GenerationGuidanceSelection.empty(), targetType, mutability,
                requiredResources, routeDecision, toolPermissionProfile, ruleVersion,
                releaseFingerprint);
    }

    public IntentOperationType operation() {
        return intentProfile.operationType();
    }

    public Set<IntentAffectedScope> contextHints() {
        return intentProfile.affectedScopes();
    }

    public ExpectedValidationLevel validationFloor() {
        return routeDecision.expectedValidationLevel();
    }

    /** 保留场景其余事实，只在受控 fallback 时替换路由。 */
    public GenerationScenarioDecision withRoute(GenerationModeDecision replacement) {
        return new GenerationScenarioDecision(
                intentProfile,
                guidanceSelection,
                targetType,
                mutability,
                requiredResources,
                replacement,
                toolPermissionProfile,
                ruleVersion,
                releaseFingerprint);
    }

    /** 从旧命令字段恢复一个可执行的保守决策。 */
    public static GenerationScenarioDecision restoreLegacy(IntentProfile profile,
                                                           CodeGenTypeEnum targetType,
                                                           GenerationResourceRequirements resources,
                                                           GenerationModeDecision routeDecision,
                                                           int schemaVersion) {
        IntentProfile safeProfile = profile == null ? IntentProfile.unknown() : profile;
        GenerationModeDecision safeRoute = Objects.requireNonNull(routeDecision, "旧命令路由不能为空");
        boolean readOnly = safeRoute.mode() == GenerationMode.READ_ONLY;
        safeProfile = alignLegacyOperation(safeProfile, readOnly);
        boolean databaseRequired = !readOnly
                && (safeProfile.requiresDatabase()
                || resources != null && resources.databaseRequired());
        safeProfile = alignLegacyDatabaseRequirement(safeProfile, databaseRequired);
        GenerationMutability mutability = readOnly ? GenerationMutability.READ_ONLY : GenerationMutability.WRITE;
        GenerationResourceRequirements safeResources = readOnly
                ? GenerationResourceRequirements.none()
                : GenerationResourceRequirements.ofDatabaseRequirement(databaseRequired);
        String legacyIdentity = "legacy-task-command-v" + schemaVersion;
        return new GenerationScenarioDecision(
                safeProfile,
                targetType,
                mutability,
                safeResources,
                safeRoute,
                readOnly ? GenerationToolPermissionProfile.READ_ONLY
                        : GenerationToolPermissionProfile.WRITE_FENCED,
                legacyIdentity,
                sha256(legacyIdentity));
    }

    private static IntentProfile alignLegacyOperation(IntentProfile profile, boolean readOnlyRoute) {
        boolean profileReadOnly = isReadOnlyOperation(profile.operationType());
        if (profileReadOnly == readOnlyRoute) {
            return profile;
        }
        IntentOperationType compatibleOperation = readOnlyRoute
                ? IntentOperationType.EXPLAIN
                : IntentOperationType.EDIT;
        return new IntentProfile(
                compatibleOperation,
                profile.affectedScopes(),
                profile.semanticComplexity(),
                profile.requiresBackend(),
                profile.requiresDatabase(),
                profile.destructiveRisk(),
                profile.expectedFileCount(),
                profile.validationRisk(),
                profile.confidence(),
                profile.ambiguitySignal(),
                profile.primaryBusinessDomain(),
                profile.explicitProjectType());
    }

    private static IntentProfile alignLegacyDatabaseRequirement(
            IntentProfile profile,
            boolean databaseRequired) {
        if (!databaseRequired || profile.requiresDatabase()) {
            return profile;
        }
        EnumSet<IntentAffectedScope> scopes = EnumSet.copyOf(profile.affectedScopes());
        scopes.add(IntentAffectedScope.DATABASE);
        return new IntentProfile(
                profile.operationType(),
                scopes,
                profile.semanticComplexity(),
                true,
                true,
                profile.destructiveRisk(),
                profile.expectedFileCount(),
                profile.validationRisk(),
                profile.confidence(),
                profile.ambiguitySignal(),
                profile.primaryBusinessDomain(),
                profile.explicitProjectType());
    }

    public static boolean isReadOnlyOperation(IntentOperationType operationType) {
        return operationType == IntentOperationType.EXPLAIN
                || operationType == IntentOperationType.AUDIT
                || operationType == IntentOperationType.PLAN;
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }
}
