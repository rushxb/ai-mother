package com.rush.rushaicodemother.mapper;

import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/** 应用删除持久化契约，防止测试桩拥有字段而真实查询没有加载。 */
class AppLifecycleDataMapperContractTest {

    @Test
    void deletionStateQueryMustLoadTenantAndGenerationOwnershipUnderRowLock() throws Exception {
        Method query = AppLifecycleDataMapper.class
                .getMethod("selectDeletionState", Long.class);
        Select select = query.getAnnotation(Select.class);

        assertThat(select).as("删除状态查询必须显式声明 SQL").isNotNull();
        String sql = String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("tenantid")
                .contains("isgenerating")
                .contains("generatingtaskid")
                .contains("generationleaseuntil")
                .contains("generationexecutionepoch")
                .contains("for update");
    }

    @Test
    void generationTaskGuardMustFailClosedForRunningTasksAndPendingPublications() throws Exception {
        Method query = AppLifecycleDataMapper.class
                .getMethod("countDeletionBlockingGenerationTasks", Long.class);
        Select select = query.getAnnotation(Select.class);

        assertThat(select).as("生成任务删除门禁必须显式声明 SQL").isNotNull();
        String sql = String.join(" ", select.value())
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);

        assertThat(sql)
                .contains("status not in")
                .contains("'success'")
                .contains("'failed'")
                .contains("'cancelled'")
                .contains("'deadline_exceeded'")
                .contains("publicationstatus")
                .contains("'prepared'")
                .contains("'filesystem_activated'")
                .contains("'rollback_required'")
                .contains("isdelete = 0");
    }
}
