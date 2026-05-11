package com.yupi.yuaicodemother.monitor;

import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * AI 模型监听器
 */
@Component
public class AiModelMonitorListener implements ChatModelListener {

    // 用于存储请求开始时间的键
    private static final String REQUEST_START_TIME_KEY = "request_start_time";
    // 用于监控上下文传递（因为请求和响应事件的触发不是同一个线程）
    private static final String MONITOR_CONTEXT_KEY = "monitor_context";

    @Resource
    private AiModelMetricsCollector aiModelMetricsCollector;

    @Override
    public void onRequest(ChatModelRequestContext requestContext) {
        // 获取当前时间戳，但未做任何处理
        requestContext.attributes().put(REQUEST_START_TIME_KEY, Instant.now());
        // 从监控上下文中获取信息
        MonitorContext monitorContext = MonitorContextHolder.getContext();
        if (monitorContext == null) {
            monitorContext = defaultMonitorContext();
        }
        String userId = monitorContext.getUserId();
        String appId = monitorContext.getAppId();
        String taskId = monitorContext.getTaskId();
        requestContext.attributes().put(MONITOR_CONTEXT_KEY, monitorContext);
        // 获取模型名称
        String modelName = requestContext.chatRequest().modelName();
        // 记录请求指标
        aiModelMetricsCollector.recordRequest(userId, appId, taskId, modelName, "started");
    }

    @Override
    public void onResponse(ChatModelResponseContext responseContext) {
        // 从属性中获取监控信息（由 onRequest 方法存储）
        Map<Object, Object> attributes = responseContext.attributes();
        // 从监控上下文中获取信息
        MonitorContext context = (MonitorContext) attributes.get(MONITOR_CONTEXT_KEY);
        if (context == null) {
            context = defaultMonitorContext();
        }
        String userId = context.getUserId();
        String appId = context.getAppId();
        String taskId = context.getTaskId();
        // 获取模型名称
        String modelName = responseContext.chatResponse().modelName();
        // 记录成功请求
        aiModelMetricsCollector.recordRequest(userId, appId, taskId, modelName, "success");
        // 记录响应时间
        recordResponseTime(attributes, userId, appId, taskId, modelName);
        // 记录 Token 使用情况
        recordTokenUsage(responseContext, userId, appId, taskId, modelName);
    }

    @Override
    public void onError(ChatModelErrorContext errorContext) {
        // 从监控上下文中获取信息
        Map<Object, Object> attributes = errorContext.attributes();
        MonitorContext context = (MonitorContext) attributes.get(MONITOR_CONTEXT_KEY);
        if (context == null) {
            context = MonitorContextHolder.getContext();
        }
        if (context == null) {
            context = defaultMonitorContext();
        }
        String userId = context.getUserId();
        String appId = context.getAppId();
        String taskId = context.getTaskId();
        // 获取模型名称和错误类型
        String modelName = errorContext.chatRequest().modelName();
        String errorMessage = errorContext.error().getMessage();
        // 记录失败请求
        aiModelMetricsCollector.recordRequest(userId, appId, taskId, modelName, "error");
        aiModelMetricsCollector.recordError(userId, appId, taskId, modelName, errorMessage);
        // 记录响应时间（即使是错误响应）
        recordResponseTime(attributes, userId, appId, taskId, modelName);
    }

    /**
     * 记录响应时间
     */
    private void recordResponseTime(Map<Object, Object> attributes,
                                    String userId,
                                    String appId,
                                    String taskId,
                                    String modelName) {
        Instant startTime = (Instant) attributes.get(REQUEST_START_TIME_KEY);
        if (startTime == null) {
            return;
        }
        Duration responseTime = Duration.between(startTime, Instant.now());
        aiModelMetricsCollector.recordResponseTime(userId, appId, taskId, modelName, responseTime);
    }

    /**
     * 没有业务上下文的 AI 调用使用默认监控标签，避免监控逻辑影响主流程。
     */
    private MonitorContext defaultMonitorContext() {
        return MonitorContext.builder()
                .userId("anonymous")
                .appId("none")
                .taskId("none")
                .build();
    }

    /**
     * 记录Token使用情况
     */
    private void recordTokenUsage(ChatModelResponseContext responseContext,
                                  String userId,
                                  String appId,
                                  String taskId,
                                  String modelName) {
        TokenUsage tokenUsage = responseContext.chatResponse().metadata().tokenUsage();
        if (tokenUsage != null) {
            aiModelMetricsCollector.recordTokenUsage(userId, appId, taskId, modelName, "input", tokenUsage.inputTokenCount());
            aiModelMetricsCollector.recordTokenUsage(userId, appId, taskId, modelName, "output", tokenUsage.outputTokenCount());
            aiModelMetricsCollector.recordTokenUsage(userId, appId, taskId, modelName, "total", tokenUsage.totalTokenCount());
        }
    }
}
