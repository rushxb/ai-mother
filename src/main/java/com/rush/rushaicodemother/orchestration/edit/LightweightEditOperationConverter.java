package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditOperation;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 将AI编辑操作转换为受限的补丁操作合同。 */
@Slf4j
@Component
public class LightweightEditOperationConverter {

    private static final Set<String> PROTECTED_FILE_NAME_PREFIXES = Set.of(
            "package.json", "vite.config", "go.mod", "dockerfile", "tsconfig"
    );

    public List<PatchOperation> convert(List<EditOperation> editOperations) {
        if (editOperations == null || editOperations.isEmpty()) {
            return List.of();
        }
        List<PatchOperation> patchOperations = new ArrayList<>();
        for (EditOperation editOperation : editOperations) {
            convertOperation(editOperation, patchOperations);
        }
        return List.copyOf(patchOperations);
    }

    private void convertOperation(EditOperation editOperation,
                                  List<PatchOperation> patchOperations) {
        if (editOperation == null || StrUtil.isBlank(editOperation.action())) {
            return;
        }
        String relativePath = normalizeRelativePath(editOperation.relativePath());
        if (StrUtil.isBlank(relativePath)) {
            return;
        }
        if (isProtectedFile(relativePath)) {
            log.warn("Skip lightweight edit operation for protected file: {}", relativePath);
            return;
        }

        String action = editOperation.action().trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "replace" -> addReplace(editOperation, relativePath, patchOperations);
            case "modify" -> addFullContentOperation(editOperation.content(), relativePath, true, patchOperations);
            case "add" -> addFullContentOperation(editOperation.content(), relativePath, false, patchOperations);
            default -> log.warn("Unsupported lightweight edit operation: {}", action);
        }
    }

    private void addReplace(EditOperation editOperation,
                            String relativePath,
                            List<PatchOperation> patchOperations) {
        if (StrUtil.isNotBlank(editOperation.oldContent()) && editOperation.newContent() != null) {
            patchOperations.add(PatchOperation.replace(
                    relativePath, editOperation.oldContent(), editOperation.newContent()));
        }
    }

    private void addFullContentOperation(String content,
                                         String relativePath,
                                         boolean modify,
                                         List<PatchOperation> patchOperations) {
        if (StrUtil.isBlank(content)) {
            return;
        }
        patchOperations.add(modify
                ? PatchOperation.modify(relativePath, content)
                : PatchOperation.add(relativePath, content));
    }

    private String normalizeRelativePath(String relativePath) {
        return StrUtil.blankToDefault(relativePath, "").trim().replace('\\', '/');
    }

    private boolean isProtectedFile(String relativePath) {
        int lastSeparator = relativePath.lastIndexOf('/');
        String fileName = lastSeparator >= 0 ? relativePath.substring(lastSeparator + 1) : relativePath;
        String normalizedFileName = fileName.trim().toLowerCase(Locale.ROOT);
        return PROTECTED_FILE_NAME_PREFIXES.stream().anyMatch(normalizedFileName::startsWith);
    }
}
