package com.rush.rushaicodemother.orchestration.patch;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Applies a validated patch batch and restores all prior file states on failure. */
@Component
@RequiredArgsConstructor
public class PatchOperationExecutor {

    private final PatchWorkspaceFileService workspaceFileService;
    private final PatchStructuredContentService structuredContentService;
    private final PatchBatchRollbackService rollbackService;

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
            applyOperation(operation);
            appliedFiles.add(operation.action() + ":" + operation.relativePath());
        }
        return appliedFiles;
    }

    private void applyOperation(ValidatedPatchOperation operation) throws IOException {
        PatchOperation patchOperation = operation.operation();
        switch (operation.action()) {
            case PatchOperation.ACTION_ADD ->
                    workspaceFileService.writeNewUtf8(operation.target(), patchOperation.content());
            case PatchOperation.ACTION_MODIFY ->
                    workspaceFileService.overwriteUtf8(operation.target(), patchOperation.content());
            case PatchOperation.ACTION_REPLACE -> overwriteReplacement(operation, false);
            case PatchOperation.ACTION_INSERT_BEFORE_MARKER,
                 PatchOperation.ACTION_INSERT_AFTER_MARKER -> overwriteReplacement(operation, true);
            case PatchOperation.ACTION_GO_ADD_IMPORT,
                 PatchOperation.ACTION_GO_APPEND_TO_FUNCTION,
                 PatchOperation.ACTION_GO_ADD_STRUCT_FIELDS,
                 PatchOperation.ACTION_APPEND_SQL_MIGRATION -> overwriteStructured(operation);
            case PatchOperation.ACTION_DELETE -> workspaceFileService.delete(operation.target());
            default -> throw new IOException("unsupported_patch_action:" + operation.action());
        }
    }

    private void overwriteReplacement(ValidatedPatchOperation operation,
                                      boolean insertAroundMarker) throws IOException {
        PatchOperation patchOperation = operation.operation();
        String originalContent = workspaceFileService.readUtf8(operation.target());
        String replacement = patchOperation.newContent();
        if (insertAroundMarker) {
            replacement = PatchOperation.ACTION_INSERT_BEFORE_MARKER.equals(operation.action())
                    ? patchOperation.newContent() + System.lineSeparator() + patchOperation.oldContent()
                    : patchOperation.oldContent() + System.lineSeparator() + patchOperation.newContent();
        }
        workspaceFileService.overwriteUtf8(
                operation.target(),
                originalContent.replace(patchOperation.oldContent(), replacement)
        );
    }

    private void overwriteStructured(ValidatedPatchOperation operation) throws IOException {
        String originalContent = workspaceFileService.readUtf8(operation.target());
        String updatedContent = structuredContentService.transform(
                operation.action(), originalContent, operation.operation());
        if (!originalContent.equals(updatedContent)) {
            workspaceFileService.overwriteUtf8(operation.target(), updatedContent);
        }
    }
}
