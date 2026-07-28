package com.rush.rushaicodemother.orchestration.runtime.agent;

import com.rush.rushaicodemother.ai.prompt.PromptSystemMessageTransformer;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.invocation.InvocationContext;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/** 解析工程智能体的默认提示，并应用提示目录的稳定版或金丝雀版本。 */
@Component
public class GenerationAgentPromptResolver {

    private final PromptSystemMessageTransformer promptTransformer;
    private final Map<CodeGenTypeEnum, String> defaultPrompts;

    public GenerationAgentPromptResolver(PromptSystemMessageTransformer promptTransformer,
                                         ResourceLoader resourceLoader) {
        this.promptTransformer = promptTransformer;
        EnumMap<CodeGenTypeEnum, String> prompts = new EnumMap<>(CodeGenTypeEnum.class);
        for (GenerationAgentPromptBinding binding : GenerationAgentPromptBinding.all()) {
            prompts.put(binding.codeGenType(), read(
                    resourceLoader, binding.promptResource()));
        }
        this.defaultPrompts = Map.copyOf(prompts);
    }

    public String resolve(CodeGenTypeEnum codeGenType,
                          InvocationContext invocationContext) {
        GenerationAgentPromptBinding binding =
                GenerationAgentPromptBinding.forCodeGenType(codeGenType);
        String defaultPrompt = defaultPrompts.get(codeGenType);
        if (defaultPrompt == null) {
            throw new IllegalArgumentException("工程智能体提示绑定不存在");
        }
        return promptTransformer.transform(
                binding.promptKey(), defaultPrompt, invocationContext);
    }

    private String read(ResourceLoader resourceLoader, String classpathResource) {
        Resource resource = resourceLoader.getResource("classpath:" + classpathResource);
        if (!resource.exists()) {
            throw new IllegalStateException("工程智能体默认提示资源不存在");
        }
        try (InputStream input = resource.getInputStream()) {
            String content = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (content.isBlank()) {
                throw new IllegalStateException("工程智能体默认提示不能为空");
            }
            return content;
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("工程智能体默认提示读取失败", failure);
        }
    }
}
