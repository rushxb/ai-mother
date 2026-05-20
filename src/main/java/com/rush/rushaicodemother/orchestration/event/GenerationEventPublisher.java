package com.rush.rushaicodemother.orchestration.event;

import com.rush.rushaicodemother.orchestration.GenerationTaskRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class GenerationEventPublisher {

    public void publish(GenerationTaskRequest request,
                        GenerationEventType type,
                        String message,
                        Map<String, Object> data) {
        Long appId = request == null || request.app() == null ? null : request.app().getId();
        Long userId = request == null || request.loginUser() == null ? null : request.loginUser().getId();
        log.info("生成任务事件: appId={}, userId={}, type={}, message={}, data={}",
                appId, userId, type == null ? null : type.getValue(), message, data);
    }
}
