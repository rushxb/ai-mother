package com.rush.rushaicodemother.orchestration.tool;

import cn.hutool.crypto.digest.DigestUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.rush.rushaicodemother.config.AiToolLoopGuardProperties;
import com.rush.rushaicodemother.core.error.GenerationAgentLoopException;
import com.rush.rushaicodemother.monitor.GenerationOrchestrationMetricsCollector;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 使用脱敏调用指纹识别 Agent 工具重复和无进展循环。
 * 状态只用于单次任务的运行时治理，持久化事实仍由工具会话检查点负责。
 */
@Component
public class GenerationToolLoopGuard {

    static final String REASON_IDENTICAL_CALL = "identical_call";
    static final String REASON_IN_FLIGHT_DUPLICATE = "in_flight_duplicate";
    static final String REASON_NO_PROGRESS = "no_progress";

    private final AiToolLoopGuardProperties properties;
    private final ObjectMapper objectMapper;
    private final GenerationOrchestrationMetricsCollector metricsCollector;
    private final Cache<String, TaskState> states;

    /**
 * 创建生成工具循环防护实例并完成必要的依赖和初始状态设置。
 *
 * @param properties 配置属性
 * @param objectMapper {@code objectMapper} 对应的调用参数
 * @param metricsCollector {@code metricsCollector} 对应的调用参数
 */
    public GenerationToolLoopGuard(AiToolLoopGuardProperties properties,
                                   ObjectMapper objectMapper,
                                   GenerationOrchestrationMetricsCollector metricsCollector) {
        if (properties == null || !properties.isConfigurationValid()) {
            throw new IllegalArgumentException("AI 工具循环治理配置无效");
        }
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.metricsCollector = metricsCollector;
        this.states = Caffeine.newBuilder()
                .maximumSize(properties.getMaximumTrackedTasks())
                .expireAfterAccess(properties.getRetention())
                .build();
    }

    /**
 * 处理执行前调用。
 *
 * @param taskId 任务编号
 * @param request 请求参数
 */
    public void beforeInvocation(String taskId, ToolExecutionRequest request) {
        if (taskId == null || taskId.isBlank() || request == null) {
            throw new IllegalArgumentException("工具循环治理缺少任务或调用信息");
        }
        String signature = requestSignature(request);
        TaskState state = states.get(taskId, ignored -> new TaskState());
        String reason = state.before(
                requestKey(request, signature),
                signature,
                properties.getMaxIdenticalCalls(),
                properties.getMaxNoProgressCalls()
        );
        if (reason != null) {
            String toolName = normalizedToolName(request.name());
            metricsCollector.recordToolLoopGuard(reason, toolName);
            throw new GenerationAgentLoopException(reason, toolName);
        }
    }

    /**
 * 完成调用并持久化终态。
 *
 * @param taskId 任务编号
 * @param request 请求参数
 * @param result 待处理结果
 * @param failed 失败
 */
    public void completeInvocation(String taskId,
                                   ToolExecutionRequest request,
                                   String result,
                                   boolean failed) {
        if (taskId == null || taskId.isBlank() || request == null) {
            return;
        }
        TaskState state = states.getIfPresent(taskId);
        if (state == null) {
            return;
        }
        String signature = requestSignature(request);
        state.complete(
                requestKey(request, signature),
                signature,
                outcomeDigest(result, failed),
                properties.getHistorySize()
        );
    }

    /** 从审批检查点携带的已校验会话重建状态，避免恢复到其他节点后重复原循环。 */
    public void restore(String taskId, List<ChatMessage> messages) {
        // 先处理前置条件和快速返回分支，避免无效输入进入核心流程。
        if (taskId == null || taskId.isBlank() || messages == null) {
            return;
        }
        TaskState restored = new TaskState();
        Map<String, ToolExecutionRequest> requestsById = new HashMap<>();
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        for (ChatMessage message : messages) {
            if (message instanceof AiMessage aiMessage && aiMessage.hasToolExecutionRequests()) {
                for (ToolExecutionRequest request : aiMessage.toolExecutionRequests()) {
                    if (request != null && request.id() != null && !request.id().isBlank()) {
                        requestsById.put(request.id(), request);
                    }
                }
                continue;
            }
            if (!(message instanceof ToolExecutionResultMessage resultMessage)) {
                continue;
            }
            ToolExecutionRequest request = requestsById.get(resultMessage.id());
            if (request == null) {
                continue;
            }
            restored.observe(
                    requestSignature(request),
                    outcomeDigest(resultMessage.text(), Boolean.TRUE.equals(resultMessage.isError())),
                    properties.getHistorySize()
            );
        }
        states.put(taskId, restored);
    }

