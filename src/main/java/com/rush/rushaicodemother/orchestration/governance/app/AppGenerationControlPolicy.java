package com.rush.rushaicodemother.orchestration.governance.app;

import com.rush.rushaicodemother.ai.model.GenerationPerformanceProfile;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;

/**
 * 单个应用的生成控制策略快照。
 *
 * <p>策略是数据库事实的不可变投影；缺少持久记录时使用安全默认值。当前应用仍共享一个
 * 可写工作区，因此并发安全上限保持为 1，后续只有在任务级工作区隔离完成后才能放宽。</p>
 */
public record AppGenerationControlPolicy(
        Long appId,
        long version,
        boolean generationPaused,
        boolean emergencyStopped,
        int maxConcurrentTasks,
        ModelPolicy modelPolicy,
        DependencyMutationPolicy dependencyMutationPolicy,
        DependencyNetworkPolicy dependencyNetworkPolicy,
        DangerousToolPolicy dangerousToolPolicy,
        Long monthlyCreditLimit,
        Long updatedBy,
        Instant updatedAt
) {

    public static final int MAX_SAFE_CONCURRENT_TASKS = 1;

    public AppGenerationControlPolicy {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 必须为正数");
        }
        if (version < 0) {
            throw new IllegalArgumentException("应用生成控制版本不能为负数");
        }
        if (maxConcurrentTasks < 1 || maxConcurrentTasks > MAX_SAFE_CONCURRENT_TASKS) {
            throw new IllegalArgumentException("当前应用生成并发上限只能为 1");
        }
        Objects.requireNonNull(modelPolicy, "模型策略不能为空");
        Objects.requireNonNull(dependencyMutationPolicy, "依赖修改策略不能为空");
        Objects.requireNonNull(dependencyNetworkPolicy, "依赖网络策略不能为空");
        Objects.requireNonNull(dangerousToolPolicy, "危险工具策略不能为空");
        if (monthlyCreditLimit != null && monthlyCreditLimit < 0) {
            throw new IllegalArgumentException("应用月预算不能为负数");
        }
        if (version == 0 && (updatedBy != null || updatedAt != null)) {
            throw new IllegalArgumentException("默认应用生成控制不能携带更新元数据");
        }
        if (version > 0 && (updatedBy == null || updatedBy <= 0 || updatedAt == null)) {
            throw new IllegalArgumentException("持久应用生成控制缺少更新元数据");
        }
    }

    /** 未创建控制记录时使用平台安全默认值。 */
    public static AppGenerationControlPolicy defaults(Long appId) {
        return new AppGenerationControlPolicy(
                appId,
                0L,
                false,
                false,
                1,
                ModelPolicy.PLATFORM_DEFAULT,
                DependencyMutationPolicy.ALLOW,
                DependencyNetworkPolicy.TRUSTED_REGISTRY_ONLY,
                DangerousToolPolicy.REQUIRE_APPROVAL,
                null,
                null,
                null
        );
    }

    public boolean hasMonthlyCreditLimit() {
        return monthlyCreditLimit != null;
    }

    /** 将应用模型约束应用到平台已选择的性能档位，不反向提升模型成本。 */
    public GenerationPerformanceProfile constrainModelProfile(
            GenerationPerformanceProfile selectedProfile) {
        Objects.requireNonNull(selectedProfile, "模型性能档位不能为空");
        if (modelPolicy != ModelPolicy.ECONOMY_ONLY
                || selectedProfile.modelTier() != GenerationPerformanceProfile.ModelTier.QUALITY) {
            return selectedProfile;
        }
        return new GenerationPerformanceProfile(
                GenerationPerformanceProfile.ModelTier.BALANCED,
                false,
                selectedProfile.maxToolInvocations(),
                "应用 ECONOMY_ONLY 策略将质量档限制为平衡档"
        );
    }

    public enum ModelPolicy {
        PLATFORM_DEFAULT,
        ECONOMY_ONLY;

        public static ModelPolicy fromDatabase(String value) {
            return parse(ModelPolicy.class, value, "模型策略");
        }
    }

    public enum DependencyMutationPolicy {
        ALLOW,
        DENY;

        public static DependencyMutationPolicy fromDatabase(String value) {
            return parse(DependencyMutationPolicy.class, value, "依赖修改策略");
        }
    }

    public enum DependencyNetworkPolicy {
        TRUSTED_REGISTRY_ONLY,
        DENY;

        public static DependencyNetworkPolicy fromDatabase(String value) {
            return parse(DependencyNetworkPolicy.class, value, "依赖网络策略");
        }
    }

    public enum DangerousToolPolicy {
        REQUIRE_APPROVAL,
        DENY;

        public static DangerousToolPolicy fromDatabase(String value) {
            return parse(DangerousToolPolicy.class, value, "危险工具策略");
        }
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + "不能为空");
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(label + "不合法", invalid);
        }
    }
}
