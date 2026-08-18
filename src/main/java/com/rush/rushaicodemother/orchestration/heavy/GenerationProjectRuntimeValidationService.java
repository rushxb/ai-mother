package com.rush.rushaicodemother.orchestration.heavy;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.verification.runtime.ProjectRuntimeValidationResult;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 通过工程类型 adapter 注册表执行真实运行时验证。 */
@Service
public class GenerationProjectRuntimeValidationService {

    private final Map<CodeGenTypeEnum, GenerationProjectRuntimeValidationAdapter> adaptersByType;

    /** 构建不可变注册表，缺失或重复声明在应用启动阶段失败。 */
    public GenerationProjectRuntimeValidationService(
            List<GenerationProjectRuntimeValidationAdapter> adapters
    ) {
        this.adaptersByType = GenerationProjectAdapterRegistry.register(adapters, "运行时验证");
    }

    /** 执行请求工程类型对应的运行时验证，并强制非空结果契约。 */
    public ProjectRuntimeValidationResult validate(
            GenerationProjectRuntimeValidationRequest request
    ) {
        Objects.requireNonNull(request, "工程运行时验证请求不能为空");
        CodeGenTypeEnum codeGenType = request.workspace().codeGenType();
        GenerationProjectRuntimeValidationAdapter adapter = adaptersByType.get(codeGenType);
        if (adapter == null) {
            throw new IllegalArgumentException(
                    "当前项目类型不支持运行时验证: " + codeGenType.getValue());
        }
        ProjectRuntimeValidationResult result = adapter.validateRuntime(request);
        if (result == null) {
            throw new IllegalStateException(
                    "工程运行时验证适配器返回空结果: " + codeGenType.getValue());
        }
        return result;
    }
}
