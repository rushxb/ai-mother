ALTER TABLE generation_tool_approval
    ADD COLUMN requestExecutionEpoch bigint default 0 not null
        comment 'approval request execution epoch; 0 means legacy unbound'
        AFTER taskId;

-- 仅等待审批中的任务仍能证明请求纪元；其他历史记录保持 0 并由运行时失败关闭。
UPDATE generation_tool_approval approval
    INNER JOIN generation_task task ON task.taskId = approval.taskId
SET approval.requestExecutionEpoch = task.executionEpoch
WHERE approval.requestExecutionEpoch = 0
  AND task.status = 'waiting_approval'
  AND task.executionEpoch > 0
  AND task.isDelete = 0;

ALTER TABLE generation_tool_approval
    DROP INDEX uk_task_approval,
    DROP INDEX uk_task_tool_request,
    ADD UNIQUE KEY uk_task_epoch_approval (taskId, requestExecutionEpoch, approvalId),
    ADD UNIQUE KEY uk_task_epoch_tool_request (taskId, requestExecutionEpoch, toolRequestId),
    ADD INDEX idx_approval_task_epoch (taskId, requestExecutionEpoch, status, id),
    ADD CONSTRAINT chk_generation_tool_approval_request_epoch
        CHECK (requestExecutionEpoch >= 0);
