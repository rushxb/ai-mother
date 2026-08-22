package com.rush.rushaicodemother.orchestration.artifact;

import com.rush.rushaicodemother.model.enums.CodeGenTypeEnum;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * 可持久恢复的质量门禁事实模型。
 *
 * <p>该模块集中拥有门禁字段的类型恢复与状态不变量，防止检查点中的
 * {@code passed}、{@code level} 和阻断项互相矛盾时绕过 Review。</p>
 */
public final class QualityGateArtifact {

    public static final String KEY = "quality_gate";

    private static final String SCHEMA_VERSION = "v2";
    private static final String REVIEW_SUBJECT_DIGEST_FIELD = "reviewSubjectDigest";
    private static final Set<String> CORE_FIELDS = Set.of(
            "schemaVersion",
            REVIEW_SUBJECT_DIGEST_FIELD,
            "passed",
            "level",
            "blockers",
            "warnings",
            "passes"
    );

    private final QualityGateResult result;
    private final Map<String, Object> payload;

    private QualityGateArtifact(QualityGateResult result, Map<String, Object> payload) {
        this.result = result;
        this.payload = Map.copyOf(payload);
    }

    /**
     * 根据 Review 实际读取的生成输入生成稳定主体。
     *
     * <p>调用方只传目标类型和制品集合；具体纳入哪些事实、如何规范化与摘要均由本模块拥有。</p>
     */
    public static ReviewSubject reviewSubject(
            CodeGenTypeEnum targetType,
            Map<String, GenerationArtifact> artifacts
    ) {
        if (targetType == null || artifacts == null) {
            throw invalid("Review 目标类型和制品集合不能为空");
        }
        GenerationArtifact specificationArtifact = artifacts.get(GenerationSpecificationArtifact.KEY);
        if (specificationArtifact == null) {
            throw invalid("缺少已审查的生成规范");
        }
        GenerationSpecificationArtifact specification =
                GenerationSpecificationArtifact.fromArtifact(specificationArtifact);
        MessageDigest digest = sha256();
        updateDigest(digest, "targetType", targetType.getValue());
        updateDigest(digest, "enhancedPrompt", specification.enhancedPrompt());
        updateDigest(digest, "patchFirst", Boolean.toString(specification.patchFirst()));
        updateDigest(digest, "requiresBuild", Boolean.toString(specification.requiresBuild()));
        updateDigest(digest, "validationMode", specification.validationMode());
        updateDigest(digest, "generationMode", specification.generationMode());
        bindChangePlan(digest, artifacts.get(ChangePlan.KEY));
        bindApiContract(digest, targetType, artifacts.get(ApiContractArtifact.KEY));
        return new ReviewSubject(HexFormat.of().formatHex(digest.digest()));
    }

    private static void bindChangePlan(MessageDigest digest, GenerationArtifact artifact) {
        if (artifact == null) {
            updateDigest(digest, "changePlanState", "missing");
            return;
        }
        ChangePlan changePlan = ChangePlan.fromArtifact(artifact);
        updateDigest(digest, "changePlanState", "valid");
        updateStructuredValue(digest, "changePlan", changePlan.toPayload());
    }

    private static void bindApiContract(
            MessageDigest digest,
            CodeGenTypeEnum targetType,
            GenerationArtifact artifact
    ) {
        if (targetType != CodeGenTypeEnum.BACKEND_PROJECT
                && targetType != CodeGenTypeEnum.FULL_STACK_PROJECT) {
            return;
        }
        if (artifact == null) {
            updateDigest(digest, "apiContractState", "missing");
            return;
        }
        try {
            ApiContractArtifact apiContract = ApiContractArtifact.fromArtifact(artifact);
            updateDigest(digest, "apiContractState", "valid");
            updateStructuredValue(digest, "apiContract", apiContract.toArtifact().payload());
        } catch (IllegalArgumentException exception) {
            // 后端 Review 对任何损坏契约给出同一个阻断结论，摘要也绑定到这一语义状态。
            updateDigest(digest, "apiContractState", "invalid");
        }
    }

