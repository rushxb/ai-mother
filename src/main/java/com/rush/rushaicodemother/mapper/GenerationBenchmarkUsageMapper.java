package com.rush.rushaicodemother.mapper;

import com.rush.rushaicodemother.model.entity.GenerationTask;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 生成基准测试用量数据访问映射器。
 */
public interface GenerationBenchmarkUsageMapper {

    @Select("""
            SELECT totalTokens, creditCost
            FROM generation_task
            WHERE taskId = #{taskId}
              AND isDelete = 0
            LIMIT 1
            """)
    GenerationTask selectUsageByTaskId(@Param("taskId") String taskId);
}
