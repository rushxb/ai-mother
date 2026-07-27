package com.rush.rushaicodemother.orchestration.tool;

import java.time.Instant;
import java.util.Optional;
import java.util.List;

/** 一次性破坏性工具批准的持久事实来源。 */
public interface ToolApprovalRepository {

    ToolApprovalRecord createPending(ToolApprovalRecord approval);

    Optional<ToolApprovalRecord> find(String taskId,
                                      DestructiveToolAction action,
                                      String approvalId);

    ToolApprovalRecord attachInvocationCheckpoint(String taskId,
                                                  DestructiveToolAction action,
                                                  String approvalId,
                                                  ToolInvocationCheckpoint checkpoint);

    boolean approve(String taskId,
                    DestructiveToolAction action,
                    String approvalId,
                    Long decidedBy,
                    Instant decidedAt);

    boolean reject(String taskId,
                   DestructiveToolAction action,
                   String approvalId,
                   Long decidedBy,
                   Instant decidedAt);

    boolean beginExecution(String taskId,
                           DestructiveToolAction action,
                           String approvalId,
                           String toolRequestId,
                           Instant executionStartedAt,
                           int maxAttempts);

    boolean completeExecution(String taskId,
                              DestructiveToolAction action,
                              String approvalId,
                              String toolRequestId,
                              ToolExecutionOutcome outcome,
                              Instant consumedAt);

    Optional<ToolApprovalRecord> findRecoverableExecution(String taskId);

    boolean resetStaleExecution(String taskId,
                                DestructiveToolAction action,
                                String approvalId,
                                long expectedVersion);

    int expireBefore(Instant now, int limit);

    List<ToolApprovalRecord> findWaitingContinuations(int limit);
}
