package com.rush.rushaicodemother.orchestration;

import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.service.GenerationTraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class GenerationAppStateService {

    private final AppMapper appMapper;
    private final GenerationTraceService generationTraceService;

    public void markGenerationStarted(Long appId, String generatingStage) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(1);
        updateApp.setGeneratingMessage("");
        updateApp.setGeneratingStage(generatingStage);
        appMapper.update(updateApp);
    }

    public void markGenerationStage(Long appId, String generatingStage, String generatingMessage, GenerationSession session) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(1);
        updateApp.setGeneratingMessage(generatingMessage);
        updateApp.setGeneratingStage(generatingStage);
        appMapper.update(updateApp);
        if (session != null && session.preparation() != null) {
            generationTraceService.updateStage(session.preparation().taskId(), generatingStage, generatingMessage);
        }
    }

    public void updateGenerationSnapshot(Long appId, String generatingMessage) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(1);
        updateApp.setGeneratingMessage(generatingMessage);
        appMapper.update(updateApp);
    }

    public void markGenerationFinished(Long appId) {
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setIsGenerating(0);
        updateApp.setGeneratingMessage("");
        updateApp.setGeneratingStage(null);
        appMapper.update(updateApp);
    }

    public void switchAppCodeGenType(Long appId, CodeGenTypeEnum targetType) {
        if (targetType == null) {
            return;
        }
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCodeGenType(targetType.getValue());
        appMapper.update(updateApp);
    }
}
