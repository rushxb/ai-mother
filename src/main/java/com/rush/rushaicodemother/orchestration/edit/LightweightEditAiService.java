package com.rush.rushaicodemother.orchestration.edit;

import cn.hutool.core.util.StrUtil;
import com.rush.rushaicodemother.ai.model.EditResult;
import com.rush.rushaicodemother.orchestration.artifact.PatchApplyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Owns model calls and repair prompts used by lightweight editing. */
@Service
@RequiredArgsConstructor
public class LightweightEditAiService {

    private final GenerationEditModelInvoker modelInvoker;

    public EditResult generate(String userMessage, String projectContext) {
        return modelInvoker.invokeLegacy(userMessage, projectContext);
    }

    public EditResult generateManaged(String taskId, String userMessage, String projectContext) {
        return modelInvoker.invokeManaged(taskId, "initial", userMessage, projectContext);
    }

    public EditResult retryAfterPatchRejection(String userMessage,
                                               String projectContext,
                                               PatchApplyResult applyResult,
                                               String diagnostic) {
        String retryMessage = """
                %s

                上一次补丁被本地校验拒绝，请重新审视项目上下文后只返回可应用的 JSON 编辑操作。
                拒绝原因: %s
                拒绝操作: %s

                约束:
                1. replace.oldContent 必须逐字复制自项目上下文中的真实文件内容。
                2. 如果无法稳定精确替换局部片段，改用 modify 覆盖完整文件，但只能覆盖确实需要修改的文件。
                3. 不要猜测未提供内容的文件结构。
                4. 如果拒绝操作包含 undeclared_bare_import，禁止继续 import 该包，也不要修改 package.json；改用项目已声明依赖、已有组件、CSS、Unicode 字符或内联 SVG 实现。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                applyResult == null ? "" : StrUtil.blankToDefault(applyResult.reason(), ""),
                StrUtil.blankToDefault(diagnostic, "")
        );
        return modelInvoker.invokeLegacy(retryMessage, projectContext);
    }

    public EditResult retryAfterPatchRejection(String taskId,
                                               String userMessage,
                                               String projectContext,
                                               PatchApplyResult applyResult,
                                               String diagnostic) {
        return modelInvoker.invokeManaged(
                taskId, "patch_retry", buildPatchRetryMessage(userMessage, applyResult, diagnostic), projectContext);
    }

    public EditResult retryAfterValidationFailure(
            String userMessage,
            String projectContext,
            BackgroundValidationService.ValidationResult validationResult) {
        String retryMessage = """
                %s

                上一次修复补丁已应用，但修复后验证仍未通过。请基于下方验证失败信息做一次最小范围二次修复，只返回 JSON 编辑操作。

                验证失败信息:
                %s

                约束:
                1. 不要重复应用已经完成的修改。
                2. 优先修复验证日志中指向的文件、变量、import 或导出。
                3. 对 SyntaxError / already declared，必须检查同一作用域内 import、const、let、function、defineProps、解构声明是否重复。
                4. 如果无法确定，读取上下文中同名标识符出现最多的文件并做最小修改，不要整站重写。
                5. 不要新增项目 package.json 未声明的第三方依赖 import；如果需要图标或工具函数，优先复用现有依赖或用原生代码实现。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                validationResult == null ? "" : StrUtil.blankToDefault(validationResult.message(), "")
        );
        return modelInvoker.invokeLegacy(retryMessage, projectContext);
    }

    public EditResult retryAfterValidationFailureManaged(
            String taskId,
            String userMessage,
            String projectContext,
            BackgroundValidationService.ValidationResult validationResult) {
        return modelInvoker.invokeManaged(
                taskId, "validation_retry",
                buildValidationRetryMessage(userMessage, validationResult), projectContext);
    }

    private String buildPatchRetryMessage(String userMessage,
                                          PatchApplyResult applyResult,
                                          String diagnostic) {
        return """
                %s

                上一次补丁被本地校验拒绝，请重新审视项目上下文后只返回可应用的 JSON 编辑操作。
                拒绝原因: %s
                拒绝操作: %s

                约束:
                1. replace.oldContent 必须逐字复制自项目上下文中的真实文件内容。
                2. 如果无法稳定精确替换局部片段，改用 modify 覆盖完整文件，但只能覆盖确实需要修改的文件。
                3. 不要猜测未提供内容的文件结构。
                4. 如果拒绝操作包含 undeclared_bare_import，禁止继续引入该包，也不要修改 package.json。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                applyResult == null ? "" : StrUtil.blankToDefault(applyResult.reason(), ""),
                StrUtil.blankToDefault(diagnostic, "")
        );
    }

    private String buildValidationRetryMessage(
            String userMessage,
            BackgroundValidationService.ValidationResult validationResult) {
        return """
                %s

                上一次修改已应用，但修改后验证仍未通过。请基于下方验证失败信息做一次最小范围二次修复，只返回 JSON 编辑操作。
                验证失败信息:
                %s

                约束:
                1. 不要重复应用已经完成的修改。
                2. 优先修复验证日志中指向的文件、变量、import 或导出。
                3. 对 SyntaxError / already declared，必须检查同一作用域内 import、const、let、function、defineProps、解构声明是否重复。
                4. 不要新增项目 package.json 未声明的第三方 import。
                """.formatted(
                StrUtil.blankToDefault(userMessage, ""),
                validationResult == null ? "" : StrUtil.blankToDefault(validationResult.message(), "")
        );
    }
}
