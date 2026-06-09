package com.rush.rushaicodemother.orchestration.pipeline;

import com.rush.rushaicodemother.core.handler.GenerationStreamEvent;
import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;
import com.rush.rushaicodemother.orchestration.GenerationSession;
import com.rush.rushaicodemother.orchestration.GenerationSessionRegistry;
import com.rush.rushaicodemother.orchestration.GenerationTaskResult;
import com.rush.rushaicodemother.orchestration.lifecycle.GenerationTaskLifecycleService;
import com.rush.rushaicodemother.orchestration.routing.GenerationRoute;
import com.rush.rushaicodemother.orchestration.template.SlotFillGenerationService;
import com.rush.rushaicodemother.orchestration.template.SlotFillResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

@Slf4j
@Order(20)
@Component
@RequiredArgsConstructor
public class SlotFillGenerationPipeline implements GenerationPipeline {

    private static final long COMPLETED_SESSION_REPLAY_SECONDS = 30;

    private final GenerationTaskLifecycleService generationTaskLifecycleService;
    private final GenerationSessionRegistry sessionRegistry;
    private final SlotFillGenerationService slotFillGenerationService;

    @Override
    public String route() {
        return GenerationRoute.SLOT_FILL;
    }

    @Override
    public boolean supports(GenerationPipelineRequest request) {
        CodeGenTypeEnum type = request.codeGenType();
        return (type == CodeGenTypeEnum.VUE_PROJECT
                || type == CodeGenTypeEnum.BACKEND_PROJECT
                || type == CodeGenTypeEnum.FULL_STACK_PROJECT)
                && !request.workspace().exists();
    }

    @Override
    public Optional<GenerationTaskResult> execute(GenerationPipelineRequest request) {
        App app = request.taskRequest().app();
        try {
            SlotFillResult result = slotFillGenerationService.tryGenerate(app, request.taskRequest());
            if (result == null) {
                return Optional.empty();
            }
            log.info("模板 slot 填充路径完成，appId: {}, templateId: {}, filledSlots: {}",
                    app.getId(), result.templateId(), result.filledSlotCount());
            String taskId = "slot_fill_" + System.currentTimeMillis();
            GenerationSession session = new GenerationSession(null);
            sessionRegistry.put(app.getId(), session);
            session.emit(GenerationStreamEvent.agentEvent(
                    result.summary(),
                    Map.of(
                            "route", route(),
                            "templateId", result.templateId(),
                            "filledSlots", result.filledSlots(),
                            "totalChars", result.totalChars()
                    )
            ));
            session.complete();
            sessionRegistry.cleanupLater(app.getId(), session, COMPLETED_SESSION_REPLAY_SECONDS);
            generationTaskLifecycleService.charge(taskId);
            return Optional.of(new GenerationTaskResult(taskId, route(), request.workspace(), session.asFlux()));
        } catch (Exception e) {
            log.warn("模板 slot 填充路径异常，回退到后续生成管线，appId: {}, error: {}", app.getId(), e.getMessage());
            return Optional.empty();
        }
    }
}
