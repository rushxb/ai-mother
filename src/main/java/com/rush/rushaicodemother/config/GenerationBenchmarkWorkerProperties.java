package com.rush.rushaicodemother.config;

import jakarta.validation.constraints.AssertTrue;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Pattern;

/** 独立 Benchmark Worker 的候选、输出和证据有效期配置。 */
@Data
@Component
@Validated
@ConfigurationProperties(prefix = "app.generation-benchmark.worker")
public class GenerationBenchmarkWorkerProperties {

    /** 单轮评测签发证据的有效期，属于固定发布契约。 */
    public static final Duration EVIDENCE_VALIDITY = Duration.ofDays(1);

    private static final Pattern PROMPT_KEY =
            Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern PROMPT_VERSION =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,31}");

    private boolean enabled;
    private String outputFile = "";
    private Duration evidenceValidity = EVIDENCE_VALIDITY;
    private Candidate candidate = new Candidate();

    /**
 * 校验当前配置项组合是否合法。
 *
 * @return 满足条件时返回 {@code true}，否则返回 {@code false}
 */
    @AssertTrue(message = "Benchmark Worker 配置无效")
    public boolean isConfigurationValid() {
        if (!enabled) {
            return true;
        }
        if (outputFile == null || outputFile.isBlank() || outputFile.length() > 2_048
                || outputFile.chars().anyMatch(Character::isISOControl)
                || evidenceValidity == null || evidenceValidity.isZero()
                || evidenceValidity.isNegative()
                || evidenceValidity.compareTo(Duration.ofDays(7)) > 0
                || candidate == null) {
            return false;
        }
        String subjectType = normalized(candidate.subjectType);
        return switch (subjectType) {
            case "AI_MODEL_ENABLE" -> validModelCandidate();
            case "PROMPT_RELEASE" -> validPromptCandidate();
            default -> false;
        };
    }

    private boolean validModelCandidate() {
        return candidate.modelId > 0
                && blank(candidate.promptKey)
                && blank(candidate.stableVersion)
                && blank(candidate.canaryVersion)
                && candidate.canaryPercentage == 0;
    }

    /** 校验提示词候选配置是否有效。 */
    private boolean validPromptCandidate() {
        if (candidate.modelId != 0
                || !PROMPT_KEY.matcher(trim(candidate.promptKey)).matches()
                || !PROMPT_VERSION.matcher(trim(candidate.stableVersion)).matches()
                || candidate.canaryPercentage < 0 || candidate.canaryPercentage > 100) {
            return false;
        }
        String canaryVersion = trim(candidate.canaryVersion);
        if (candidate.canaryPercentage == 0) {
            return canaryVersion.isEmpty();
        }
        return PROMPT_VERSION.matcher(canaryVersion).matches()
                && !trim(candidate.stableVersion).equals(canaryVersion);
    }

    private String normalized(String value) {
        return trim(value).toUpperCase(Locale.ROOT);
    }

    private boolean blank(String value) {
        return trim(value).isEmpty();
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    @Data
    public static class Candidate {
        private String subjectType = "";
        private long modelId;
        private String promptKey = "";
        private String stableVersion = "";
        private String canaryVersion = "";
        private int canaryPercentage;
    }
}
