package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 工程智能体类型、提示目录键和默认提示资源之间的唯一代码绑定。 */
public record GenerationAgentPromptBinding(
        CodeGenTypeEnum codeGenType,
        String promptKey,
        String promptResource
) {

    private static final List<GenerationAgentPromptBinding> BINDINGS = List.of(
            new GenerationAgentPromptBinding(
                    CodeGenTypeEnum.VUE_PROJECT,
                    "codegen-vue-project",
                    "prompt/codegen-vue-project-system-prompt.txt"),
            new GenerationAgentPromptBinding(
                    CodeGenTypeEnum.BACKEND_PROJECT,
                    "codegen-backend-project",
                    "prompt/codegen-backend-project-system-prompt.txt"),
            new GenerationAgentPromptBinding(
                    CodeGenTypeEnum.FULL_STACK_PROJECT,
                    "codegen-full-stack-project",
                    "prompt/codegen-full-stack-project-system-prompt.txt")
    );
    private static final Map<CodeGenTypeEnum, GenerationAgentPromptBinding> BY_CODE_GEN_TYPE =
            indexBindings();

    public GenerationAgentPromptBinding {
        if (codeGenType == null) {
            throw new IllegalArgumentException("工程智能体类型不能为空");
        }
        if (promptKey == null || promptKey.isBlank()
                || promptResource == null || promptResource.isBlank()) {
            throw new IllegalArgumentException("工程智能体提示绑定不完整");
        }
    }

    public static GenerationAgentPromptBinding forCodeGenType(CodeGenTypeEnum codeGenType) {
        GenerationAgentPromptBinding binding = BY_CODE_GEN_TYPE.get(codeGenType);
        if (binding == null) {
            throw new IllegalArgumentException("工程智能体类型不受支持");
        }
        return binding;
    }

    public static List<GenerationAgentPromptBinding> all() {
        return BINDINGS;
    }

    private static Map<CodeGenTypeEnum, GenerationAgentPromptBinding> indexBindings() {
        EnumMap<CodeGenTypeEnum, GenerationAgentPromptBinding> indexed =
                new EnumMap<>(CodeGenTypeEnum.class);
        for (GenerationAgentPromptBinding binding : BINDINGS) {
            if (indexed.putIfAbsent(binding.codeGenType(), binding) != null) {
                throw new IllegalStateException("工程智能体类型存在重复提示绑定");
            }
        }
        return Map.copyOf(indexed);
    }
}