    private String requestSignature(ToolExecutionRequest request) {
        String toolName = request == null || request.name() == null ? "" : request.name().trim();
        String arguments = request == null || request.arguments() == null ? "" : request.arguments();
        return DigestUtil.sha256Hex(toolName + "\u0000" + canonicalArguments(arguments));
    }

    /** 判断当前状态是否允许{@code onical}参数。 */
    private String canonicalArguments(String arguments) {
        try {
            JsonNode parsed = objectMapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
            return canonicalNode(parsed).toString();
        } catch (Exception malformedJson) {
            return arguments == null ? "" : arguments;
        }
    }

    /** 判断当前状态是否允许{@code onical}节点。 */
    private JsonNode canonicalNode(JsonNode node) {
        if (node == null || node.isNull() || node.isValueNode()) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            Set<String> names = new TreeSet<>();
            node.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                sorted.set(name, canonicalNode(node.get(name)));
            }
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode array = objectMapper.createArrayNode();
            node.forEach(value -> array.add(canonicalNode(value)));
            return array;
        }
        return node;
    }

    private String requestKey(ToolExecutionRequest request, String signature) {
        return request.id() == null || request.id().isBlank()
                ? "anonymous:" + signature
                : request.id();
    }

    private String outcomeDigest(String result, boolean failed) {
        return DigestUtil.sha256Hex((failed ? "failed" : "success")
                + "\u0000" + (result == null ? "" : result));
    }

    private String normalizedToolName(String toolName) {
        if (toolName == null || !toolName.matches("[A-Za-z][A-Za-z0-9_.-]{0,127}")) {
            return "unknown";
        }
        return toolName;
    }

    private static final class TaskState {
        private final Deque<Observation> history = new ArrayDeque<>();
        private final Map<String, String> pendingByRequest = new HashMap<>();
        private int noProgressCalls;

        /** 返回执行前。 */
        private synchronized String before(String requestKey,
                                           String signature,
                                           int maxIdenticalCalls,
                                           int maxNoProgressCalls) {
            if (noProgressCalls >= maxNoProgressCalls) {
                return REASON_NO_PROGRESS;
            }
            if (pendingByRequest.containsValue(signature)) {
                return REASON_IN_FLIGHT_DUPLICATE;
            }
            int identicalCalls = 0;
            Iterator<Observation> iterator = history.descendingIterator();
            while (iterator.hasNext()) {
                Observation observation = iterator.next();
                if (!observation.signature().equals(signature)) {
                    break;
                }
                identicalCalls++;
            }
            if (identicalCalls >= maxIdenticalCalls) {
                return REASON_IDENTICAL_CALL;
            }
            pendingByRequest.put(requestKey, signature);
            return null;
        }

        private synchronized void complete(String requestKey,
                                           String signature,
                                           String outcomeDigest,
                                           int historySize) {
            String pendingSignature = pendingByRequest.remove(requestKey);
            if (!signature.equals(pendingSignature)) {
                return;
            }
            observe(signature, outcomeDigest, historySize);
        }

        private synchronized void observe(String signature,
                                          String outcomeDigest,
                                          int historySize) {
            Observation observation = new Observation(signature, outcomeDigest);
            boolean repeated = history.contains(observation);
            noProgressCalls = repeated ? noProgressCalls + 1 : 0;
            history.addLast(observation);
            while (history.size() > historySize) {
                history.removeFirst();
            }
        }
    }

    private record Observation(String signature, String outcomeDigest) {
    }
}