    /** 从 Review 结果创建可持久化门禁事实，并保留非核心诊断详情。 */
    public static QualityGateArtifact fromResult(QualityGateResult result,
                                                 ReviewSubject reviewSubject,
                                                 Map<String, Object> additionalPayload) {
        validateResult(Objects.requireNonNull(result, "质量门禁结果不能为空"));
        Objects.requireNonNull(reviewSubject, "质量门禁 Review 主体不能为空");
        Map<String, Object> details = additionalPayload == null ? Map.of() : additionalPayload;
        for (String fieldName : CORE_FIELDS) {
            if (details.containsKey(fieldName)) {
                throw invalid("核心字段 " + fieldName + " 只能由质量门禁模块写入");
            }
        }
        Map<String, Object> payload = new LinkedHashMap<>(details);
        payload.put("schemaVersion", SCHEMA_VERSION);
        payload.put(REVIEW_SUBJECT_DIGEST_FIELD, reviewSubject.digest);
        payload.put("passed", result.passed());
        payload.put("level", result.level());
        payload.put("blockers", result.blockers());
        payload.put("warnings", result.warnings());
        payload.put("passes", result.passes());
        return new QualityGateArtifact(result, payload);
    }

    /** 从持久制品严格恢复门禁结果。 */
    public static QualityGateArtifact fromArtifact(GenerationArtifact artifact,
                                                   ReviewSubject expectedSubject) {
        Objects.requireNonNull(artifact, "质量门禁制品不能为空");
        Objects.requireNonNull(expectedSubject, "当前 Review 主体不能为空");
        if (!KEY.equals(artifact.key())) {
            throw invalid("制品类型不匹配: " + artifact.key());
        }
        Map<String, Object> payload = Objects.requireNonNull(
                artifact.payload(), "质量门禁制品载荷不能为空");
        String schemaVersion = requireText(payload.get("schemaVersion"), "schemaVersion");
        if (!SCHEMA_VERSION.equals(schemaVersion)) {
            throw invalid("schemaVersion 不受支持: " + schemaVersion);
        }
        String persistedDigest = requireText(
                payload.get(REVIEW_SUBJECT_DIGEST_FIELD), REVIEW_SUBJECT_DIGEST_FIELD);
        if (!MessageDigest.isEqual(
                persistedDigest.getBytes(StandardCharsets.US_ASCII),
                expectedSubject.digest.getBytes(StandardCharsets.US_ASCII))) {
            throw invalid("Review 主体与当前生成输入不一致");
        }
        boolean passed = requireBoolean(payload.get("passed"), "passed");
        String level = requireText(payload.get("level"), "level");
        List<String> blockers = requireStringList(payload.get("blockers"), "blockers");
        List<String> warnings = requireStringList(payload.get("warnings"), "warnings");
        List<String> passes = requireStringList(payload.get("passes"), "passes");

        if (passed) {
            String expectedLevel = warnings.isEmpty() ? "pass" : "warning";
            if (!blockers.isEmpty()) {
                throw invalid("通过状态不能包含阻断项");
            }
            if (!expectedLevel.equals(level)) {
                throw invalid("通过状态与 level 不一致: " + level);
            }
            QualityGateResult result = QualityGateResult.passed(warnings, passes);
            return new QualityGateArtifact(result, payload);
        }
        if (blockers.isEmpty()) {
            throw invalid("阻断状态必须包含阻断项");
        }
        if (!"blocker".equals(level)) {
            throw invalid("阻断状态与 level 不一致: " + level);
        }
        QualityGateResult result = QualityGateResult.failed(blockers, warnings, passes);
        return new QualityGateArtifact(result, payload);
    }

    public QualityGateResult result() {
        return result;
    }

    /** 转换为通用持久制品。 */
    public GenerationArtifact toArtifact(String role, String title) {
        return GenerationArtifact.of(
                KEY,
                requireText(role, "role"),
                requireText(title, "title"),
                payload
        );
    }

