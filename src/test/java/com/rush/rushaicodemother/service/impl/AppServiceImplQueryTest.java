package com.rush.rushaicodemother.service.impl;

import com.mybatisflex.core.service.IService;
import com.rush.rushaicodemother.service.AppService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * 保留该类名以覆盖无 clean 增量构建中可能残留的旧查询测试字节码。
 */
class AppServiceImplQueryTest {

    @Test
    void appServiceMustNotExposeGenericCrudContract() {
        assertFalse(IService.class.isAssignableFrom(AppService.class));
        assertEquals(Object.class, AppServiceImpl.class.getSuperclass());
    }
}
