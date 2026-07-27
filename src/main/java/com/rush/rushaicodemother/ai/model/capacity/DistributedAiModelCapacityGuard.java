package com.rush.rushaicodemother.ai.model.capacity;

import cn.hutool.crypto.digest.DigestUtil;
import com.rush.rushaicodemother.config.AiModelCapacityProperties;
import com.rush.rushaicodemother.infrastructure.diagnostic.LogExceptionSanitizer;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateLimiterConfig;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

/** Redis 支持每模型并发、RPM 和保守的 TPM 准入控制。 */
@Slf4j
@Component
public class DistributedAiModelCapacityGuard implements AiModelCapacityGuard, AutoCloseable {

    private static final Duration RATE_INTERVAL = Duration.ofMinutes(1);

    private final RedissonClient redissonClient;
    private final AiModelCapacityProperties properties;
    private final AiModelMetricsCollector metrics;
    private final ScheduledExecutorService leaseScheduler;
    private final LongSupplier nanoTime;
    private final boolean shutdownScheduler;
    private final AtomicBoolean shuttingDown = new AtomicBoolean();

    @Autowired
    public DistributedAiModelCapacityGuard(RedissonClient redissonClient,
                                           AiModelCapacityProperties properties,
                                           AiModelMetricsCollector metrics) {
        this(
                redissonClient,
                properties,
                metrics,
                createScheduler(Objects.requireNonNull(properties, "properties").getSchedulerThreads()),
                System::nanoTime,
                true
        );
    }

    DistributedAiModelCapacityGuard(RedissonClient redissonClient,
                                    AiModelCapacityProperties properties,
                                    AiModelMetricsCollector metrics,
                                    ScheduledExecutorService leaseScheduler,
                                    LongSupplier nanoTime) {
        this(redissonClient, properties, metrics, leaseScheduler, nanoTime, false);
    }

