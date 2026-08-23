package com.rush.rushaicodemother.orchestration.workspace;

import java.util.Arrays;

/** 用于协调文件系统发布与关系元数据的持久传奇状态。 */
public enum GenerationWorkspacePublicationJournalStatus {
    PREPARED("prepared"),
    FILESYSTEM_ACTIVATED("filesystem_activated"),
    COMMITTED("committed"),
    ROLLBACK_REQUIRED("rollback_required"),
    ROLLED_BACK("rolled_back"),
    SUPERSEDED("superseded");

    private final String value;

    GenerationWorkspacePublicationJournalStatus(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /**
     * 判断任务恢复是否必须等待发布对账完成。
     *
     * <p>{@code prepared} 也可能已经激活文件系统指针，只是进程在写回 journal 前退出，
     * 因此不能仅凭状态名称把它当作“尚未发布”并推进执行轮次。</p>
     */
    public boolean requiresReconciliation() {
        return this == PREPARED
                || this == FILESYSTEM_ACTIVATED
                || this == ROLLBACK_REQUIRED;
    }

    /**
 * 根据输入数据创建当前对象。
 *
 * @param value 待处理值
 * @return 生成工作区发布日志状态
 */
    public static GenerationWorkspacePublicationJournalStatus fromValue(String value) {
        return Arrays.stream(values())
                .filter(status -> status.value.equals(value))
                .findFirst()
                .orElse(null);
    }
}
