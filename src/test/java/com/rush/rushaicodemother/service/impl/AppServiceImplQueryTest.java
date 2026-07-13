package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.model.dto.app.AppQueryRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class AppServiceImplQueryTest {

    private final AppServiceImpl service = new AppServiceImplTestFixture().createService();

    @Test
    void missingSortFieldMustUseDefaultSortWithoutThrowing() {
        AppQueryRequest request = new AppQueryRequest();

        assertDoesNotThrow(() -> service.getQueryWrapper(request));
    }
}
