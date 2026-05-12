package com.yupi.yuaicodemother.orchestration.tool;

import com.yupi.yuaicodemother.model.enums.CodeGenTypeEnum;
import com.yupi.yuaicodemother.orchestration.artifact.ChangePlan;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class GenerationToolExecutionContextService {

    private final ConcurrentMap<Long, GenerationToolExecutionContext> contexts = new ConcurrentHashMap<>();

    public Optional<GenerationToolExecutionContext> getContext(Long appId) {
        if (appId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(contexts.get(appId));
    }

    public void bindContext(GenerationToolExecutionContext context) {
        if (context == null || context.appId() == null) {
            return;
        }
        contexts.put(context.appId(), context);
    }

    public void bindChangePlan(Long appId, String taskId, String generationMode, CodeGenTypeEnum codeGenType, ChangePlan changePlan, boolean allowUnplannedWrite, String reason) {
        bindContext(new GenerationToolExecutionContext(appId, taskId, generationMode, codeGenType, changePlan, allowUnplannedWrite, reason));
    }

    public void clearContext(Long appId) {
        if (appId != null) {
            contexts.remove(appId);
        }
    }
}
