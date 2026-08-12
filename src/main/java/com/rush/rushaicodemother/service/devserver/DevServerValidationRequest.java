package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.runtime.execution.GenerationExecutionFence;

/**
 * 一次 Dev Server 运行时验证的完整入参。
 *
 * <p>取代逐个参数重载：验证的可选维度已经有隔离栅栏、就绪回调、会话持有者三个，
 * 继续叠加重载会让调用点难以看出自己选中了哪一组语义。收敛成参数对象后，
 * 新增可选维度只需加字段与 wither，既有调用点不受影响 —— 这是开闭原则在入参上的落点。</p>
 *
 * @param taskId         生成任务 ID，用于取消/超时判定与日志关联
 * @param appId          应用 ID
 * @param userId         发起用户 ID，参与端口配额与租约归属
 * @param codeGenType    生成类型，决定项目定位方式
 * @param executionFence 执行隔离栅栏；非空表示以隔离执行工作区为 root 验证
 * @param onDevServerReady Dev Server 就绪回调，在错误采集窗口开始前触发一次
 * @param ownership      会话持有者语义，决定验证返回时是否停止本次创建的会话
 */
public record DevServerValidationRequest(
        String taskId,
        Long appId,
        Long userId,
        CodeGenTypeEnum codeGenType,
        GenerationExecutionFence executionFence,
        Runnable onDevServerReady,
        DevServerSessionOwnership ownership
) {

    /** 构造最小验证请求：无隔离栅栏、无就绪回调、调用方作用域持有。 */
    public static DevServerValidationRequest of(String taskId,
                                                Long appId,
                                                Long userId,
                                                CodeGenTypeEnum codeGenType) {
        return new DevServerValidationRequest(taskId, appId, userId, codeGenType,
                null, null, DevServerSessionOwnership.CALLER_SCOPED);
    }

    /** 归一化可选字段，使下游无需重复判空。 */
    public DevServerValidationRequest {
        onDevServerReady = onDevServerReady == null ? () -> { } : onDevServerReady;
        ownership = ownership == null ? DevServerSessionOwnership.CALLER_SCOPED : ownership;
    }

    /** 返回以给定执行栅栏做隔离验证的同值请求。 */
    public DevServerValidationRequest withExecutionFence(GenerationExecutionFence fence) {
        return new DevServerValidationRequest(taskId, appId, userId, codeGenType,
                fence, onDevServerReady, ownership);
    }

    /** 返回携带就绪回调的同值请求。 */
    public DevServerValidationRequest withReadyCallback(Runnable callback) {
        return new DevServerValidationRequest(taskId, appId, userId, codeGenType,
                executionFence, callback, ownership);
    }

    /**
     * 返回声明了任务作用域持有的同值请求。
     *
     * <p>以 wither 形式提供，让「谁把持有权交给了任务」在调用处一眼可见，
     * 而不是埋在一串位置参数里。</p>
     */
    public DevServerValidationRequest withTaskScopedOwnership() {
        return new DevServerValidationRequest(taskId, appId, userId, codeGenType,
                executionFence, onDevServerReady, DevServerSessionOwnership.TASK_SCOPED);
    }
}
