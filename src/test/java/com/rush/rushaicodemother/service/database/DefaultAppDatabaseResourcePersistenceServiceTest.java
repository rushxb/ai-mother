package com.rush.rushaicodemother.service.database;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppDatabaseResourceMapper;
import com.rush.rushaicodemother.model.entity.AppDatabaseResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DefaultAppDatabaseResourcePersistenceServiceTest {

    private AppDatabaseResourceMapper mapper;
    private DefaultAppDatabaseResourcePersistenceService service;

    @BeforeEach
    void setUp() {
        mapper = mock(AppDatabaseResourceMapper.class);
        service = new DefaultAppDatabaseResourcePersistenceService(mapper);
    }

    @Test
    void enableMustUpsertControlledFieldsAndReloadActiveRowAsSuccessEvidence() {
        NewAppDatabaseResource command = validCommand();
        AppDatabaseResource persisted = new AppDatabaseResource();
        persisted.setId(101L);
        persisted.setAppId(7L);
        persisted.setStatus("active");
        when(mapper.selectActiveByAppId(7L)).thenReturn(persisted);

        AppDatabaseResource result = service.enableResource(command);

        assertSame(persisted, result);
        ArgumentCaptor<AppDatabaseResource> captor = ArgumentCaptor.forClass(AppDatabaseResource.class);
        org.mockito.InOrder order = inOrder(mapper);
        order.verify(mapper).upsertActiveResource(captor.capture());
        order.verify(mapper).selectActiveByAppId(7L);
        AppDatabaseResource writeModel = captor.getValue();
        assertEquals(7L, writeModel.getAppId());
        assertEquals(9L, writeModel.getUserId());
        assertEquals("db7", writeModel.getResourceId());
        assertEquals("SQLite", writeModel.getDbEngine());
        assertEquals("active", writeModel.getStatus());
        assertEquals(command.lastUsedTime(), writeModel.getLastUsedTime());
    }

    @Test
    void enableMustFailWhenUpsertDoesNotProduceTargetActiveRow() {
        when(mapper.selectActiveByAppId(7L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enableResource(validCommand())
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertEquals("Database 资源启用失败，请稍后重试", exception.getMessage());
    }

    @Test
    void enableMustMapUniqueResourceConflictToStableBusinessError() {
        when(mapper.upsertActiveResource(any()))
                .thenThrow(new DuplicateKeyException("uk_resourceId"));

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> service.enableResource(validCommand())
        );

        assertEquals(ErrorCode.OPERATION_ERROR.getCode(), exception.getCode());
        assertEquals("Database 资源标识冲突，请联系管理员检查资源数据", exception.getMessage());
        verify(mapper, never()).selectActiveByAppId(any());
    }

    @Test
    void invalidEnableCommandMustNotInvokeMapper() {
        NewAppDatabaseResource invalid = new NewAppDatabaseResource(
                null, 9L, "db7", "app Database", "http://db7.localhost",
                "SQLite", "go", "ask_every_time", LocalDateTime.now()
        );

        assertThrows(BusinessException.class, () -> service.enableResource(invalid));

        verifyNoInteractions(mapper);
    }

    @Test
    void batchLookupMustRemoveNullInvalidAndDuplicateIdsBeforeMapperInvocation() {
        when(mapper.selectActiveByAppIds(any())).thenReturn(List.of());

        service.findActiveByAppIds(Arrays.asList(null, 2L, -1L, 1L, 2L, 0L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(mapper).selectActiveByAppIds(captor.capture());
        assertEquals(List.of(2L, 1L), List.copyOf(captor.getValue()));
    }

    @Test
    void emptyBatchLookupMustNotInvokeMapper() {
        assertEquals(List.of(), service.findActiveByAppIds(Arrays.asList(null, -1L, 0L)));

        verify(mapper, never()).selectActiveByAppIds(any());
    }

    @Test
    void invalidSingleLookupMustNotInvokeMapper() {
        assertThrows(BusinessException.class, () -> service.findActiveByAppId(0L));

        verifyNoInteractions(mapper);
    }

    private NewAppDatabaseResource validCommand() {
        return new NewAppDatabaseResource(
                7L,
                9L,
                "db7",
                "app Database",
                "http://db7.localhost",
                "SQLite",
                "go",
                "ask_every_time",
                LocalDateTime.of(2026, 7, 14, 10, 30)
        );
    }
}