    private DistributedAiModelCapacityGuard(RedissonClient redissonClient,
                                            AiModelCapacityProperties properties,
                                            AiModelMetricsCollector metrics,
                                            ScheduledExecutorService leaseScheduler,
                                            LongSupplier nanoTime,
                                            boolean shutdownScheduler) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.leaseScheduler = Objects.requireNonNull(leaseScheduler, "leaseScheduler");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.shutdownScheduler = shutdownScheduler;
    }

    @Override
    public Lease acquire(String provider,
                         String modelId,
                         int configuredMaxOutputTokens,
                         ChatRequest request) {
        return acquire(provider, modelId, configuredMaxOutputTokens, request, null);
    }

    @Override
    public Lease acquire(String provider,
                         String modelId,
                         int configuredMaxOutputTokens,
                         ChatRequest request,
                         Duration upstreamTimeout) {
        requireIdentity(provider, modelId, configuredMaxOutputTokens, request, upstreamTimeout);
        if (!properties.isEnabled()) {
            return Lease.NOOP;
        }

        long startedAt = nanoTime.getAsLong();
        if (shuttingDown.get()) {
            metrics.recordCapacityAdmission(
                    provider, modelId, "infrastructure", "rejected", elapsed(startedAt));
            throw AiModelCapacityException.unavailable(null);
        }

        String identity = modelIdentity(provider, modelId);
        long tokenReservation = estimateTokenReservation(request, configuredMaxOutputTokens);
        if (tokenReservation > properties.getTokensPerMinute()) {
            throw rejected(provider, modelId, "tpm", startedAt);
        }

        Duration maximumHold = maximumHold(upstreamTimeout);
        Duration initialPermitLease = shorter(properties.getPermitLease(), maximumHold);
        RPermitExpirableSemaphore semaphore = null;
        String permitId = null;
        long permitAcquiredAt = 0L;
        try {
            semaphore = concurrencySemaphore(identity);
            permitId = acquireConcurrency(semaphore, initialPermitLease);
            permitAcquiredAt = nanoTime.getAsLong();
            if (permitId == null) {
                throw rejected(provider, modelId, "concurrency", startedAt);
            }
            acquireRate(identity, "rpm", properties.getRequestsPerMinute(), 1,
                    provider, modelId, startedAt);
            acquireRate(identity, "tpm", properties.getTokensPerMinute(), tokenReservation,
                    provider, modelId, startedAt);

            Lease lease = new RenewableLease(
                    semaphore,
                    permitId,
                    provider,
                    modelId,
                    permitAcquiredAt,
                    initialPermitLease,
                    maximumHold
            );
            try {
                metrics.recordCapacityAdmission(
                        provider, modelId, "all", "acquired", elapsed(startedAt));
                return lease;
            } catch (RuntimeException telemetryFailure) {
                lease.close();
                throw telemetryFailure;
            }
        } catch (AiModelCapacityException capacityFailure) {
            release(semaphore, permitId, provider, modelId);
            throw capacityFailure;
        } catch (InterruptedException interrupted) {
            release(semaphore, permitId, provider, modelId);
            Thread.currentThread().interrupt();
            metrics.recordCapacityAdmission(
                    provider, modelId, "infrastructure", "rejected", elapsed(startedAt));
            throw AiModelCapacityException.unavailable(interrupted);
        } catch (RuntimeException infrastructureFailure) {
            release(semaphore, permitId, provider, modelId);
            if (properties.isFailOpen()) {
                metrics.recordCapacityAdmission(
                        provider, modelId, "infrastructure", "bypassed", elapsed(startedAt));
                log.warn("AI model capacity infrastructure failed open, provider={}, modelId={}",
                        safe(provider), safe(modelId), LogExceptionSanitizer.sanitize(infrastructureFailure));
                return Lease.NOOP;
            }
            metrics.recordCapacityAdmission(
                    provider, modelId, "infrastructure", "rejected", elapsed(startedAt));
            throw AiModelCapacityException.unavailable(infrastructureFailure);
        }
    }

    @PreDestroy
    @Override
    public void close() {
        if (shuttingDown.compareAndSet(false, true) && shutdownScheduler) {
            leaseScheduler.shutdownNow();
        }
    }

    private RPermitExpirableSemaphore concurrencySemaphore(String identity) {
        String key = properties.getKeyPrefix() + "concurrency:" + identity
                + ":" + properties.getMaxConcurrentPerModel();
        RPermitExpirableSemaphore semaphore = redissonClient.getPermitExpirableSemaphore(key);
        semaphore.trySetPermits(properties.getMaxConcurrentPerModel());
        semaphore.expire(properties.getIdleTtl());
        return semaphore;
    }

    private String acquireConcurrency(RPermitExpirableSemaphore semaphore,
                                      Duration initialPermitLease) throws InterruptedException {
        return semaphore.tryAcquire(
                properties.getAcquireTimeout().toMillis(),
                positiveMillis(initialPermitLease),
                TimeUnit.MILLISECONDS
        );
    }

    private void acquireRate(String identity,
                             String gate,
                             long rate,
                             long permits,
                             String provider,
                             String modelId,
                             long startedAt) throws InterruptedException {
        String key = properties.getKeyPrefix() + gate + ":" + identity + ":" + rate;
        RRateLimiter limiter = redissonClient.getRateLimiter(key);
        boolean initialized = limiter.trySetRate(RateType.OVERALL, rate, RATE_INTERVAL);
        if (!initialized && rateConfigurationChanged(limiter.getConfig(), rate)) {
            limiter.setRate(RateType.OVERALL, rate, RATE_INTERVAL);
        }
        limiter.expire(properties.getIdleTtl());
        if (!limiter.tryAcquire(permits, properties.getAcquireTimeout())) {
            throw rejected(provider, modelId, gate, startedAt);
        }
    }

    private boolean rateConfigurationChanged(RateLimiterConfig current, long expectedRate) {
        return current == null
                || current.getRateType() != RateType.OVERALL
                || !Objects.equals(current.getRate(), expectedRate)
                || !Objects.equals(current.getRateInterval(), RATE_INTERVAL.toMillis());
    }

    private AiModelCapacityException rejected(String provider,
                                              String modelId,
                                              String gate,
                                              long startedAt) {
        metrics.recordCapacityAdmission(
                provider, modelId, gate, "rejected", elapsed(startedAt));
        return AiModelCapacityException.rejected(gate);
    }

    private void release(RPermitExpirableSemaphore semaphore,
                         String permitId,
                         String provider,
                         String modelId) {
        if (semaphore == null || permitId == null || permitId.isBlank()) {
            return;
        }
        try {
            semaphore.tryRelease(permitId);
        } catch (RuntimeException releaseFailure) {
            metrics.recordCapacityLeaseEvent(provider, modelId, "release_failed");
            log.warn("AI model capacity permit release failed, provider={}, modelId={}",
                    safe(provider), safe(modelId), LogExceptionSanitizer.sanitize(releaseFailure));
        }
    }

    private long estimateTokenReservation(ChatRequest request, int configuredMaxOutputTokens) {
        int requestChars = String.valueOf(request).length();
        long estimatedInputTokens = Math.max(1L, (requestChars + 3L) / 4L);
        long outputReservation = Math.min(
                configuredMaxOutputTokens,
                properties.getMaxReservedOutputTokens()
        );
        return estimatedInputTokens + outputReservation;
    }

    private Duration maximumHold(Duration upstreamTimeout) {
        if (upstreamTimeout == null) {
            return properties.getMaximumHold();
        }
        Duration timeoutWithGrace;
        try {
            timeoutWithGrace = upstreamTimeout.plus(properties.getMaximumHoldGrace());
        } catch (ArithmeticException overflow) {
            return properties.getMaximumHold();
        }
        return shorter(timeoutWithGrace, properties.getMaximumHold());
    }

    private Duration shorter(Duration left, Duration right) {
        return left.compareTo(right) <= 0 ? left : right;
    }

    private String modelIdentity(String provider, String modelId) {
        return DigestUtil.sha256Hex(normalize(provider) + ":" + normalize(modelId));
    }

    private void requireIdentity(String provider,
                                 String modelId,
                                 int configuredMaxOutputTokens,
                                 ChatRequest request,
                                 Duration upstreamTimeout) {
        if (provider == null || provider.isBlank()
                || modelId == null || modelId.isBlank()
                || configuredMaxOutputTokens <= 0
                || request == null) {
            throw new IllegalArgumentException("AI model capacity identity is incomplete");
        }
        if (upstreamTimeout != null && (upstreamTimeout.isZero() || upstreamTimeout.isNegative())) {
            throw new IllegalArgumentException("AI model upstream timeout must be positive");
        }
    }

    private String normalize(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        String normalized = value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9_.-]", "_");
        return normalized.length() <= 80 ? normalized : normalized.substring(0, 80);
    }

    private Duration elapsed(long startedAt) {
        return Duration.ofNanos(Math.max(0L, nanoTime.getAsLong() - startedAt));
    }

    private long positiveMillis(Duration duration) {
        return Math.max(1L, duration.toMillis());
    }

    private long deadline(long startedAt, Duration duration) {
        long durationNanos;
        try {
            durationNanos = duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
        return startedAt > Long.MAX_VALUE - durationNanos
                ? Long.MAX_VALUE
                : startedAt + durationNanos;
    }

    private boolean reached(long now, long deadline) {
        return deadline != Long.MAX_VALUE && now >= deadline;
    }

    private long remainingNanos(long now, long deadline) {
        if (deadline == Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        if (now >= deadline) {
            return 0L;
        }
        long remaining = deadline - now;
        return remaining < 0L ? Long.MAX_VALUE : remaining;
    }

    private long renewalLeaseMillis(long now, long maximumHoldDeadline) {
        long configuredMillis = positiveMillis(properties.getPermitLease());
        long remainingNanos = remainingNanos(now, maximumHoldDeadline);
        if (remainingNanos == Long.MAX_VALUE) {
            return configuredMillis;
        }
        long remainingMillis = TimeUnit.NANOSECONDS.toMillis(remainingNanos);
        if (TimeUnit.MILLISECONDS.toNanos(remainingMillis) < remainingNanos) {
            remainingMillis++;
        }
        return Math.max(1L, Math.min(configuredMillis, remainingMillis));
    }

    private static ScheduledExecutorService createScheduler(int threads) {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> Thread.ofPlatform()
                .name("ai-model-capacity-lease-" + sequence.incrementAndGet())
                .daemon(true)
                .unstarted(runnable);
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(threads, threadFactory);
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return executor;
    }

    private final class RenewableLease implements Lease {

        private final RPermitExpirableSemaphore semaphore;
        private final String permitId;
        private final String provider;
        private final String modelId;
        private final long maximumHoldDeadline;
        private final ScheduledFuture<?> heartbeatFuture;
        private final ScheduledFuture<?> maximumHoldFuture;

        private boolean closed;
        private boolean lost;
        private boolean renewalInFlight;
        private boolean lossListenerInvoked;
        private Runnable lossListener;
        private long confirmedPermitDeadline;

        private RenewableLease(RPermitExpirableSemaphore semaphore,
                               String permitId,
                               String provider,
                               String modelId,
                               long permitAcquiredAt,
                               Duration initialPermitLease,
                               Duration maximumHold) {
            this.semaphore = semaphore;
            this.permitId = permitId;
            this.provider = provider;
            this.modelId = modelId;
            this.confirmedPermitDeadline = deadline(permitAcquiredAt, initialPermitLease);
            this.maximumHoldDeadline = deadline(permitAcquiredAt, maximumHold);

            long heartbeatNanos = properties.getHeartbeatInterval().toNanos();
            ScheduledFuture<?> heartbeat = leaseScheduler.scheduleWithFixedDelay(
                    this::renew,
                    heartbeatNanos,
                    heartbeatNanos,
                    TimeUnit.NANOSECONDS
            );
            try {
                long holdNanos = remainingNanos(nanoTime.getAsLong(), maximumHoldDeadline);
                this.maximumHoldFuture = leaseScheduler.schedule(
                        () -> markLost("max_hold_exceeded", null),
                        holdNanos,
                        TimeUnit.NANOSECONDS
                );
                this.heartbeatFuture = heartbeat;
            } catch (RuntimeException schedulingFailure) {
                heartbeat.cancel(false);
                throw schedulingFailure;
            }
        }

        @Override
        public synchronized boolean isValid() {
            return !lost;
        }

        @Override
        public void onLost(Runnable listener) {
            if (listener == null) {
                throw new IllegalArgumentException("capacity lease loss listener is required");
            }
            Runnable invoke = null;
            synchronized (this) {
                if (lossListener != null) {
                    throw new IllegalStateException("capacity lease loss listener is already registered");
                }
                lossListener = listener;
                if (lost && !lossListenerInvoked) {
                    lossListenerInvoked = true;
                    invoke = listener;
                }
            }
            invokeLossListener(invoke);
        }

        @Override
        public void close() {
            ScheduledFuture<?> heartbeat;
            ScheduledFuture<?> holdDeadline;
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                heartbeat = heartbeatFuture;
                holdDeadline = maximumHoldFuture;
            }
            cancel(heartbeat);
            cancel(holdDeadline);
            release(semaphore, permitId, provider, modelId);
        }

        private void renew() {
            long now = nanoTime.getAsLong();
            boolean inFlightExpired;
            synchronized (this) {
                if (closed) {
                    return;
                }
                inFlightExpired = renewalInFlight && reached(now, confirmedPermitDeadline);
                if (renewalInFlight && !inFlightExpired) {
                    return;
                }
            }
            if (inFlightExpired) {
                markLost("lost", null);
                return;
            }
            if (reached(now, maximumHoldDeadline)) {
                markLost("max_hold_exceeded", null);
                return;
            }

            long leaseMillis = renewalLeaseMillis(now, maximumHoldDeadline);
            synchronized (this) {
                if (closed) {
                    return;
                }
                renewalInFlight = true;
            }
            try {
                RFuture<Boolean> renewal = semaphore.updateLeaseTimeAsync(
                        permitId, leaseMillis, TimeUnit.MILLISECONDS);
                if (renewal == null) {
                    throw new IllegalStateException("capacity lease renewal future is unavailable");
                }
                renewal.whenComplete((renewed, failure) ->
                        completeRenewal(leaseMillis, renewed, failure));
            } catch (RuntimeException renewalFailure) {
                clearRenewalInFlight();
                handleRenewalFailure(renewalFailure);
            }
        }

        private void completeRenewal(long leaseMillis, Boolean renewed, Throwable failure) {
            synchronized (this) {
                renewalInFlight = false;
                if (closed) {
                    return;
                }
            }
            if (failure != null) {
                handleRenewalFailure(failure);
                return;
            }
            if (!Boolean.TRUE.equals(renewed)) {
                markLost("lost", null);
                return;
            }

            synchronized (this) {
                if (closed) {
                    return;
                }
                confirmedPermitDeadline = deadline(
                        nanoTime.getAsLong(), Duration.ofMillis(leaseMillis));
            }
            metrics.recordCapacityLeaseEvent(provider, modelId, "renewed");
        }

        private void clearRenewalInFlight() {
            synchronized (this) {
                renewalInFlight = false;
            }
        }

        private void handleRenewalFailure(Throwable renewalFailure) {
            boolean expired;
            synchronized (this) {
                if (closed) {
                    return;
                }
                expired = reached(nanoTime.getAsLong(), confirmedPermitDeadline);
            }
            if (expired) {
                markLost("lost", renewalFailure);
                return;
            }
            metrics.recordCapacityLeaseEvent(provider, modelId, "retryable_failure");
        }

        private void markLost(String outcome, Throwable cause) {
            Runnable listener;
            ScheduledFuture<?> heartbeat;
            ScheduledFuture<?> holdDeadline;
            synchronized (this) {
                if (closed) {
                    return;
                }
                lost = true;
                closed = true;
                heartbeat = heartbeatFuture;
                holdDeadline = maximumHoldFuture;
                if (lossListener != null && !lossListenerInvoked) {
                    lossListenerInvoked = true;
                    listener = lossListener;
                } else {
                    listener = null;
                }
            }

            cancel(heartbeat);
            cancel(holdDeadline);
            metrics.recordCapacityLeaseEvent(provider, modelId, outcome);
            if (cause != null) {
                log.warn("AI model capacity lease was lost, provider={}, modelId={}",
                        safe(provider), safe(modelId), LogExceptionSanitizer.sanitize(cause));
            } else if ("max_hold_exceeded".equals(outcome)) {
                log.warn("AI model capacity lease exceeded its maximum hold, provider={}, modelId={}",
                        safe(provider), safe(modelId));
            } else {
                log.warn("AI model capacity lease was lost, provider={}, modelId={}",
                        safe(provider), safe(modelId));
            }
            try {
                invokeLossListener(listener);
            } finally {
                release(semaphore, permitId, provider, modelId);
            }
        }

        private void invokeLossListener(Runnable listener) {
            if (listener == null) {
                return;
            }
            try {
                listener.run();
            } catch (RuntimeException listenerFailure) {
                log.warn("AI model capacity lease loss callback failed, provider={}, modelId={}",
                        safe(provider), safe(modelId), LogExceptionSanitizer.sanitize(listenerFailure));
            }
        }

        private void cancel(ScheduledFuture<?> future) {
            if (future != null) {
                future.cancel(false);
            }
        }
    }
}
