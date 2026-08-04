package com.rush.rushaicodemother.orchestration;

/**
 * 生成任务在执行阶段需要具备的应用资源。
 *
 * <p>这里只保存可持久化的意图，不在请求线程执行资源变更。工作器在取得执行租约后
 * 根据该值对象幂等开通资源，从而保证幂等重放不会产生新的业务副作用。</p>
 */
public record GenerationResourceRequirements(boolean databaseRequired) {

    private static final GenerationResourceRequirements NONE =
            new GenerationResourceRequirements(false);

    /** 返回不需要额外资源的默认需求。 */
    public static GenerationResourceRequirements none() {
        return NONE;
    }

    /** 根据数据库需求创建资源需求。 */
    public static GenerationResourceRequirements ofDatabaseRequirement(boolean databaseRequired) {
        return databaseRequired ? new GenerationResourceRequirements(true) : NONE;
    }
}
