package com.rush.rushaicodemother.orchestration.runtime.execution;

/**
 * 用户可见预览的成熟度等级。
 *
 * <p>区分这两级是为了让「用户多久能看到东西」与「产物是否已通过验证」成为两个独立事实：
 * 此前两者被绑在同一个发布点上，导致 TTP（首个可预览耗时）恒等于任务总时长，指标失去诊断意义。</p>
 */
public enum GenerationPreviewLevel {

    /**
     * 暂定预览：工作区已可渲染，但尚未通过构建/运行时验证。
     *
     * <p>仅用于让用户尽早看到结果，<b>不构成完成证据、不触发计费、不写任务终态</b>。
     * 对应产物属于未发布的执行纪元，随时可能被纠偏重试覆盖或随任务失败作废。</p>
     */
    PROVISIONAL("time_to_provisional_preview"),

    /**
     * 已验证预览：产物已通过验证并原子发布为用户可见版本。
     *
     * <p>这是交付语义上的「可用」，与完成证据和计费在同一条收口链路上。</p>
     */
    VERIFIED("time_to_first_preview");

    /**
     * 该等级对应的遥测 span 阶段名。
     *
     * <p>由枚举自带而非由调用方分支判断：新增等级时只需在此登记，
     * 记录侧无需再改。已验证等级刻意沿用历史名 {@code time_to_first_preview} ——
     * 它是发布门禁与基准执行器的既有读取契约，改名会让历史数据与门禁失配。</p>
     */
    private final String spanStage;

    GenerationPreviewLevel(String spanStage) {
        this.spanStage = spanStage;
    }

    public String spanStage() {
        return spanStage;
    }

    /** 事件载荷与指标标签使用的稳定小写字面值，避免前端依赖枚举名大小写。 */
    public String wireValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
