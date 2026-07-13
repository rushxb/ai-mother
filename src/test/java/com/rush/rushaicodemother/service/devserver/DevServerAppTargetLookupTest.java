package com.rush.rushaicodemother.service.devserver;

import com.rush.rushaicodemother.exception.BusinessException;
import com.rush.rushaicodemother.exception.ErrorCode;
import com.rush.rushaicodemother.mapper.AppMapper;
import com.rush.rushaicodemother.model.entity.App;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DevServerAppTargetLookupTest {

    private AppMapper appMapper;
    private DevServerAppTargetLookup targetLookup;

    @BeforeEach
    void setUp() {
        appMapper = mock(AppMapper.class);
        targetLookup = new DevServerAppTargetLookup(appMapper);
    }

    @Test
    void existingApplicationMustBeReturned() {
        App app = new App();
        app.setId(11L);
        when(appMapper.selectDevServerTarget(11L)).thenReturn(app);

        App result = targetLookup.requireTarget(11L);

        assertSame(app, result);
        verify(appMapper).selectDevServerTarget(11L);
    }

    @Test
    void missingApplicationMustRaiseNotFoundError() {
        when(appMapper.selectDevServerTarget(11L)).thenReturn(null);

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> targetLookup.requireTarget(11L)
        );

        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), exception.getCode());
        assertEquals("应用不存在", exception.getMessage());
    }

    @Test
    void invalidApplicationIdMustFailBeforeQuery() {
        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> targetLookup.requireTarget(0L)
        );

        assertEquals(ErrorCode.PARAMS_ERROR.getCode(), exception.getCode());
        verifyNoInteractions(appMapper);
    }
}
