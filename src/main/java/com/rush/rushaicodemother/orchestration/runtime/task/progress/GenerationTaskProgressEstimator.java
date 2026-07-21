package com.rush.rushaicodemother.orchestration.runtime.task.progress;

import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.model.enums.GenerationTaskStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;

/** Estimates task completion from durable route history with bounded configuration fallbacks. */
@Slf4j
@Service
public class GenerationTaskProgressEstimator {

    private static final String BASIS_HISTORICAL = "historical_route";
    private static final String BASIS_FALLBACK = "configured_fallback";
    private static final String BASIS_TERMINAL = "terminal_actual";

    private final GenerationDurationProfileService profileService;
    private final GenerationTaskProgressProperties properties;
    private final Clock clock;

    @Autowired
    public GenerationTaskProgressEstimator(GenerationDurationProfileService profileService,
                                           GenerationTaskProgressProperties properties) {
        this(profileService, properties, Clock.systemUTC());
    }

    GenerationTaskProgressEstimator(GenerationDurationProfileService profileService,
                                    GenerationTaskProgressProperties properties,
                                    Clock clock) {
        this.profileService = Objects.requireNonNull(profileService, "profileService");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public GenerationTaskProgressEstimate estimate(String route,
                                                    String status,
                                                    Instant submittedAt,
                                                    Instant deadlineAt,
                                                    String currentStage) {
        Instant now = Instant.now(clock);
        if (submittedAt == null) {
            return GenerationTaskProgressEstimate.unavailable(0L, now);
        }
        long elapsedMs = nonNegativeMillis(submittedAt, now);
        GenerationTaskStatus taskStatus = GenerationTaskStatus.fromValue(status);
        if (taskStatus != null && taskStatus.isTerminal()) {
            return terminalEstimate(elapsedMs, now);
        }

        ProfileDecision decision = resolveProfile(route, currentStage);
        long minimumRemainingMs = properties.getMinimumRunningRemaining().toMillis();
        long estimatedTotalMs = Math.max(decision.p50TotalMs(), safeAdd(elapsedMs, minimumRemainingMs));
        long conservativeTotalMs = Math.max(decision.p90TotalMs(), estimatedTotalMs);
        long remainingMs = Math.max(minimumRemainingMs, estimatedTotalMs - elapsedMs);
        long conservativeRemainingMs = Math.max(remainingMs, conservativeTotalMs - elapsedMs);
        int progressPercent = calculateProgress(status, elapsedMs, estimatedTotalMs);
        Instant estimatedCompletionAt = now.plusMillis(remainingMs);
        Instant conservativeCompletionAt = now.plusMillis(conservativeRemainingMs);
        Long deadlineSlackMs = deadlineAt == null
                ? null
                : Duration.between(conservativeCompletionAt, deadlineAt).toMillis();
        boolean deadlineRisk = deadlineSlackMs != null && deadlineSlackMs < 0;

        return new GenerationTaskProgressEstimate(
                true,
                elapsedMs,
                estimatedTotalMs,
                remainingMs,
                conservativeRemainingMs,
                estimatedCompletionAt,
                conservativeCompletionAt,
                progressPercent,
                decision.confidence(),
                decision.basis(),
                decision.taskSampleSize(),
                decision.stageProfile() == null ? null : decision.stageProfile().p50DurationMs(),
                decision.stageProfile() == null ? null : decision.stageProfile().p90DurationMs(),
                decision.stageProfile() == null ? null : decision.stageProfile().sampleSize(),
                deadlineRisk,
                deadlineSlackMs,
                now
        );
    }

    private GenerationTaskProgressEstimate terminalEstimate(long elapsedMs, Instant now) {
        return new GenerationTaskProgressEstimate(
                true, elapsedMs, elapsedMs, 0L, 0L, now, now, 100,
                "high", BASIS_TERMINAL, 0,
                null, null, null, false, null, now);
    }

    private ProfileDecision resolveProfile(String route, String currentStage) {
        GenerationDurationProfile profile = safeProfile(route);
        if (profile != null && profile.taskSampleSize() >= properties.getMinimumHistoricalSamples()
                && profile.p50TotalDurationMs() > 0) {
            long p50 = clampDuration(profile.p50TotalDurationMs());
            long p90 = clampDuration(Math.max(profile.p90TotalDurationMs(), p50));
            String confidence = profile.taskSampleSize() >= properties.getHighConfidenceSamples()
                    && p90 <= safeMultiply(p50, 2.0d) ? "high" : "medium";
            return new ProfileDecision(
                    p50, p90, BASIS_HISTORICAL, confidence, profile.taskSampleSize(),
                    findStageProfile(profile, currentStage));
        }
        long fallback = clampDuration(properties.getFallbackTotalDuration().toMillis());
        long fallbackP90 = clampDuration(Math.max(
                fallback, safeMultiply(fallback, properties.getFallbackP90Multiplier())));
        return new ProfileDecision(fallback, fallbackP90, BASIS_FALLBACK, "low",
                profile == null ? 0 : profile.taskSampleSize(),
                profile == null ? null : findStageProfile(profile, currentStage));
    }

    private GenerationDurationProfile safeProfile(String route) {
        if (route == null || route.isBlank()) {
            return null;
        }
        try {
            return profileService.getProfile(route);
        } catch (RuntimeException failure) {
            log.warn("Generation duration profile unavailable; using configured ETA fallback, route: {}",
                    sanitizeRoute(route), LogExceptionSanitizer.sanitize(failure));
            return null;
        }
    }

    private GenerationStageDurationProfile findStageProfile(GenerationDurationProfile profile,
                                                              String currentStage) {
        if (currentStage == null || currentStage.isBlank()) {
            return null;
        }
        String normalized = currentStage.trim().toLowerCase(Locale.ROOT);
        return profile.stages().stream()
                .filter(stage -> normalized.equals(stage.stage()))
                .max(Comparator.comparingInt(GenerationStageDurationProfile::sampleSize)
                        .thenComparingLong(GenerationStageDurationProfile::p90DurationMs))
                .orElse(null);
    }

    private int calculateProgress(String status, long elapsedMs, long estimatedTotalMs) {
        if (estimatedTotalMs <= 0) {
            return 0;
        }
        int raw = (int) Math.floor(Math.min(100.0d, elapsedMs * 100.0d / estimatedTotalMs));
        if ("queued".equalsIgnoreCase(status)) {
            return Math.min(5, raw);
        }
        return Math.min(properties.getRunningProgressCap(), raw);
    }

    private long clampDuration(long value) {
        return Math.max(1L, Math.min(value, properties.getMaximumEstimatedDuration().toMillis()));
    }

    private long safeMultiply(long value, double multiplier) {
        double result = value * multiplier;
        return result >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.round(result);
    }

    private long safeAdd(long left, long right) {
        if (Long.MAX_VALUE - left < right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private long nonNegativeMillis(Instant start, Instant end) {
        if (end.isBefore(start)) {
            return 0L;
        }
        return Duration.between(start, end).toMillis();
    }

    private String sanitizeRoute(String route) {
        String value = route == null ? "unknown" : route.trim();
        return value.length() <= 64 ? value : value.substring(0, 64);
    }

    private record ProfileDecision(
            long p50TotalMs,
            long p90TotalMs,
            String basis,
            String confidence,
            int taskSampleSize,
            GenerationStageDurationProfile stageProfile
    ) {
    }
}
