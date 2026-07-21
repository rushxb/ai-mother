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

/** Routes generated-code results to the single saver registered for their generation type. */
@Service
public class CodeFileSaverExecutor {

    private final Map<CodeGenTypeEnum, CodeFileSaverTemplate<?>> saversByType;

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

    /** Executes the saver registered for the requested generation type. */
    public File executeSaver(Object codeResult, CodeGenTypeEnum codeGenType, Long appId) {
        return executeSaver(codeResult, codeGenType, appId, null);
    }

    /** Executes a saver against an explicitly selected task/epoch workspace. */
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
