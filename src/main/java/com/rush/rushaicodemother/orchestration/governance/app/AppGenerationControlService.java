package com.rush.rushaicodemother.orchestration.governance.app;

import com.rush.rushaicodemother.application.app.AppAccessPolicy;
import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.orchestration.runtime.task.GenerationTaskControlService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

/** 应用管理员生成控制面，统一负责授权、乐观锁和急停取消。 */
@Service
public class AppGenerationControlService {

    private static final String ACCESS_DENIED_MESSAGE = "仅应用所属租户管理员可管理生成控制";

    private final AppGenerationControlRepository repository;
    private final AppAccessPolicy appAccessPolicy;
    private final GenerationTaskControlService taskControlService;
    private final Clock clock;

    @Autowired
    public AppGenerationControlService(AppGenerationControlRepository repository,
                                       AppAccessPolicy appAccessPolicy,
                                       GenerationTaskControlService taskControlService) {
        this(repository, appAccessPolicy, taskControlService, Clock.systemDefaultZone());
    }

    AppGenerationControlService(AppGenerationControlRepository repository,
                                AppAccessPolicy appAccessPolicy,
                                GenerationTaskControlService taskControlService,
                                Clock clock) {
        this.repository = Objects.requireNonNull(repository, "应用生成控制仓储不能为空");
        this.appAccessPolicy = Objects.requireNonNull(appAccessPolicy, "应用访问策略不能为空");
        this.taskControlService = Objects.requireNonNull(taskControlService, "任务控制服务不能为空");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空");
    }

    /** 读取应用控制快照；授权在策略读取前完成。 */
    @Transactional(readOnly = true)
    public AppGenerationControlPolicy get(Long appId, User actor) {
        App app = requireActiveApplication(repository.findActiveApplication(appId));
        appAccessPolicy.requireOwnerOrAdmin(app, actor, ACCESS_DENIED_MESSAGE);
        return repository.get(appId);
    }

    /** 以完整替换和乐观版本方式更新控制策略。 */
    @Transactional(rollbackFor = Exception.class)
    public AppGenerationControlPolicy update(Long appId,
                                             AppGenerationControlUpdateCommand command,
                                             User actor) {
        Objects.requireNonNull(command, "应用生成控制更新命令不能为空");
        App app = requireActiveApplication(repository.lockActiveApplication(appId));
        appAccessPolicy.requireOwnerOrAdmin(app, actor, ACCESS_DENIED_MESSAGE);
        AppGenerationControlPolicy current = repository.get(appId);
        if (current.version() != command.expectedVersion()) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "应用生成控制版本已更新，请刷新后重试");
        }
        if (actor == null || actor.getId() == null || actor.getId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        }

        AppGenerationControlPolicy updated = new AppGenerationControlPolicy(
                appId,
                Math.addExact(current.version(), 1L),
                command.generationPaused(),
                command.emergencyStopped(),
                command.maxConcurrentTasks(),
                command.modelPolicy(),
                command.dependencyMutationPolicy(),
                command.dependencyNetworkPolicy(),
                command.dangerousToolPolicy(),
                command.monthlyCreditLimit(),
                actor.getId(),
                clock.instant()
        );
        boolean persisted = current.version() == 0
                ? repository.insert(updated)
                : repository.update(updated, current.version());
        if (!persisted) {
            throw new BusinessException(ErrorCode.CONFLICT_ERROR, "应用生成控制版本已更新，请刷新后重试");
        }
        if (updated.emergencyStopped()) {
            taskControlService.emergencyStopActiveForApp(appId);
        }
        return updated;
    }

    private App requireActiveApplication(App app) {
        if (app == null || app.getId() == null || app.getId() <= 0
                || app.getTenantId() == null || app.getTenantId() <= 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        }
        return app;
    }
}
