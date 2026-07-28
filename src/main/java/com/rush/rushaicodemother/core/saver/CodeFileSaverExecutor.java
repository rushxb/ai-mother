package com.rush.rushaicodemother.core.saver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.workspace.GenerationWorkspace;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 将生成的代码结果路由到为其生成类型注册的单个保存器。 */
@Service
public class CodeFileSaverExecutor {

    private final Map<CodeGenTypeEnum, CodeFileSaverTemplate<?>> saversByType;

    /**
 * 创建代码文件{@code Saver}执行器实例并完成必要的依赖和初始状态设置。
 *
 * @param savers 待处理的 {@code savers} 集合
 */
    public CodeFileSaverExecutor(List<CodeFileSaverTemplate<?>> savers) {
        Objects.requireNonNull(savers, "savers must not be null");
        EnumMap<CodeGenTypeEnum, CodeFileSaverTemplate<?>> registeredSavers =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (CodeFileSaverTemplate<?> saver : savers) {
            if (saver == null) {
                throw new IllegalStateException("代码文件保存器列表不能包含 null");
            }
            CodeFileSaverTemplate<?> previous = registeredSavers.putIfAbsent(saver.codeGenType(), saver);
            if (previous != null) {
                throw new IllegalStateException("代码生成类型存在重复保存器: " + saver.codeGenType());
            }
        }
        this.saversByType = Map.copyOf(registeredSavers);
    }

    /** 执行为请求的生成类型注册的保存器。 */
    public File executeSaver(Object codeResult, CodeGenTypeEnum codeGenType, Long appId) {
        return executeSaver(codeResult, codeGenType, appId, null);
    }

    /** 针对明确选择的任务/纪元工作区执行保护程序。 */
    public File executeSaver(Object codeResult,
                             CodeGenTypeEnum codeGenType,
                             Long appId,
                             GenerationWorkspace workspace) {
        if (codeGenType == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "代码生成类型不能为空");
        }
        CodeFileSaverTemplate<?> saver = saversByType.get(codeGenType);
        if (saver == null) {
            throw new BusinessException(
                    ErrorCode.SYSTEM_ERROR,
                    "不支持的代码生成类型: " + codeGenType.getValue()
            );
        }
        return workspace == null
                ? saver.saveCode(codeResult, appId)
                : saver.saveCode(codeResult, appId, workspace);
    }
}
