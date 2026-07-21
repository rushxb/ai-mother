package com.rush.rushaicodemother.infrastructure.persistence.devserver;

import com.rush.rushaicodemother.mapper.DevServerSessionMapper;
import com.rush.rushaicodemother.model.entity.DevServerSessionEntity;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionClaimResult;
import com.rush.rushaicodemother.service.devserver.persistence.DevServerSessionRegistration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyBatisDevServerSessionRegistryTest {

    private DevServerSessionMapper mapper;
    private MyBatisDevServerSessionRegistry registry;
    private Instant now;

    @BeforeEach
    void setUp() {
        mapper = mock(DevServerSessionMapper.class);
        registry = new MyBatisDevServerSessionRegistry(mapper);
        now = Instant.parse("2026-07-17T08:00:00Z");
    }

    @Test
    void activeSessionMustPreventCrossNodeDuplicateStart() {
        when(mapper.selectByAppId(11L)).thenReturn(DevServerSessionEntity.builder()
                .appId(11L)
                .state("RUNNING")
                .leaseUntil(LocalDateTime.ofInstant(now.plusSeconds(30), ZoneOffset.UTC))
                .build());

        DevServerSessionClaimResult result = registry.claimStarting(
                registration(), now, now.plusSeconds(30), 3);

        assertEquals(DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS, result);
        verify(mapper, never()).insert(any());
    }

    @Test
    void userQuotaMustBeCheckedUnderTheUserRowLock() {
        when(mapper.countActiveByUser(anyLong(), any())).thenReturn(3L);

        DevServerSessionClaimResult result = registry.claimStarting(
                registration(), now, now.plusSeconds(30), 3);

        assertEquals(DevServerSessionClaimResult.USER_QUOTA_EXCEEDED, result);
        verify(mapper).lockUser(7L);
        verify(mapper, never()).insert(any());
    }

    @Test
    void newSessionMustBeInsertedAsTheDurableOwner() {
        when(mapper.insert(any())).thenReturn(1);

        DevServerSessionClaimResult result = registry.claimStarting(
                registration(), now, now.plusSeconds(30), 3);

        assertEquals(DevServerSessionClaimResult.ACQUIRED, result);
        verify(mapper).insert(any(DevServerSessionEntity.class));
    }

    @Test
    void expiredActiveSessionMustWaitForResourceRecoveryBeforeRestart() {
        when(mapper.selectByAppId(11L)).thenReturn(DevServerSessionEntity.builder()
                .appId(11L)
                .state("RUNNING")
                .leaseUntil(LocalDateTime.ofInstant(now.minusSeconds(1), ZoneOffset.UTC))
                .build());

        DevServerSessionClaimResult result = registry.claimStarting(
                registration(), now, now.plusSeconds(30), 3);

        assertEquals(DevServerSessionClaimResult.ACTIVE_SESSION_EXISTS, result);
        verify(mapper, never()).claimTerminal(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString(), any(), any());
    }

    @Test
    void recoveredTerminalSessionMayBeClaimedForAReplacementProcess() {
        when(mapper.selectByAppId(11L)).thenReturn(DevServerSessionEntity.builder()
                .appId(11L)
                .state("STOPPED")
                .build());
        when(mapper.claimTerminal(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString(), any(), any())).thenReturn(1);

        DevServerSessionClaimResult result = registry.claimStarting(
                registration(), now, now.plusSeconds(30), 3);

        assertEquals(DevServerSessionClaimResult.ACQUIRED, result);
        verify(mapper).claimTerminal(anyLong(), anyLong(), anyString(), anyString(), anyInt(),
                anyString(), any(), any());
    }

    private DevServerSessionRegistration registration() {
        return new DevServerSessionRegistration(
                11L, 7L, "node-a", "node-a:process", Path.of("project").toAbsolutePath(), 5180);
    }
}
