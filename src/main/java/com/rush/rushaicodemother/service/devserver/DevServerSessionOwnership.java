package com.rush.rushaicodemother.service.devserver;

/**
 * Dev Server 会话的持有者语义：决定谁、在什么时点停止会话。
 *
 * <p>取代此前 {@link DevServerStartResult#startedByCaller()} 单独承担的二值判断。
 * 该布尔值只说明「会话是否由本次调用创建」，无法表达「创建者是否愿意继续持有」——
 * 暂定预览恰恰需要后者：验证方法创建了会话，但必须把停止责任交给发布收口，
 * 否则错误采集窗口一结束（约 5 秒）预览地址就失效，用户来不及点开。</p>
 *
 * <p>用枚举而非布尔值，是为了让后续新增持有者（例如用户手动预览会话）不必再改动
 * {@link DevServerStartOptions} 的签名与既有调用点。</p>
 */
public enum DevServerSessionOwnership {

    /**
     * 调用方作用域持有：方法返回即停止，由创建者在 {@code finally} 中收口。
     *
     * <p>这是默认语义，与本枚举引入前的行为完全一致：BUILD 门禁、后台校验、
     * 基准运行时评估等一次性用途都应保持该语义，避免会话在无人负责的情况下滞留。</p>
     */
    CALLER_SCOPED,

    /**
     * 生成任务持有：验证返回后**不**停止，由发布收口显式停止。
     *
     * <p>停止点必须在工作区发布之前 —— 发布会把执行工作区目录整体移走，而进程仍持有
     * 旧 inode，届时预览既不报错也不更新，用户看到的是一份无声的过期内容。</p>
     *
     * <p>任务失败时不存在显式停止点，由 {@code DevServerManager} 的心跳回收兜底：
     * 执行工作区被丢弃后目录消失，下一轮维护即以 {@code workspace_directory_missing}
     * 回收该会话。刻意不新增第二条清理链路，因为每一处显式停止都是一个可能被漏掉的分支。</p>
     */
    TASK_SCOPED;

    /** 会话是否应在创建者的方法作用域结束时立即停止。 */
    public boolean stopsWithCaller() {
        return this == CALLER_SCOPED;
    }
}
