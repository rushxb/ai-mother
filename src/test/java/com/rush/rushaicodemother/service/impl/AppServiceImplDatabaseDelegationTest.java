package com.rush.rushaicodemother.service.impl;

import com.rush.rushaicodemother.model.entity.App;
import com.rush.rushaicodemother.model.entity.User;
import com.rush.rushaicodemother.model.vo.AppDatabaseResourceVO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AppServiceImplDatabaseDelegationTest {

    @Test
    void enableDatabaseMustReturnBusinessViewWithoutEntityConversionInAppService() {
        AppServiceImplTestFixture fixture = new AppServiceImplTestFixture();
        AppServiceImpl service = fixture.createService();
        User loginUser = new User();
        loginUser.setId(9L);
        App app = new App();
        app.setId(7L);
        app.setUserId(9L);
        AppDatabaseResourceVO expected = new AppDatabaseResourceVO();
        expected.setAppId(7L);
        when(fixture.persistenceService().findActiveById(7L)).thenReturn(app);
        when(fixture.databaseResourceService().enableDatabase(app)).thenReturn(expected);

        AppDatabaseResourceVO result = service.enableDatabase(7L, loginUser);

        assertSame(expected, result);
        verify(fixture.databaseResourceService()).enableDatabase(app);
    }
}
