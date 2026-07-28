package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditOperation;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.patch.PatchOperation;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * 智能体编辑Planning服务实现。
 */
@Service
public class AgentEditPlanningService {

    /**
 * 返回计划。
 *
 * @param readResult {@code readResult} 对应的调用参数
 * @param understanding {@code understanding} 对应的调用参数
 * @param codeGenType 代码生成类型
 * @param editResult 编辑结果
 * @param patchOperations 补丁操作
 * @return 智能体编辑{@code Planning}
 */
    public EditChangePlan plan(AgentEditReadResult readResult,
                               AgentEditUnderstanding understanding,
                               CodeGenTypeEnum codeGenType,
                               EditResult editResult,
                               List<PatchOperation> patchOperations) {
        LinkedHashSet<String> modifyFiles = new LinkedHashSet<>();
        LinkedHashSet<String> addFiles = new LinkedHashSet<>();
        LinkedHashSet<String> deleteFiles = new LinkedHashSet<>();
        if (patchOperations != null) {
            for (PatchOperation operation : patchOperations) {
                String path = normalizePath(operation.relativePath());
                if (StrUtil.isBlank(path)) {
                    continue;
                }
                switch (operation.action()) {
                    case PatchOperation.ACTION_ADD -> addFiles.add(path);
                    case PatchOperation.ACTION_DELETE -> deleteFiles.add(path);
                    default -> modifyFiles.add(path);
                }
            }
        }
        return new EditChangePlan(
                inferScope(modifyFiles.size() + addFiles.size() + deleteFiles.size(), codeGenType, readResult),
                modifyFiles.stream().toList(),
                addFiles.stream().toList(),
                deleteFiles.stream().toList(),
                List.of(
                        "Read: 定位并读取相关文件",
                        "Understand: 分析文件职责、风险和受影响模块",
                        "Edit: 按 ChangePlan 约束应用结构化补丁",
                        "Verify: 按项目类型执行同步验证",
                        "Repair: 验证失败时局部重试并按快照回滚"
                ),
                inferValidation(editResult, codeGenType, readResult, patchOperations),
                understanding == null ? "medium" : understanding.riskLevel(),
                "snapshot"
        );
    }

    /**
 * 将输入转换为{@code To}补丁操作。
 *
 * @param editResult 编辑结果
 * @return {@code To}补丁操作集合
 */
    public List<PatchOperation> convertToPatchOperations(EditResult editResult) {
        if (editResult == null || editResult.operations() == null) {
            return List.of();
        }
        return editResult.operations().stream()
                .map(this::convert)
                .filter(operation -> operation != null && StrUtil.isNotBlank(operation.relativePath()))
                .toList();
    }

    /** 将输入转换为智能体编辑{@code Planning}。 */
    private PatchOperation convert(EditOperation operation) {
        if (operation == null || StrUtil.isBlank(operation.action())) {
            return null;
        }
        String action = operation.action().trim().toLowerCase();
        String relativePath = normalizePath(operation.relativePath());
        return switch (action) {
            case PatchOperation.ACTION_REPLACE -> PatchOperation.replace(relativePath, operation.oldContent(), operation.newContent());
            case PatchOperation.ACTION_MODIFY -> PatchOperation.modify(relativePath, operation.content());
            case PatchOperation.ACTION_ADD -> PatchOperation.add(relativePath, operation.content());
            case PatchOperation.ACTION_DELETE -> PatchOperation.delete(relativePath);
            default -> null;
        };
    }

    /** 返回{@code infer}作用域。 */
    private String inferScope(int fileCount, CodeGenTypeEnum codeGenType, AgentEditReadResult readResult) {
        if (codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return "fullstack_patch";
        }
        if (fileCount > 1 || (readResult != null && !"low".equals(readResult.riskLevel()))) {
            return "cross_module_patch";
        }
        return "single_module_patch";
    }

    /** 返回{@code infer}校验。 */
    private String inferValidation(EditResult editResult,
                                   CodeGenTypeEnum codeGenType,
                                   AgentEditReadResult readResult,
                                   List<PatchOperation> patchOperations) {
        boolean aiRequiresBuild = editResult != null
                && editResult.validation() != null
                && editResult.validation().requiresBuild();
        if (aiRequiresBuild
                || codeGenType == CodeGenTypeEnum.FULL_STACK_PROJECT
                || codeGenType == CodeGenTypeEnum.BACKEND_PROJECT
                || (readResult != null && "high".equals(readResult.riskLevel()))) {
            return "build_validation";
        }
        int fileCount = patchOperations == null ? 0 : patchOperations.size();
        return fileCount <= 3 ? "validate_light" : "build_validation";
    }

    private String normalizePath(String path) {
        return StrUtil.blankToDefault(path, "").trim().replace('\\', '/');
    }
}
