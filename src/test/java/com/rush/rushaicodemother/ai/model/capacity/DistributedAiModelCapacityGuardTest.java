package com.rush.rushaicodemother.ai.model.capacity;

import com.rush.rushaicodemother.config.AiModelCapacityProperties;
import com.rush.rushaicodemother.monitor.AiModelMetricsCollector;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.redisson.api.RFuture;
import org.redisson.api.RPermitExpirableSemaphore;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.redisson.misc.CompletableFutureWrapper;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DistributedAiModelCapacityGuardTest {

    @Test
    void disabledCapacityPolicyMustNotTouchRedisOrScheduler() {
        RedissonClient redisson = mock(RedissonClient.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        AiModelCapacityProperties properties = properties(false);
        DistributedAiModelCapacityGuard guard = new DistributedAiModelCapacityGuard(
                redisson, properties, mock(AiModelMetricsCollector.class), scheduler, () -> 0L);

        guard.acquire("openai", "gpt-test", 4096, request()).close();

        verifyNoInteractions(redisson, scheduler);
    }

    @Test
    void successfulAdmissionMustRenewAndReleaseTheConcurrencyPermit() throws Exception {
        Fixture fixture = fixture();
        AtomicLong now = new AtomicLong();
        DistributedAiModelCapacityGuard guard = fixture.guard(now);

        AiModelCapacityGuard.Lease lease = guard.acquire(
                "openai", "gpt-test", 4096, request());
        now.set(Duration.ofSeconds(20).toNanos());
        fixture.heartbeat().run();
        lease.close();

        verify(fixture.semaphore).trySetPermits(4);
        verify(fixture.semaphore).tryAcquire(250L, 60_000L, TimeUnit.MILLISECONDS);
        verify(fixture.rpm).tryAcquire(1L, Duration.ofMillis(250));
        verify(fixture.tpm).tryAcquire(anyLong(), eq(Duration.ofMillis(250)));
        verify(fixture.semaphore).updateLeaseTimeAsync(
                "permit-1", 60_000L, TimeUnit.MILLISECONDS);
        verify(fixture.semaphore).tryRelease("permit-1");
        verify(fixture.heartbeatFuture).cancel(false);
        verify(fixture.maximumHoldFuture).cancel(false);
        verify(fixture.metrics).recordCapacityLeaseEvent(
                "openai", "gpt-test", "renewed");
        verify(fixture.metrics).recordCapacityAdmission(
                eq("openai"), eq("gpt-test"), eq("all"), eq("acquired"),
                any(Duration.class));
    }

    @Test
    void providerTimeoutMustBoundInitialLeaseAndMaximumHoldDeadline() throws Exception {
        Fixture fixture = fixture();
        AtomicLong now = new AtomicLong();
        DistributedAiModelCapacityGuard guard = fixture.guard(now);

        AiModelCapacityGuard.Lease lease = guard.acquire(
                "openai", "gpt-test", 4096, request(), Duration.ofSeconds(10));

        verify(fixture.semaphore).tryAcquire(250L, 40_000L, TimeUnit.MILLISECONDS);
        verify(fixture.scheduler).schedule(
                any(Runnable.class), eq(Duration.ofSeconds(40).toNanos()), eq(TimeUnit.NANOSECONDS));
        lease.close();
    }

    @Test
    void transientRenewalFailureMustBeToleratedOnlyBeforeConfirmedExpiry() throws Exception {
        Fixture fixture = fixture();
        AtomicLong now = new AtomicLong();
        when(fixture.semaphore.updateLeaseTimeAsync(anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenThrow(new IllegalStateException("redis unavailable"));
        DistributedAiModelCapacityGuard guard = fixture.guard(now);
        AiModelCapacityGuard.Lease lease = guard.acquire(
                "openai", "gpt-test", 4096, request());

        now.set(Duration.ofSeconds(20).toNanos());
        fixture.heartbeat().run();

        assertTrue(lease.isValid());
        verify(fixture.metrics).recordCapacityLeaseEvent(
                "openai", "gpt-test", "retryable_failure");
        verify(fixture.semaphore, never()).tryRelease("permit-1");

        now.set(Duration.ofSeconds(61).toNanos());
        fixture.heartbeat().run();

        assertFalse(lease.isValid());
        verify(fixture.metrics).recordCapacityLeaseEvent("openai", "gpt-test", "lost");
        verify(fixture.semaphore).tryRelease("permit-1");
    }

    @Test
    void missingPermitDuringRenewalMustNotifyLossListenerExactlyOnce() throws Exception {
        Fixture fixture = fixture();
        AtomicLong now = new AtomicLong(Duration.ofSeconds(20).toNanos());
        fixture.completeRenewal(false, null);
        DistributedAiModelCapacityGuard guard = fixture.guard(now);
        now.set(0L);
        AiModelCapacityGuard.Lease lease = guard.acquire(
                "openai", "gpt-test", 4096, request());
        AtomicInteger losses = new AtomicInteger();
        lease.onLost(losses::incrementAndGet);

        now.set(Duration.ofSeconds(20).toNanos());
        fixture.heartbeat().run();
        fixture.heartbeat().run();

        assertFalse(lease.isValid());
        assertEquals(1, losses.get());
        verify(fixture.semaphore).tryRelease("permit-1");
    }

    @Test
    void hungAsynchronousRenewalMustNotOutliveTheLastConfirmedLeaseDeadline() throws Exception {
        Fixture fixture = fixture();
        fixture.pendingRenewal();
        AtomicLong now = new AtomicLong();
        DistributedAiModelCapacityGuard guard = fixture.guard(now);
        AiModelCapacityGuard.Lease lease = guard.acquire(
                "openai", "gpt-test", 4096, request());

        now.set(Duration.ofSeconds(20).toNanos());
        fixture.heartbeat().run();
        assertTrue(lease.isValid());

        now.set(Duration.ofSeconds(61).toNanos());
        fixture.heartbeat().run();

        assertFalse(lease.isValid());
        verify(fixture.semaphore, times(1)).updateLeaseTimeAsync(
                "permit-1", 60_000L, TimeUnit.MILLISECONDS);
        verify(fixture.metrics).recordCapacityLeaseEvent("openai", "gpt-test", "lost");
        verify(fixture.semaphore).tryRelease("permit-1");
    }

    @Test
    void maximumHoldDeadlineMustFailClosedAndReleaseThePermit() throws Exception {
        Fixture fixture = fixture();
        AtomicLong now = new AtomicLong();
        DistributedAiModelCapacityGuard guard = fixture.guard(now);
        AiModelCapacityGuard.Lease lease = guard.acquire(
                "openai", "gpt-test", 4096, request(), Duration.ofSeconds(10));
        AtomicInteger losses = new AtomicInteger();
        lease.onLost(losses::incrementAndGet);

        fixture.maximumHold().run();

        assertFalse(lease.isValid());
        assertEquals(1, losses.get());
        verify(fixture.metrics).recordCapacityLeaseEvent(
                "openai", "gpt-test", "max_hold_exceeded");
        verify(fixture.semaphore).tryRelease("permit-1");
    }

    @Test
    void exhaustedConcurrencyMustFailFastWithoutConsumingRateTokens() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RPermitExpirableSemaphore semaphore = mock(RPermitExpirableSemaphore.class);
        when(redisson.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn(null);
        DistributedAiModelCapacityGuard guard = new DistributedAiModelCapacityGuard(
                redisson,
                properties(true),
                mock(AiModelMetricsCollector.class),
                mock(ScheduledExecutorService.class),
                () -> 0L);

        AiModelCapacityException failure = assertThrows(
                AiModelCapacityException.class,
                () -> guard.acquire("openai", "gpt-test", 4096, request()));

        assertEquals("concurrency", failure.gate());
        verify(redisson, never()).getRateLimiter(anyString());
    }

    @Test
    void configuredFailOpenMustBypassRedisInfrastructureFailure() {
        RedissonClient redisson = mock(RedissonClient.class);
        when(redisson.getPermitExpirableSemaphore(anyString()))
                .thenThrow(new IllegalStateException("redis unavailable"));
        AiModelCapacityProperties properties = properties(true);
        properties.setFailOpen(true);
        AiModelMetricsCollector metrics = mock(AiModelMetricsCollector.class);
        DistributedAiModelCapacityGuard guard = new DistributedAiModelCapacityGuard(
                redisson,
                properties,
                metrics,
                mock(ScheduledExecutorService.class),
                () -> 0L);

        guard.acquire("openai", "gpt-test", 4096, request()).close();

        verify(metrics).recordCapacityAdmission(
                eq("openai"), eq("gpt-test"), eq("infrastructure"), eq("bypassed"),
                any(Duration.class));
    }

    private Fixture fixture() throws Exception {
        RedissonClient redisson = mock(RedissonClient.class);
        RPermitExpirableSemaphore semaphore = mock(RPermitExpirableSemaphore.class);
        RRateLimiter rpm = mock(RRateLimiter.class);
        RRateLimiter tpm = mock(RRateLimiter.class);
        ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
        ScheduledFuture<?> heartbeatFuture = mock(ScheduledFuture.class);
        ScheduledFuture<?> maximumHoldFuture = mock(ScheduledFuture.class);
        AtomicReference<RFuture<Boolean>> renewalFuture = new AtomicReference<>(
                new CompletableFutureWrapper<>(true));
        AiModelMetricsCollector metrics = mock(AiModelMetricsCollector.class);

        when(redisson.getPermitExpirableSemaphore(anyString())).thenReturn(semaphore);
        when(redisson.getRateLimiter(anyString())).thenReturn(rpm, tpm);
        when(semaphore.tryAcquire(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenReturn("permit-1");
        when(semaphore.updateLeaseTimeAsync(anyString(), anyLong(), eq(TimeUnit.MILLISECONDS)))
                .thenAnswer(invocation -> renewalFuture.get());
        when(rpm.trySetRate(RateType.OVERALL, 120L, Duration.ofMinutes(1))).thenReturn(true);
        when(tpm.trySetRate(RateType.OVERALL, 500_000L, Duration.ofMinutes(1))).thenReturn(true);
        when(rpm.tryAcquire(1L, Duration.ofMillis(250))).thenReturn(true);
        when(tpm.tryAcquire(anyLong(), eq(Duration.ofMillis(250)))).thenReturn(true);
        doReturn(heartbeatFuture).when(scheduler).scheduleWithFixedDelay(
                any(Runnable.class), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS));
        doReturn(maximumHoldFuture).when(scheduler).schedule(
                any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS));
        Fixture fixture = new Fixture(
                redisson, semaphore, rpm, tpm, scheduler,
                heartbeatFuture, maximumHoldFuture, renewalFuture, metrics);
        fixture.completeRenewal(true, null);
        return fixture;
    }

    private AiModelCapacityProperties properties(boolean enabled) {
        AiModelCapacityProperties properties = new AiModelCapacityProperties();
        properties.setEnabled(enabled);
        return properties;
    }

    private ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hello")).build();
    }

    private record Fixture(
            RedissonClient redisson,
            RPermitExpirableSemaphore semaphore,
            RRateLimiter rpm,
            RRateLimiter tpm,
            ScheduledExecutorService scheduler,
            ScheduledFuture<?> heartbeatFuture,
            ScheduledFuture<?> maximumHoldFuture,
            AtomicReference<RFuture<Boolean>> renewalFuture,
            AiModelMetricsCollector metrics
    ) {
        private DistributedAiModelCapacityGuard guard(AtomicLong now) {
            AiModelCapacityProperties properties = new AiModelCapacityProperties();
            properties.setEnabled(true);
            return new DistributedAiModelCapacityGuard(
                    redisson, properties, metrics, scheduler, now::get);
        }

        private Runnable heartbeat() {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).scheduleWithFixedDelay(
                    captor.capture(), anyLong(), anyLong(), eq(TimeUnit.NANOSECONDS));
            return captor.getValue();
        }

        private Runnable maximumHold() {
            ArgumentCaptor<Runnable> captor = ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).schedule(
                    captor.capture(), anyLong(), eq(TimeUnit.NANOSECONDS));
            return captor.getValue();
        }

        private void completeRenewal(Boolean renewed, Throwable failure) {
            renewalFuture.set(failure == null
                    ? new CompletableFutureWrapper<>(renewed)
                    : new CompletableFutureWrapper<>(failure));
        }

        private void pendingRenewal() {
            renewalFuture.set(new CompletableFutureWrapper<>(new CompletableFuture<>()));
        }
    }
}
