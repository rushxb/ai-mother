package com.rush.rushaicodemother.orchestration.create;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 创建补丁Merge服务实现。
 */
@Service
public class CreatePatchMergeService {

    public SlotPatchPlan merge(List<PatchOperation> operations) {
        if (operations == null || operations.isEmpty()) {
            return new SlotPatchPlan(List.of(), 0, 0);
        }
        Map<String, List<PatchOperation>> operationsByPath = new LinkedHashMap<>();
        for (PatchOperation operation : operations) {
            if (operation == null || StrUtil.isBlank(operation.relativePath())) {
                continue;
            }
            operationsByPath.computeIfAbsent(operation.relativePath(), ignored -> new ArrayList<>()).add(operation);
        }
        List<PatchOperation> merged = new ArrayList<>();
        for (List<PatchOperation> sameFileOperations : operationsByPath.values()) {
            merged.addAll(mergeSameFile(sameFileOperations));
        }
        return new SlotPatchPlan(merged, operations.size(), merged.size());
    }

    private List<PatchOperation> mergeSameFile(List<PatchOperation> operations) {
        if (operations.size() <= 1) {
            return operations;
        }
        List<PatchOperation> wholeFileWrites = operations.stream()
                .filter(this::isWholeFileWrite)
                .toList();
        if (wholeFileWrites.size() > 1) {
            throw new IllegalArgumentException("同一文件存在多个整文件写入: " + operations.getFirst().relativePath());
        }
        if (wholeFileWrites.size() == 1 && operations.size() > 1) {
            throw new IllegalArgumentException("同一文件整文件写入与局部 patch 冲突: " + operations.getFirst().relativePath());
        }
        List<PatchOperation> merged = new ArrayList<>();
        for (PatchOperation operation : operations) {
            if (PatchOperation.ACTION_GO_ADD_IMPORT.equals(operation.action())) {
                if (merged.stream().noneMatch(existing -> sameGoImport(existing, operation))) {
                    merged.add(operation);
                }
                continue;
            }
            if (PatchOperation.ACTION_APPEND_SQL_MIGRATION.equals(operation.action())) {
                merged.add(mergeSqlAppend(operation, merged));
                continue;
            }
            merged.add(operation);
        }
        return merged.stream().distinct().toList();
    }

    private boolean isWholeFileWrite(PatchOperation operation) {
        return PatchOperation.ACTION_ADD.equals(operation.action())
                || PatchOperation.ACTION_MODIFY.equals(operation.action());
    }

    private boolean sameGoImport(PatchOperation left, PatchOperation right) {
        return PatchOperation.ACTION_GO_ADD_IMPORT.equals(left.action())
                && left.relativePath().equals(right.relativePath())
                && StrUtil.equals(left.newContent(), right.newContent());
    }

    private PatchOperation mergeSqlAppend(PatchOperation operation, List<PatchOperation> merged) {
        List<PatchOperation> previousSqlAppends = merged.stream()
                .filter(existing -> PatchOperation.ACTION_APPEND_SQL_MIGRATION.equals(existing.action()))
                .filter(existing -> existing.relativePath().equals(operation.relativePath()))
                .toList();
        if (previousSqlAppends.isEmpty()) {
            return operation;
        }
        PatchOperation first = previousSqlAppends.getFirst();
        merged.remove(first);
        String content = StrUtil.blankToDefault(first.newContent(), "").stripTrailing()
                + System.lineSeparator()
                + StrUtil.blankToDefault(operation.newContent(), "").stripLeading();
        return PatchOperation.appendSqlMigration(operation.relativePath(), content);
    }
}
