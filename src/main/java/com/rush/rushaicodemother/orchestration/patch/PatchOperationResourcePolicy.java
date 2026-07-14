package com.rush.rushaicodemother.orchestration.patch;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.config.PatchExecutionProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** Rejects patch batches that exceed bounded in-memory and file payload budgets. */
@Component
@RequiredArgsConstructor
public class PatchOperationResourcePolicy {

    private final PatchExecutionProperties properties;

    public List<String> validate(List<PatchOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return List.of();
        }
        if (operations.size() > properties.getMaxOperations()) {
            return List.of("batch:operation_limit_exceeded");
        }

        List<String> blockers = new ArrayList<>();
        long totalContentChars = 0;
        for (PatchOperation operation : operations) {
            long operationContentChars = contentChars(operation);
            if (operationContentChars > properties.getMaxOperationContentChars()) {
                blockers.add(operationLabel(operation) + ":operation_content_limit_exceeded");
            }
            totalContentChars += operationContentChars;
            if (totalContentChars > properties.getMaxTotalContentChars()) {
                blockers.add("batch:total_content_limit_exceeded");
                break;
            }
        }
        return List.copyOf(blockers);
    }

    private long contentChars(PatchOperation operation) {
        if (operation == null) {
            return 0;
        }
        return length(operation.content()) + length(operation.oldContent()) + length(operation.newContent());
    }

    private int length(String value) {
        return value == null ? 0 : value.length();
    }

    private String operationLabel(PatchOperation operation) {
        if (operation == null) {
            return "unknown:";
        }
        String action = StrUtil.blankToDefault(operation.action(), "unknown");
        String path = StrUtil.blankToDefault(operation.relativePath(), "").replace('\\', '/');
        return action + ":" + path;
    }
}
