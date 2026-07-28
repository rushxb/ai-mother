package com.rush.rushaicodemother.ai.provenance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rush.rushaicodemother.ai.prompt.PromptCatalog;
import com.rush.rushaicodemother.ai.prompt.PromptSelection;
import com.rush.rushaicodemother.service.trace.GenerationModelCallProvenance;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.JacksonChatMessageJsonCodec;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 构建确定性请求沿袭，无需保留提示内容。 */
@Component
public class AiModelProvenanceFactory {

    private static final int SCHEMA_VERSION = 3;
    private static final int MAX_CONTEXT_PACK_REFERENCES = 8;
    private static final int MAX_CONTEXT_PACK_CHARS = 256_000;
    private static final String CONTEXT_PACK_PREFIX = "[AI_CONTEXT_PACK schema=";
    private static final String CONTEXT_PACK_SUFFIX = "[/AI_CONTEXT_PACK]";
    private static final Pattern CONTEXT_PACK_HEADER = Pattern.compile(
            "\\[AI_CONTEXT_PACK schema=(\\d{1,4}) appId=(null|\\d{1,20}) "
                    + "targetType=([a-z0-9._:-]{1,64}) digest=([a-f0-9]{64})\\]"
    );

    private final ObjectMapper objectMapper;
    private final PromptCatalog promptCatalog;
    private final JacksonChatMessageJsonCodec messageCodec = new JacksonChatMessageJsonCodec();

    public AiModelProvenanceFactory(ObjectMapper objectMapper) {
        this(objectMapper, PromptCatalog.unmanaged());
    }

    @Autowired
    public AiModelProvenanceFactory(ObjectMapper objectMapper, PromptCatalog promptCatalog) {
        this.objectMapper = objectMapper;
        this.promptCatalog = promptCatalog == null ? PromptCatalog.unmanaged() : promptCatalog;
    }

    /**
 * 创建 AI 模型来源信息。
 *
 * @param request 请求参数
 * @param provider 提供方
 * @param configuredModel 已配置模型
 * @return AI 模型来源信息
 */
    public GenerationModelCallProvenance create(ChatRequest request,
                                                String provider,
                                                String configuredModel) {
        List<ChatMessage> messages = request == null || request.messages() == null
                ? List.of()
                : request.messages();
        List<ToolSpecification> tools = request == null || request.toolSpecifications() == null
                ? List.of()
                : request.toolSpecifications();

        String requestHash = hash(canonicalMessages(messages));
        String promptTemplateHash = hash(canonicalSystemMessages(messages));
        String toolSchemaHash = hash(canonicalTools(tools));
        String modelConfigHash = hash(canonicalModelConfiguration(
                request, provider, configuredModel, toolSchemaHash));

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("schemaVersion", SCHEMA_VERSION);
        metadata.put("provider", normalize(provider));
        metadata.put("configuredModel", normalize(configuredModel));
        metadata.put("requestedModel", request == null ? null : normalize(request.modelName()));
        metadata.put("requestHash", requestHash);
        metadata.put("promptTemplateHash", promptTemplateHash);
        metadata.put("promptCatalogBundleId", promptCatalog.bundleId());
        metadata.put("promptVersions", promptVersions(messages));
        metadata.put("toolSchemaHash", toolSchemaHash);
        metadata.put("modelConfigHash", modelConfigHash);
        metadata.put("requestMessageCount", messages.size());
        metadata.put("systemMessageCount", messages.stream().filter(SystemMessage.class::isInstance).count());
        metadata.put("toolCount", tools.size());
        metadata.put("toolNames", tools.stream()
                .map(ToolSpecification::name)
                .map(this::normalize)
                .sorted()
                .toList());
        List<Map<String, Object>> contextPacks = contextPackReferences(messages);
        metadata.put("contextPackCount", contextPacks.size());
        metadata.put("contextPacks", contextPacks);
        if (request != null) {
            Map<String, Object> parameters = new LinkedHashMap<>();
            parameters.put("temperature", request.temperature());
            parameters.put("topP", request.topP());
            parameters.put("topK", request.topK());
            parameters.put("frequencyPenalty", request.frequencyPenalty());
            parameters.put("presencePenalty", request.presencePenalty());
            parameters.put("maxOutputTokens", request.maxOutputTokens());
            parameters.put("stopSequenceCount",
                    request.stopSequences() == null ? 0 : request.stopSequences().size());
            parameters.put("toolChoice", request.toolChoice() == null
                    ? null : request.toolChoice().toString());
            parameters.put("responseFormatHash", hash(String.valueOf(request.responseFormat())));
            metadata.put("parameters", parameters);
        }
        return new GenerationModelCallProvenance(
                requestHash,
                promptTemplateHash,
                toolSchemaHash,
                modelConfigHash,
                messages.size(),
                tools.size(),
                toJson(metadata)
        );
    }

