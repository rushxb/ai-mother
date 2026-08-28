package com.rush.rushaicodemother.model.dto.generation;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlPolicy;
import com.rush.rushaicodemother.orchestration.governance.app.AppGenerationControlUpdateCommand;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** 应用生成控制完整替换请求；月预算为 null 表示继承租户上限。 */
public record AppGenerationControlUpdateRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull Boolean generationPaused,
        @NotNull Boolean emergencyStopped,
        @NotNull @Min(1) @Max(1) Integer maxConcurrentTasks,
        @NotBlank String modelPolicy,
        @NotBlank String dependencyMutationPolicy,
        @NotBlank String dependencyNetworkPolicy,
        @NotBlank String dangerousToolPolicy,
        @PositiveOrZero Long monthlyCreditLimit
) {

    /** 将公共字符串合同转换为强类型领域命令，未知枚举失败关闭。 */
    public AppGenerationControlUpdateCommand toCommand() {
        try {
            return new AppGenerationControlUpdateCommand(
                    expectedVersion,
                    generationPaused,
                    emergencyStopped,
                    maxConcurrentTasks,
                    AppGenerationControlPolicy.ModelPolicy.fromDatabase(modelPolicy),
                    AppGenerationControlPolicy.DependencyMutationPolicy.fromDatabase(
                            dependencyMutationPolicy),
                    AppGenerationControlPolicy.DependencyNetworkPolicy.fromDatabase(
                            dependencyNetworkPolicy),
                    AppGenerationControlPolicy.DangerousToolPolicy.fromDatabase(
                            dangerousToolPolicy),
                    monthlyCreditLimit
            );
        } catch (IllegalArgumentException invalid) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "应用生成控制参数不合法", invalid);
        }
    }
}
