package com.rush.rushaicodemother.orchestration.patch;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** 应用经过验证的补丁批次并在失败时恢复所有先前的文件状态。 */
@Component
@RequiredArgsConstructor
public class PatchOperationExecutor {

    private final PatchWorkspaceFileService workspaceFileService;
    private final PatchStructuredContentService structuredContentService;
    private final PatchBatchRollbackService rollbackService;

    /**
     * 以批次原子性执行补丁操作。
     *
     * @param operations 已通过安全校验的补丁操作
     * @return 实际改变工作区的操作标签集合
     */
    public List<String> execute(List<ValidatedPatchOperation> operations) throws IOException {
        PatchRollbackSnapshot snapshot = rollbackService.capture(
                operations.stream().map(ValidatedPatchOperation::target).toList());
        try {
            return applyOperations(operations);
        } catch (Exception patchFailure) {
            try {
                rollbackService.restore(snapshot);
            } catch (IOException rollbackFailure) {
                patchFailure.addSuppressed(rollbackFailure);
            }
            throw new PatchBatchExecutionException(patchFailure);
        }
    }

    private List<String> applyOperations(List<ValidatedPatchOperation> operations) throws IOException {
        List<String> appliedFiles = new ArrayList<>();
        for (ValidatedPatchOperation operation : operations) {
            if (applyOperation(operation)) {
                appliedFiles.add(operation.action() + ":" + operation.relativePath());
            }
        }
        return appliedFiles;
    }

    /**
     * 应用单个操作，并以文件实际内容是否发生变化作为成功变更事实。
     * 工具调用成功不等于工作区发生变更，幂等重试不能污染 Agent 完成证据。
     */
    private boolean applyOperation(ValidatedPatchOperation operation) throws IOException {
        PatchOperation patchOperation = operation.operation();
        return switch (operation.action()) {
            case PatchOperation.ACTION_ADD -> {
                workspaceFileService.writeNewUtf8(operation.target(), patchOperation.content());
                yield true;
            }
            case PatchOperation.ACTION_MODIFY -> overwriteIfChanged(
                    operation.target(),
                    workspaceFileService.readUtf8(operation.target()),
                    patchOperation.content()
            );
            case PatchOperation.ACTION_REPLACE -> overwriteReplacement(operation, false);
            case PatchOperation.ACTION_INSERT_BEFORE_MARKER,
                 PatchOperation.ACTION_INSERT_AFTER_MARKER -> overwriteReplacement(operation, true);
            case PatchOperation.ACTION_GO_ADD_IMPORT,
                 PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
                 PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
                 PatchOperation.ACTION_APPEND_SQL_MIGRATION -> overwriteStructured(operation);
            case PatchOperation.ACTION_DELETE -> {
                workspaceFileService.delete(operation.target());
                yield true;
            }
            default -> throw new IOException("unsupported_patch_action:" + operation.action());
        };
    }

    /** 覆盖写入替换内容。 */
    private boolean overwriteReplacement(ValidatedPatchOperation operation,
                                         boolean insertAroundMarker) throws IOException {
        PatchOperation patchOperation = operation.operation();
        String originalContent = workspaceFileService.readUtf8(operation.target());
        String replacement = patchOperation.newContent();
        if (insertAroundMarker) {
            replacement = PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(operation.action())
                    ? patchOperation.newContent() + System.lineSeparator() + patchOperation.oldContent()
                    : patchOperation.oldContent() + System.lineSeparator() + patchOperation.newContent();
        }
        return overwriteIfChanged(
                operation.target(),
                originalContent,
                originalContent.replace(patchOperation.oldContent(), replacement)
        );
    }

    private boolean overwriteStructured(ValidatedPatchOperation operation) throws IOException {
        String originalContent = workspaceFileService.readUtf8(operation.target());
        String updatedContent = structuredContentService.transform(
                operation.action(), originalContent, operation.operation());
        return overwriteIfChanged(operation.target(), originalContent, updatedContent);
    }

    /** 仅在内容真正变化时写盘，返回值供上层形成可信的 mutation 计数。 */
    private boolean overwriteIfChanged(PatchWorkspaceTarget target,
                                       String originalContent,
                                       String updatedContent) throws IOException {
        if (originalContent.equals(updatedContent)) {
            return false;
        }
        workspaceFileService.overwriteUtf8(target, updatedContent);
        return true;
    }
}