    /** 返回提示词{@code Versions}。 */
    private List<Map<String, Object>> promptVersions(List<ChatMessage> messages) {
        Map<String, Map<String, Object>> identified = new LinkedHashMap<>();
        for (ChatMessage message : messages) {
            if (!(message instanceof SystemMessage systemMessage)) {
                continue;
            }
            PromptSelection selection = promptCatalog.identify(systemMessage.text()).orElse(null);
            if (selection == null) {
                continue;
            }
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("promptKey", selection.promptKey());
            metadata.put("version", selection.version());
            metadata.put("channel", selection.channel().name().toLowerCase(java.util.Locale.ROOT));
            metadata.put("contentHash", selection.contentHash());
            identified.putIfAbsent(selection.promptKey() + "@" + selection.version(), Map.copyOf(metadata));
        }
        return List.copyOf(identified.values());
    }

    private String canonicalMessages(List<ChatMessage> messages) {
        StringBuilder canonical = new StringBuilder();
        for (int index = 0; index < messages.size(); index++) {
            ChatMessage message = messages.get(index);
            canonical.append(index).append(':')
                    .append(message == null ? "null" : message.type())
                    .append(':').append(serializeMessage(message)).append('\n');
        }
        return canonical.toString();
    }

    /** 判断当前状态是否允许{@code onical}{@code System}消息。 */
    private String canonicalSystemMessages(List<ChatMessage> messages) {
        StringBuilder canonical = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message instanceof SystemMessage systemMessage) {
                canonical.append(systemMessage.text()).append('\n');
            }
        }
        return canonical.toString();
    }

    /** 判断当前状态是否允许{@code onical}{@code Tools}。 */
    private String canonicalTools(List<ToolSpecification> tools) {
        List<String> definitions = new ArrayList<>(tools.size());
        for (ToolSpecification tool : tools) {
            if (tool != null) {
                definitions.add(tool.toJson());
            }
        }
        definitions.sort(String::compareTo);
        return String.join("\n", definitions);
    }

    /** 返回上下文{@code Pack}{@code References}。 */
    private List<Map<String, Object>> contextPackReferences(List<ChatMessage> messages) {
        List<Map<String, Object>> references = new ArrayList<>();
        Set<String> identifiedDigests = new LinkedHashSet<>();
        for (ChatMessage message : messages) {
            if (references.size() >= MAX_CONTEXT_PACK_REFERENCES) {
                break;
            }
            String text = messageText(message);
            if (text == null || text.isBlank()) {
                continue;
            }
            extractContextPacks(text, references, identifiedDigests);
        }
        return List.copyOf(references);
    }

    /** 从输入中提取上下文{@code Packs}。 */
    private void extractContextPacks(String text,
                                     List<Map<String, Object>> references,
                                     Set<String> identifiedDigests) {
        int cursor = 0;
        // 按既定顺序逐项处理，并在达到资源或状态边界时提前结束。
        while (cursor < text.length() && references.size() < MAX_CONTEXT_PACK_REFERENCES) {
            int start = text.indexOf(CONTEXT_PACK_PREFIX, cursor);
            if (start < 0) {
                return;
            }
            int headerEnd = text.indexOf(']', start);
            if (headerEnd < 0 || headerEnd - start > 256) {
                cursor = start + CONTEXT_PACK_PREFIX.length();
                continue;
            }
            Matcher header = CONTEXT_PACK_HEADER.matcher(text.substring(start, headerEnd + 1));
            if (!header.matches()) {
                cursor = start + CONTEXT_PACK_PREFIX.length();
                continue;
            }
            int bodyStart = lineBreakEnd(text, headerEnd + 1);
            if (bodyStart < 0) {
                cursor = headerEnd + 1;
                continue;
            }
            int suffixStart = text.indexOf(CONTEXT_PACK_SUFFIX, bodyStart);
            if (suffixStart < 0 || suffixStart - start > MAX_CONTEXT_PACK_CHARS) {
                cursor = bodyStart;
                continue;
            }
            int bodyEnd = lineBreakStart(text, suffixStart);
            if (bodyEnd < bodyStart) {
                cursor = suffixStart + CONTEXT_PACK_SUFFIX.length();
                continue;
            }
            String body = text.substring(bodyStart, bodyEnd);
            String declaredBodyDigest = header.group(4);
            if (!declaredBodyDigest.equals(hash(body))) {
                cursor = start + CONTEXT_PACK_PREFIX.length();
                continue;
            }
            int packEnd = suffixStart + CONTEXT_PACK_SUFFIX.length();
            String packDigest = hash(text.substring(start, packEnd));
            if (identifiedDigests.add(packDigest)) {
                Map<String, Object> reference = new LinkedHashMap<>();
                reference.put("schemaVersion", Integer.parseInt(header.group(1)));
                Long appId = parseAppId(header.group(2));
                if (appId != null) {
                    reference.put("appId", appId);
                }
                reference.put("targetType", header.group(3));
                reference.put("digest", packDigest);
                reference.put("bodyDigest", declaredBodyDigest);
                reference.put("sectionCount", occurrences(body, "[SECTION type="));
                references.add(Map.copyOf(reference));
            }
            cursor = packEnd;
        }
    }

    /** 返回消息{@code Text}。 */
    private String messageText(ChatMessage message) {
        if (message instanceof SystemMessage systemMessage) {
            return systemMessage.text();
        }
        if (message instanceof UserMessage userMessage && userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        if (message instanceof AiMessage aiMessage) {
            return aiMessage.text();
        }
        if (message instanceof ToolExecutionResultMessage resultMessage && resultMessage.hasSingleText()) {
            return resultMessage.text();
        }
        return null;
    }

    /** 返回{@code line}{@code Break}{@code End}。 */
    private int lineBreakEnd(String text, int index) {
        if (index >= text.length()) {
            return -1;
        }
        if (text.charAt(index) == '\n') {
            return index + 1;
        }
        if (text.charAt(index) == '\r'
                && index + 1 < text.length()
                && text.charAt(index + 1) == '\n') {
            return index + 2;
        }
        return -1;
    }

    /** 返回{@code line}{@code Break}开始。 */
    private int lineBreakStart(String text, int suffixStart) {
        if (suffixStart <= 0 || text.charAt(suffixStart - 1) != '\n') {
            return -1;
        }
        int bodyEnd = suffixStart - 1;
        if (bodyEnd > 0 && text.charAt(bodyEnd - 1) == '\r') {
            bodyEnd--;
        }
        return bodyEnd;
    }

    /** 解析应用编号。 */
    private Long parseAppId(String value) {
        if ("null".equals(value)) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private int occurrences(String value, String token) {
        int count = 0;
        int cursor = 0;
        while ((cursor = value.indexOf(token, cursor)) >= 0) {
            count++;
            cursor += token.length();
        }
        return count;
    }

    /** 判断当前状态是否允许{@code onical}模型配置。 */
    private String canonicalModelConfiguration(ChatRequest request,
                                               String provider,
                                               String configuredModel,
                                               String toolSchemaHash) {
        if (request == null) {
            return normalize(provider) + '\n' + normalize(configuredModel) + '\n' + toolSchemaHash;
        }
        return String.join("\n",
                normalize(provider),
                normalize(configuredModel),
                normalize(request.modelName()),
                String.valueOf(request.temperature()),
                String.valueOf(request.topP()),
                String.valueOf(request.topK()),
                String.valueOf(request.frequencyPenalty()),
                String.valueOf(request.presencePenalty()),
                String.valueOf(request.maxOutputTokens()),
                String.valueOf(request.stopSequences()),
                String.valueOf(request.toolChoice()),
                hash(String.valueOf(request.responseFormat())),
                toolSchemaHash
        );
    }

    /** 返回{@code serialize}消息。 */
    private String serializeMessage(ChatMessage message) {
        if (message == null) {
            return "null";
        }
        try {
            return messageCodec.messageToJson(message);
        } catch (RuntimeException ignored) {
            return message.getClass().getName() + ':' + String.valueOf(message);
        }
    }

    /** 将当前对象转换为{@code Json}。 */
    private String toJson(Map<String, Object> metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AI model provenance metadata cannot be serialized", exception);
        }
    }

    /** 判断是否存在{@code h}。 */
    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? "unknown" : value.trim();
    }
}