    private static void validateResult(QualityGateResult result) {
        Objects.requireNonNull(result.blockers(), "质量门禁 blockers 不能为空");
        Objects.requireNonNull(result.warnings(), "质量门禁 warnings 不能为空");
        Objects.requireNonNull(result.passes(), "质量门禁 passes 不能为空");
        validateMessages(result.blockers(), "blockers");
        validateMessages(result.warnings(), "warnings");
        validateMessages(result.passes(), "passes");
        String expectedLevel;
        if (result.passed()) {
            if (!result.blockers().isEmpty()) {
                throw invalid("通过状态不能包含阻断项");
            }
            expectedLevel = result.warnings().isEmpty() ? "pass" : "warning";
        } else {
            if (result.blockers().isEmpty()) {
                throw invalid("阻断状态必须包含阻断项");
            }
            expectedLevel = "blocker";
        }
        if (!expectedLevel.equals(result.level())) {
            throw invalid("门禁状态与 level 不一致: " + result.level());
        }
    }

    private static void validateMessages(List<String> messages, String fieldName) {
        if (messages.stream().anyMatch(message -> message == null || message.isBlank())) {
            throw invalid("字段 " + fieldName + " 只能包含非空字符串");
        }
    }

    private static boolean requireBoolean(Object value, String fieldName) {
        if (value instanceof Boolean booleanValue) {
            return booleanValue;
        }
        throw invalid("字段 " + fieldName + " 必须为布尔值");
    }

    private static String requireText(Object value, String fieldName) {
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw invalid("字段 " + fieldName + " 必须为非空字符串");
    }

    private static List<String> requireStringList(Object value, String fieldName) {
        if (!(value instanceof Collection<?> collection)) {
            throw invalid("字段 " + fieldName + " 必须为字符串列表");
        }
        for (Object item : collection) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw invalid("字段 " + fieldName + " 只能包含非空字符串");
            }
        }
        return collection.stream().map(String.class::cast).toList();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", exception);
        }
    }

    /** 使用长度前缀编码字段，避免简单字符串拼接产生边界碰撞。 */
    private static void updateDigest(MessageDigest digest, String fieldName, String value) {
        byte[] fieldBytes = fieldName.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(fieldBytes.length).array());
        digest.update(fieldBytes);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(valueBytes.length).array());
        digest.update(valueBytes);
    }

    /**
     * 对已经过领域制品校验的嵌套事实进行确定性编码，Map 顺序不会影响摘要。
     */
    private static void updateStructuredValue(MessageDigest digest, String fieldName, Object value) {
        if (value == null) {
            updateDigest(digest, fieldName + ".type", "null");
            return;
        }
        if (value instanceof Map<?, ?> map) {
            updateDigest(digest, fieldName + ".type", "map");
            Map<String, Object> sortedValues = new TreeMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw invalid("字段 " + fieldName + " 的 Map 键必须为字符串");
                }
                sortedValues.put(key, entry.getValue());
            }
            updateDigest(digest, fieldName + ".size", Integer.toString(sortedValues.size()));
            for (Map.Entry<String, Object> entry : sortedValues.entrySet()) {
                updateDigest(digest, fieldName + ".key", entry.getKey());
                updateStructuredValue(digest, fieldName + "." + entry.getKey(), entry.getValue());
            }
            return;
        }
        if (value instanceof Collection<?> collection) {
            updateDigest(digest, fieldName + ".type", "list");
            updateDigest(digest, fieldName + ".size", Integer.toString(collection.size()));
            int index = 0;
            for (Object item : collection) {
                updateStructuredValue(digest, fieldName + "[" + index++ + "]", item);
            }
            return;
        }
        if (value instanceof String text) {
            updateDigest(digest, fieldName + ".type", "string");
            updateDigest(digest, fieldName, text);
            return;
        }
        if (value instanceof Boolean booleanValue) {
            updateDigest(digest, fieldName + ".type", "boolean");
            updateDigest(digest, fieldName, Boolean.toString(booleanValue));
            return;
        }
        if (value instanceof Number number) {
            updateDigest(digest, fieldName + ".type", "number");
            updateDigest(digest, fieldName, number.toString());
            return;
        }
        throw invalid("字段 " + fieldName + " 包含不支持的值类型: " + value.getClass().getName());
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("质量门禁制品已损坏，" + reason);
    }

    /** 不暴露摘要构造细节，防止调用方绕过统一 Review 主体计算。 */
    public static final class ReviewSubject {

        private final String digest;

        private ReviewSubject(String digest) {
            this.digest = digest;
        }
    }
}
