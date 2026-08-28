package com.rush.rushaicodemother.infrastructure.persistence.governance;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppGenerationControlMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.AppGenerationControlEntity;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.time.ZoneId;

/** 在 MySQL 中读写应用生成控制；畸形持久数据会失败关闭。 */
@Repository
@RequiredArgsConstructor
public class MyBatisAppGenerationControlRepository implements AppGenerationControlRepository {

    private final AppGenerationControlMapper mapper;
    private final ZoneId databaseZone = ZoneId.systemDefault();

    @Override
    public AppGenerationControlPolicy get(Long appId) {
        requireAppId(appId);
        AppGenerationControlEntity entity = mapper.selectByAppId(appId);
        if (entity == null) {
            return AppGenerationControlPolicy.defaults(appId);
        }
        try {
            return new AppGenerationControlPolicy(
                    entity.getAppId(),
                    requireLong(entity.getVersion(), "版本"),
                    requireFlag(entity.getGenerationPaused(), "暂停标记"),
                    requireFlag(entity.getEmergencyStopped(), "急停标记"),
                    requireInteger(entity.getMaxConcurrentTasks(), "并发上限"),
                    AppGenerationControlPolicy.ModelPolicy.fromDatabase(entity.getModelPolicy()),
                    AppGenerationControlPolicy.DependencyMutationPolicy.fromDatabase(
                            entity.getDependencyMutationPolicy()),
                    AppGenerationControlPolicy.DependencyNetworkPolicy.fromDatabase(
                            entity.getDependencyNetworkPolicy()),
                    AppGenerationControlPolicy.DangerousToolPolicy.fromDatabase(
                            entity.getDangerousToolPolicy()),
                    entity.getMonthlyCreditLimit(),
                    entity.getUpdatedBy(),
                    entity.getUpdateTime() == null
                            ? null
                            : entity.getUpdateTime().atZone(databaseZone).toInstant()
            );
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用生成控制数据不合法",
                    invalid
            );
        }
    }

    @Override
    public App findActiveApplication(Long appId) {
        requireAppId(appId);
        return mapper.selectActiveApplication(appId);
    }

    @Override
    public App lockActiveApplication(Long appId) {
        requireAppId(appId);
        return mapper.lockActiveApplication(appId);
    }

    @Override
    public boolean insert(AppGenerationControlPolicy policy) {
        return mapper.insert(toEntity(policy)) == 1;
    }

    @Override
    public boolean update(AppGenerationControlPolicy policy, long expectedVersion) {
        if (expectedVersion <= 0 || policy == null || policy.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("应用生成控制更新版本不合法");
        }
        return mapper.update(toEntity(policy), expectedVersion) == 1;
    }

    private AppGenerationControlEntity toEntity(AppGenerationControlPolicy policy) {
        if (policy == null || policy.version() <= 0 || policy.updatedAt() == null) {
            throw new IllegalArgumentException("待持久化应用生成控制不合法");
        }
        return AppGenerationControlEntity.builder()
                .appId(policy.appId())
                .generationPaused(policy.generationPaused() ? 1 : 0)
                .emergencyStopped(policy.emergencyStopped() ? 1 : 0)
                .maxConcurrentTasks(policy.maxConcurrentTasks())
                .modelPolicy(policy.modelPolicy().name())
                .dependencyMutationPolicy(policy.dependencyMutationPolicy().name())
                .dependencyNetworkPolicy(policy.dependencyNetworkPolicy().name())
                .dangerousToolPolicy(policy.dangerousToolPolicy().name())
                .monthlyCreditLimit(policy.monthlyCreditLimit())
                .version(policy.version())
                .updatedBy(policy.updatedBy())
                .createTime(policy.updatedAt().atZone(databaseZone).toLocalDateTime())
                .updateTime(policy.updatedAt().atZone(databaseZone).toLocalDateTime())
                .build();
    }

    private boolean requireFlag(Integer value, String field) {
        if (value == null || value < 0 || value > 1) {
            throw new IllegalArgumentException(field + "不合法");
        }
        return value == 1;
    }

    private int requireInteger(Integer value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private long requireLong(Long value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + "不能为空");
        }
        return value;
    }

    private void requireAppId(Long appId) {
        if (appId == null || appId <= 0) {
            throw new IllegalArgumentException("应用 ID 必须为正数");
        }
    }
}
