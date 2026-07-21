package com.rush.rushaicodemother.service.prompt;

import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRepository;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseRuntime;
import com.rush.rushaicodemother.ai.prompt.release.PromptReleaseState;
import com.rush.rushaicodemother.config.AiPromptCatalogProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.PromptReleaseMetricsCollector;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;

/** Loads durable release pointers and atomically swaps the runtime prompt catalog. */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptReleaseRefreshService {

    private final AiPromptCatalogProperties properties;
    private final PromptReleaseRepository repository;
    private final PromptReleaseRuntime runtime;
    private final PromptReleaseMetricsCollector metricsCollector;

    @EventListener(ApplicationReadyEvent.class)
    public void loadInitialReleaseState() {
        if (!runtimeReleases().isEnabled()) {
            metricsCollector.recordRefresh("disabled", Duration.ZERO, runtime.activeRevision());
            return;
        }
        try {
            refreshNow();
        } catch (RuntimeException exception) {
            if (runtimeReleases().isInitialLoadRequired()) {
                throw new IllegalStateException(
                        "initial durable AI prompt release state could not be loaded", exception);
            }
            log.error("Initial durable AI prompt release refresh failed; retaining packaged release",
                    LogExceptionSanitizer.sanitize(exception));
        }
    }

    @Scheduled(fixedDelayString = "${app.ai-prompt-catalog.runtime-releases.refresh-interval:5s}")
    public void refreshScheduled() {
        if (!runtimeReleases().isEnabled()) {
            return;
        }
        try {
            refreshNow();
        } catch (RuntimeException exception) {
            log.error("Durable AI prompt release refresh failed; retaining last known-good release",
                    LogExceptionSanitizer.sanitize(exception));
        }
    }

    public PromptReleaseRefreshResult refreshNow() {
        if (!runtimeReleases().isEnabled()) {
            metricsCollector.recordRefresh("disabled", Duration.ZERO, runtime.activeRevision());
            return PromptReleaseRefreshResult.DISABLED;
        }
        long startedAt = System.nanoTime();
        try {
            PromptReleaseState state = repository.loadCurrent();
            boolean activated = runtime.activate(state);
            PromptReleaseRefreshResult result = activated
                    ? PromptReleaseRefreshResult.ACTIVATED
                    : PromptReleaseRefreshResult.UNCHANGED;
            metricsCollector.recordRefresh(
                    activated ? "activated" : "unchanged",
                    elapsed(startedAt),
                    runtime.activeRevision()
            );
            if (activated) {
                log.info("Activated durable AI prompt release revision={}, promptCount={}",
                        state.revision(), state.releases().size());
            }
            return result;
        } catch (RuntimeException exception) {
            metricsCollector.recordRefresh("failed", elapsed(startedAt), runtime.activeRevision());
            throw exception;
        }
    }

    private AiPromptCatalogProperties.RuntimeReleases runtimeReleases() {
        return properties.getRuntimeReleases();
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0L, System.nanoTime() - startedAt));
    }
}
